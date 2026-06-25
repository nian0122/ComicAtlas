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
            List<Map<String, Object>> pagesData = (List<Map<String, Object>>) metadata.get("pages");
            long totalSize = ((Number) metadata.get("totalSize")).longValue();

            // UPDATE comic
            Comic comic = comicMapper.selectById(comicId);
            comic.setTitle((String) comicData.get("title"));
            comic.setTitleJpn((String) comicData.get("titleJpn"));
            comic.setAuthor((String) comicData.get("author"));
            comic.setCategory((String) comicData.get("category"));
            if (comicData.get("sourceGalleryId") != null) {
                comic.setSourceGalleryId(comicData.get("sourceGalleryId").toString());
            }
            comic.setStatus("READY");
            comic.setFileSize(totalSize);
            comic.setHqSize(totalSize);
            comicMapper.updateById(comic);

            // INSERT chapter (IGNORE on duplicate)
            Chapter chapter = new Chapter();
            chapter.setComicId(comicId);
            chapter.setTitle(comic.getTitle());
            chapter.setChapterNo("1");
            chapter.setPageCount(pagesData.size());
            try {
                chapterMapper.insert(chapter);
            } catch (Exception ignored) {
                chapter = chapterMapper.selectOne(new LambdaQueryWrapper<Chapter>()
                    .eq(Chapter::getComicId, comicId).eq(Chapter::getChapterNo, "1"));
            }

            // BATCH INSERT page (IGNORE on duplicate)
            for (Map<String, Object> pd : pagesData) {
                com.comicatlas.api.comic.entity.Page page = new com.comicatlas.api.comic.entity.Page();
                page.setChapterId(chapter.getId());
                page.setPageNumber(((Number) pd.get("pageNumber")).intValue());
                page.setImageName((String) pd.get("imageName"));
                if (pd.get("width") != null) page.setWidth(((Number) pd.get("width")).intValue());
                if (pd.get("height") != null) page.setHeight(((Number) pd.get("height")).intValue());
                if (pd.get("fileSize") != null) page.setFileSize(((Number) pd.get("fileSize")).longValue());
                page.setLqStatus("PENDING");
                try {
                    pageMapper.insert(page);
                } catch (Exception ignored) { }
            }

            // INSERT IGNORE tags
            List<Map<String, String>> tagsData = (List<Map<String, String>>) comicData.get("tags");
            if (tagsData != null) {
                for (Map<String, String> td : tagsData) {
                    Tag tag = new Tag();
                    tag.setName(td.get("name"));
                    tag.setType(td.get("type"));
                    try { tagMapper.insert(tag); } catch (Exception ignored) { }
                    tag = tagMapper.selectOne(new LambdaQueryWrapper<Tag>()
                        .eq(Tag::getName, td.get("name")).eq(Tag::getType, td.get("type")));
                    ComicTag ct = new ComicTag();
                    ct.setComicId(comicId);
                    ct.setTagId(tag.getId());
                    try { comicTagMapper.insert(ct); } catch (Exception ignored) { }
                }
            }

            // UPDATE totals
            comic.setTotalPages(pagesData.size());
            comicMapper.updateById(comic);

            // UPDATE import_task
            ImportTask task = taskMapper.selectById(taskId);
            task.setStatus("LQ_GENERATING");
            task.setEndTime(LocalDateTime.now());
            if (task.getStartTime() != null) {
                task.setDurationMs(Duration.between(task.getStartTime(), task.getEndTime()).toMillis());
            }
            taskMapper.updateById(task);

            // Publish LQGenerateTask (per chapter)
            Map<String, Object> lqMsg = Map.of(
                "messageId", UUID.randomUUID().toString(),
                "comicId", comicId,
                "chapterId", chapter.getId()
            );
            rabbitTemplate.convertAndSend("comic.image", "lq.generate", lqMsg);

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
