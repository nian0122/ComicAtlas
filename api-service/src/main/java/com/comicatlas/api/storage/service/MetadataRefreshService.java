package com.comicatlas.api.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.admin.dto.RefreshMetadataResult;
import com.comicatlas.api.common.scan.RecoveryEngine;
import com.comicatlas.api.common.scan.ScannedMediaInfo;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.VideoMetadataFixRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 刷新单漫画元数据（存储操作域）。
 * <p>
 * 从 {@code AdminServiceImpl.refreshMetadata} 提取的同步扫盘逻辑：
 * CAS 锁 READY→REFRESHING，逐章节比对 HQ 目录与 DB 页面
 * （更新宽高/文件大小、增删媒体、章节页数、漫画总量），完成后发 MQ
 * 委托 Worker 重新导出 metadata.json，finally 恢复 READY。
 * 供 {@code POST /api/storage/refresh-metadata/comics/{id}} 与
 * 管理任务管线 METADATA_REFRESH 完成事件共用，收敛双路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataRefreshService {

    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final RecoveryEngine recoveryEngine;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 刷新单漫画元数据：重新扫描 HQ 目录，更新 page 的宽高/文件大小，
     * 完成后发 MQ 委托 Worker 重新导出 metadata.json。
     *
     * @param comicId 漫画 ID
     * @return 刷新结果（沿用 {@code admin.dto.RefreshMetadataResult}）
     */
    public RefreshMetadataResult refresh(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        if (comic.getStatus() != ComicStatus.READY) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "漫画状态异常，当前状态: " + comic.getStatus());
        }

        // CAS 锁：READY → REFRESHING（必须限定漫画 ID，避免整库状态被置为 REFRESHING）
        int updated = comicMapper.update(null,
                new LambdaUpdateWrapper<Comic>()
                        .eq(Comic::getId, comicId)
                        .eq(Comic::getStatus, ComicStatus.READY)
                        .set(Comic::getStatus, ComicStatus.REFRESHING));
        if (updated == 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画正在刷新中");
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> stats = transactionTemplate.execute(status -> {
                List<Chapter> chapters = chapterMapper.selectList(
                        new LambdaQueryWrapper<Chapter>()
                                .eq(Chapter::getComicId, comicId)
                                .orderByAsc(Chapter::getGlobalOrder));

                int totalPages = 0;
                long totalSize = 0;

                for (Chapter chapter : chapters) {
                    List<ScannedMediaInfo> hqImages = recoveryEngine.scanChapterPages(comicId, chapter.getGlobalOrder());

                    List<Media> dbPagesList = mediaMapper.selectList(
                            new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapter.getId()));
                    Map<String, Media> dbPageMap = new LinkedHashMap<>();
                    for (Media media : dbPagesList) {
                        String hqPath = media.getHqPath();
                        if (hqPath != null && hqPath.contains("/")) {
                            String fileName = hqPath.substring(hqPath.lastIndexOf('/') + 1);
                            if (!fileName.isEmpty() && !"null".equals(fileName)) {
                                dbPageMap.put(fileName, media);
                            }
                        }
                    }

                    int nextPageNumber = dbPagesList.isEmpty() ? 1 :
                            dbPagesList.stream().mapToInt(Media::getPageNumber).max().orElse(0) + 1;

                    for (ScannedMediaInfo pi : hqImages) {
                        if (dbPageMap.containsKey(pi.imageName())) {
                            Media existing = dbPageMap.get(pi.imageName());
                            existing.setFileSize(pi.fileSize());
                            if (!"VIDEO".equals(existing.getMediaType())) {
                                existing.setWidth(pi.width());
                                existing.setHeight(pi.height());
                            }
                            existing.setHqStatus(HqStatus.READY);
                            mediaMapper.updateById(existing);
                            dbPageMap.remove(pi.imageName());
                        } else {
                            Media newPage = new Media();
                            newPage.setChapterId(chapter.getId());
                            newPage.setPageNumber(nextPageNumber++);
                            newPage.setHqRoot("HQ");
                            newPage.setHqPath(comicId + "/" + chapter.getGlobalOrder() + "/" + pi.imageName());
                            newPage.setHqStatus(pi.fileSize() > 0 ? HqStatus.READY : HqStatus.MISSING);
                            newPage.setLqStatus(LqStatus.NOT_GENERATED);
                            newPage.setFileSize(pi.fileSize());
                            newPage.setWidth(pi.width());
                            newPage.setHeight(pi.height());
                            newPage.setMediaType(pi.mediaType());
                            mediaMapper.insert(newPage);
                        }
                        totalSize += pi.fileSize();
                    }

                    for (Media leftover : dbPageMap.values()) {
                        mediaMapper.deleteById(leftover.getId());
                    }

                    int actualPageCount = hqImages.size();
                    chapter.setPageCount(actualPageCount);
                    chapterMapper.updateById(chapter);
                    totalPages += actualPageCount;
                }

                comic.setTotalPages(totalPages);
                comic.setFileSize(totalSize);
                comic.setHqSize(totalSize);
                comicMapper.updateById(comic);

                return Map.of("catalogs", 0, "chapters", chapters.size(), "pages", totalPages);
            });

            long durationMs = System.currentTimeMillis() - start;

            fixVideoMetadata(comicId);
            catalogCacheInvalidator.evict(comicId);

            try {
                rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.METADATA_REFRESH_REQUESTED,
                        new MetadataRefreshEvent(null, null, comicId));
            } catch (Exception e) {
                log.error("发送 metadata 刷新 MQ 消息失败: comicId={}", comicId, e);
            }

            return buildResult(comicId, stats, durationMs);
        } finally {
            comicMapper.update(null,
                    new LambdaUpdateWrapper<Comic>()
                            .eq(Comic::getId, comicId)
                            .set(Comic::getStatus, ComicStatus.READY));
        }
    }

    /**
     * 委托 Worker 修复视频元数据（ffprobe 补全时长/编码信息）。
     */
    private void fixVideoMetadata(Long comicId) {
        rabbitTemplate.convertAndSend(MqExchanges.IMAGE, MqRoutingKeys.VIDEO_METADATA_FIX_REQUESTED,
                new VideoMetadataFixRequestedEvent(null, null, comicId));
    }

    /**
     * 根据统计数据构造刷新结果。
     */
    private RefreshMetadataResult buildResult(Long comicId, Map<String, Object> stats, long durationMs) {
        return new RefreshMetadataResult(
                comicId,
                "READY",
                (int) stats.get("catalogs"),
                (int) stats.get("chapters"),
                (int) stats.get("pages"),
                durationMs,
                LocalDateTime.now());
    }
}
