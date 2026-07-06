package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.TaskStatusChangedEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportEventHandler {

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final ImportTaskMapper taskMapper;

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    @Transactional
    @RabbitListener(queues = "import.result.queue")
    @SuppressWarnings("unchecked")
    public void handleComicImported(ImportTaskCompletedEvent event) {
        String idempKey = "mq:event:" + event.eventId();
        if (Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(idempKey, "1", Duration.ofDays(1)))) {
            log.info("事件已处理，跳过: eventId={}", event.eventId());
            return;
        }

        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("ComicImported: taskId={}, comicId={}", taskId, comicId);

        try {
            Map<String, Object> metadata = objectMapper.readValue(
                new File(mangaRoot + "/metadata/" + taskId + ".json"),
                new TypeReference<Map<String, Object>>() {});

            Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
            List<Map<String, Object>> catalogsData = (List<Map<String, Object>>) metadata.get("catalogs");
            List<Map<String, Object>> chaptersData = (List<Map<String, Object>>) metadata.get("chapters");

            // 1. UPDATE comic
            Comic comic = comicMapper.selectById(comicId);
            comic.setTitle((String) comicData.get("title"));
            comic.setTitleJpn((String) comicData.get("titleJpn"));
            comic.setAuthor((String) comicData.get("author"));
            comic.setCategory((String) comicData.get("category"));
            if (comicData.get("sourceGalleryId") != null) {
                comic.setSourceGalleryId(comicData.get("sourceGalleryId").toString());
            }
            comic.setStoragePolicy("MANAGED");
            comic.setStatus("READY");

            // 2. INSERT catalog（有则写入，无则跳过）
            Map<Integer, Long> catalogIdMap = insertCatalogs(catalogsData, comicId);

            // 3. INSERT chapters + pages
            int totalPages = 0;
            long totalSize = 0;
            if (chaptersData != null) {
                for (Map<String, Object> chData : chaptersData) {
                    var result = insertChapter(chData, comicId, catalogIdMap);
                    totalPages += result.pages;
                    totalSize += result.size;
                }
            }

            comic.setTotalPages(totalPages);
            if (totalSize > 0) { comic.setFileSize(totalSize); comic.setHqSize(totalSize); }
            comicMapper.updateById(comic);

            // 4. UPDATE import_task
            ImportTask task = taskMapper.selectById(taskId);
            task.setStatus("SUCCESS");
            task.setEndTime(LocalDateTime.now());
            if (task.getStartTime() != null) {
                task.setDurationMs(Duration.between(task.getStartTime(), task.getEndTime()).toMillis());
            }
            taskMapper.updateById(task);

            log.info("ComicImported 完成: comicId={}, chapters={}, pages={}", comicId,
                chaptersData != null ? chaptersData.size() : 0, totalPages);

        } catch (Exception e) {
            log.error("ComicImported 失败: taskId={}", taskId, e);
            throw new RuntimeException("ComicImported 消费失败", e);
        }
    }

    private Map<Integer, Long> insertCatalogs(List<Map<String, Object>> catalogsData, Long comicId) {
        Map<Integer, Long> idMap = new LinkedHashMap<>();
        if (catalogsData == null) return idMap;

        for (int i = 0; i < catalogsData.size(); i++) {
            Map<String, Object> cd = catalogsData.get(i);
            Catalog cat = new Catalog();
            cat.setComicId(comicId);
            cat.setTitle((String) cd.get("title"));
            cat.setSortOrder((Integer) cd.getOrDefault("sortOrder", i));
            catalogMapper.insert(cat);
            idMap.put(i, cat.getId());
        }
        return idMap;
    }

    private record ChapterResult(int pages, long size) {}

    private ChapterResult insertChapter(Map<String, Object> chData, Long comicId,
                                         Map<Integer, Long> catalogIdMap) {
        Chapter chapter = new Chapter();
        chapter.setComicId(comicId);
        chapter.setTitle((String) chData.get("title"));
        chapter.setChapterNo((String) chData.get("chapterNo"));
        chapter.setSortOrder((Integer) chData.getOrDefault("sortOrder", 0));
        chapter.setGlobalOrder((Integer) chData.getOrDefault("globalOrder", 0));
        Object cid = chData.get("catalogId");
        if (cid != null) chapter.setCatalogId(catalogIdMap.get(((Number) cid).intValue()));

        List<Map<String, Object>> pageList = (List<Map<String, Object>>) chData.get("pages");
        chapter.setPageCount(pageList != null ? pageList.size() : 0);
        chapterMapper.insert(chapter);

        int pgCount = 0;
        long totalSize = 0;
        if (pageList != null) {
            for (Map<String, Object> pd : pageList) {
                Page page = new Page();
                page.setChapterId(chapter.getId());
                page.setPageNumber(((Number) pd.get("pageNumber")).intValue());
                page.setHqRoot("HQ");
                page.setHqPath((String) pd.getOrDefault("hqPath",
                    comicId + "/" + chapter.getGlobalOrder() + "/" + pd.get("imageName")));
                page.setHqStatus(pd.get("hqStatus") != null ? (String) pd.get("hqStatus") : "READY");
                page.setLqStatus("NOT_GENERATED");
                if (pd.get("fileSize") != null) page.setFileSize(((Number) pd.get("fileSize")).longValue());
                if (pd.get("width") != null) page.setWidth(((Number) pd.get("width")).intValue());
                if (pd.get("height") != null) page.setHeight(((Number) pd.get("height")).intValue());
                pageMapper.insert(page);
                totalSize += page.getFileSize() != null ? page.getFileSize() : 0;
                pgCount++;
            }
        }
        return new ChapterResult(pgCount, totalSize);
    }

    @RabbitListener(queues = "task.status.queue")
    @Transactional
    public void handleTaskStatusChanged(TaskStatusChangedEvent event) {
        Long taskId = event.taskId();
        String newStatus = event.status();
        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) return;

        task.setStatus(newStatus);
        if ("DOWNLOADING".equals(newStatus) && task.getStartTime() == null) {
            task.setStartTime(LocalDateTime.now());
        }
        task.setProgress(event.progress());
        if (event.speedBytesPerSec() > 0) task.setDownloadSpeed(event.speedBytesPerSec());
        if (event.etaSeconds() > 0) task.setEtaSeconds(event.etaSeconds());
        if (event.downloadMethod() != null) task.setDownloadMethod(event.downloadMethod());
        taskMapper.updateById(task);
    }
}
