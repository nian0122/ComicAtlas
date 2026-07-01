package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportEventHandler {

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final ImportTaskMapper taskMapper;

    @Transactional
    @RabbitListener(queues = "import.result.queue")
    @SuppressWarnings("unchecked")
    public void handleComicImported(Map<String, Object> msg) {
        String messageId = (String) msg.get("messageId");
        String idempKey = "mq:msg:" + messageId;
        if (Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(idempKey, "1", Duration.ofDays(1)))) {
            log.info("消息已处理，跳过: messageId={}", messageId);
            return;
        }

        Long taskId = Long.valueOf(msg.get("taskId").toString());
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        log.info("ComicImported: taskId={}, comicId={}", taskId, comicId);

        try {
            // 读取 metadata.json
            File metadataFile = new File("/manga/metadata/" + taskId + ".json");
            Map<String, Object> metadata = objectMapper.readValue(metadataFile,
                new TypeReference<Map<String, Object>>() {});

            Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chaptersData = (List<Map<String, Object>>) metadata.get("chapters");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pagesData = (List<Map<String, Object>>) metadata.get("pages");

            // UPDATE comic
            Comic comic = comicMapper.selectById(comicId);
            comic.setTitle((String) comicData.get("title"));
            comic.setTitleJpn((String) comicData.get("titleJpn"));
            comic.setAuthor((String) comicData.get("author"));
            comic.setCategory((String) comicData.get("category"));
            if (comicData.get("sourceGalleryId") != null) {
                comic.setSourceGalleryId(comicData.get("sourceGalleryId").toString());
            }
            if (comicData.get("storagePolicy") != null || comicData.get("storageType") != null) {
                String policy = comicData.get("storagePolicy") != null
                    ? (String) comicData.get("storagePolicy")
                    : (String) comicData.get("storageType");
                comic.setStoragePolicy(policy);
                comic.setRootKey((String) comicData.get("rootKey"));
                comic.setRelativePath((String) comicData.get("relativePath"));
            }
            comic.setStatus("READY");
            long totalSize = 0;
            int totalPages = 0;
            Chapter firstChapter = null;

            // 新格式：chapters
            if (chaptersData != null && !chaptersData.isEmpty()) {
                for (Map<String, Object> chData : chaptersData) {
                    Chapter chapter = new Chapter();
                    chapter.setComicId(comicId);
                    chapter.setTitle((String) chData.get("title"));
                    chapter.setChapterNo((String) chData.get("chapterNo"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> pageList = (List<Map<String, Object>>) chData.get("pages");
                    chapter.setPageCount(pageList != null ? pageList.size() : 0);
                    try {
                        chapterMapper.insert(chapter);
                    } catch (Exception ignored) {
                        chapter = chapterMapper.selectOne(new LambdaQueryWrapper<Chapter>()
                            .eq(Chapter::getComicId, comicId).eq(Chapter::getChapterNo, (String) chData.get("chapterNo")));
                    }
                    if (firstChapter == null) firstChapter = chapter;

                    if (pageList != null) {
                        for (Map<String, Object> pd : pageList) {
                            com.comicatlas.api.comic.entity.Page page =
                                new com.comicatlas.api.comic.entity.Page();
                            page.setChapterId(chapter.getId());
                            page.setPageNumber(((Number) pd.get("pageNumber")).intValue());
                            page.setHqRoot(pd.get("hqRoot") != null ? (String) pd.get("hqRoot") : "HQ");
                            page.setHqPath((String) pd.get("hqPath"));
                            page.setLqRoot((String) pd.get("lqRoot"));
                            page.setLqPath((String) pd.get("lqPath"));
                            page.setHqStatus(pd.get("hqStatus") != null ? (String) pd.get("hqStatus") : "PENDING");
                            page.setLqStatus(pd.get("lqStatus") != null ? (String) pd.get("lqStatus") : "PENDING");
                            if (pd.get("fileSize") != null) page.setFileSize(((Number) pd.get("fileSize")).longValue());
                            if (pd.get("width") != null) page.setWidth(((Number) pd.get("width")).intValue());
                            if (pd.get("height") != null) page.setHeight(((Number) pd.get("height")).intValue());
                            try {
                                pageMapper.insert(page);
                            } catch (Exception ignored) {}
                            totalSize += page.getFileSize() != null ? page.getFileSize() : 0;
                            totalPages++;
                        }
                    }
                }
            }
            // 旧格式：pages（兼容）
            else if (pagesData != null) {
                totalSize = ((Number) metadata.getOrDefault("totalSize", 0)).longValue();
                Chapter chapter = new Chapter();
                chapter.setComicId(comicId);
                chapter.setTitle(comic.getTitle());
                chapter.setChapterNo("1");
                chapter.setPageCount(pagesData.size());
                firstChapter = chapter;
                try {
                    chapterMapper.insert(chapter);
                } catch (Exception ignored) {
                    chapter = chapterMapper.selectOne(new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getComicId, comicId).eq(Chapter::getChapterNo, "1"));
                }
                for (Map<String, Object> pd : pagesData) {
                    com.comicatlas.api.comic.entity.Page page =
                        new com.comicatlas.api.comic.entity.Page();
                    page.setChapterId(chapter.getId());
                    page.setPageNumber(((Number) pd.get("pageNumber")).intValue());
                    page.setHqRoot("HQ");
                    page.setHqPath((String) pd.get("imageName"));
                    page.setHqStatus("READY");
                    page.setLqStatus("PENDING");
                    if (pd.get("fileSize") != null) page.setFileSize(((Number) pd.get("fileSize")).longValue());
                    if (pd.get("width") != null) page.setWidth(((Number) pd.get("width")).intValue());
                    if (pd.get("height") != null) page.setHeight(((Number) pd.get("height")).intValue());
                    try { pageMapper.insert(page); } catch (Exception ignored) {}
                    totalPages = pagesData.size();
                }
            }

            // UPDATE totals
            if (totalSize > 0) { comic.setFileSize(totalSize); comic.setHqSize(totalSize); }
            comic.setTotalPages(totalPages > 0 ? totalPages : comic.getTotalPages());
            comicMapper.updateById(comic);

            // UPDATE import_task
            ImportTask task = taskMapper.selectById(taskId);
            task.setStatus("SUCCESS");
            task.setEndTime(LocalDateTime.now());
            if (task.getStartTime() != null) {
                task.setDurationMs(Duration.between(task.getStartTime(), task.getEndTime()).toMillis());
            }
            taskMapper.updateById(task);

            // LQ 生成改为手动触发，不自动发送
            // if (firstChapter != null && !"EXTERNAL".equals(comic.getStorageType())) { ... }

            log.info("ComicImported 处理完成: comicId={}, pages={}", comicId, pagesData.size());

        } catch (Exception e) {
            log.error("ComicImported 处理失败: taskId={}", taskId, e);
            throw new RuntimeException("ComicImported 消费失败", e);
        }
    }

    @RabbitListener(queues = "task.status.queue")
    @Transactional
    public void handleTaskStatusChanged(Map<String, Object> msg) {
        Long taskId = Long.valueOf(msg.get("taskId").toString());
        String newStatus = (String) msg.get("newStatus");
        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) return;

        task.setStatus(newStatus);
        if ("DOWNLOADING".equals(newStatus) && task.getStartTime() == null) {
            task.setStartTime(LocalDateTime.now());
        }
        if (msg.get("progress") != null) task.setProgress(((Number) msg.get("progress")).intValue());
        if (msg.get("speedBytesPerSec") != null) task.setDownloadSpeed(((Number) msg.get("speedBytesPerSec")).longValue());
        if (msg.get("etaSeconds") != null) task.setEtaSeconds(((Number) msg.get("etaSeconds")).intValue());
        if (msg.get("downloadMethod") != null) task.setDownloadMethod((String) msg.get("downloadMethod"));
        taskMapper.updateById(task);
    }
}
