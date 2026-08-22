package com.comicatlas.api.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.api.task.entity.ManagementTask;
import com.comicatlas.api.task.entity.ManagementTaskItem;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.mapper.ManagementTaskMapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 管理任务查询服务，负责查询模型组装和目标摘要聚合。 */
@Service
@RequiredArgsConstructor
public class TaskQueryService {

    private static final String TARGET_TYPE_COMIC = "COMIC";
    private static final String TARGET_TYPE_CHAPTER = "CHAPTER";
    private static final String TARGET_TYPE_MEDIA = "MEDIA";

    private final ManagementTaskMapper taskMapper;
    private final ManagementTaskItemMapper itemMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final TaskResponseAssembler taskResponseAssembler;

    /** 分页查询管理任务。 */
    public IPage<ManagementTaskResponse> listTasks(int page, int size, TaskType type,
                                                    ManagementTaskStatus status, String batchId,
                                                    String targetType, Long targetId) {
        LambdaQueryWrapper<ManagementTask> wrapper = new LambdaQueryWrapper<>();
        if (type != null) {
            wrapper.eq(ManagementTask::getTaskType, type);
        }
        if (status != null) {
            wrapper.eq(ManagementTask::getStatus, status);
        }
        if (batchId != null && !batchId.isBlank()) {
            wrapper.eq(ManagementTask::getBatchId, batchId);
        }
        if (targetType != null && !targetType.isBlank()) {
            wrapper.eq(ManagementTask::getTargetType, targetType);
        }
        if (targetId != null) {
            List<Long> taskIds = itemMapper.selectTaskIdsByTarget(targetId);
            if (taskIds.isEmpty()) {
                return emptyPage(page, size);
            }
            wrapper.in(ManagementTask::getId, taskIds);
        }
        wrapper.orderByDesc(ManagementTask::getCreatedAt);

        IPage<ManagementTask> taskPage = taskMapper.selectPage(new Page<>(page, size), wrapper);
        List<ManagementTaskResponse> responses = taskPage.getRecords().stream()
                .map(taskResponseAssembler::toResponse)
                .collect(Collectors.toList());
        enrichTargetSummaries(taskPage.getRecords(), responses);
        IPage<ManagementTaskResponse> responsePage = new Page<>(page, size);
        responsePage.setTotal(taskPage.getTotal());
        responsePage.setRecords(responses);
        return responsePage;
    }

    /** 查询任务详情。 */
    public ManagementTaskResponse getTask(Long taskId) {
        ManagementTask task = requireTask(taskId);
        ManagementTaskResponse response = taskResponseAssembler.toResponse(task);
        enrichTargetSummaries(List.of(task), List.of(response));
        return response;
    }

    /** 查询任务项。 */
    public List<ManagementTaskItemResponse> getTaskItems(Long taskId) {
        requireTask(taskId);
        return itemMapper.selectList(new LambdaQueryWrapper<ManagementTaskItem>()
                        .eq(ManagementTaskItem::getTaskId, taskId)
                        .orderByAsc(ManagementTaskItem::getId))
                .stream()
                .map(taskResponseAssembler::toItemResponse)
                .collect(Collectors.toList());
    }

    private ManagementTask requireTask(Long taskId) {
        ManagementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }
        return task;
    }

    private IPage<ManagementTaskResponse> emptyPage(int page, int size) {
        IPage<ManagementTaskResponse> emptyPage = new Page<>(page, size);
        emptyPage.setTotal(0);
        emptyPage.setRecords(List.of());
        return emptyPage;
    }

    private void enrichTargetSummaries(List<ManagementTask> tasks,
                                       List<ManagementTaskResponse> responses) {
        if (tasks.isEmpty()) {
            return;
        }
        Map<Long, ManagementTaskResponse> responseByTaskId = new HashMap<>();
        responses.forEach(response -> responseByTaskId.put(response.getId(), response));
        List<ManagementTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<ManagementTaskItem>()
                .in(ManagementTaskItem::getTaskId, tasks.stream().map(ManagementTask::getId).toList())
                .orderByAsc(ManagementTaskItem::getId));
        Map<Long, ManagementTaskItem> firstItems = new HashMap<>();
        items.forEach(item -> firstItems.putIfAbsent(item.getTaskId(), item));
        Map<Long, Long> parentComicIds = resolveParentComicIds(firstItems);
        Map<Long, Comic> comics = new HashMap<>();
        List<Long> comicIds = parentComicIds.values().stream().filter(Objects::nonNull).distinct().toList();
        if (!comicIds.isEmpty()) {
            comicMapper.selectBatchIds(comicIds).forEach(comic -> comics.put(comic.getId(), comic));
        }
        firstItems.values().forEach(item -> {
            ManagementTaskResponse response = responseByTaskId.get(item.getTaskId());
            if (response == null) {
                return;
            }
            Long parentComicId = parentComicIds.get(item.getTaskId());
            Comic comic = parentComicId == null ? null : comics.get(parentComicId);
            response.setTargetId(TARGET_TYPE_COMIC.equals(response.getTargetType())
                    && parentComicId != null ? parentComicId : item.getTargetId());
            response.setTargetName(comic == null ? null : comic.getTitle());
        });
    }

    private Map<Long, Long> resolveParentComicIds(Map<Long, ManagementTaskItem> firstItems) {
        Map<Long, Long> parentComicIds = new HashMap<>();
        Map<Long, Long> chapterComicIds = new HashMap<>();
        Map<Long, Long> mediaChapterIds = new HashMap<>();
        List<Long> chapterIds = firstItems.values().stream()
                .filter(item -> TARGET_TYPE_CHAPTER.equals(item.getTargetType()))
                .map(ManagementTaskItem::getTargetId).distinct().toList();
        List<Long> mediaIds = firstItems.values().stream()
                .filter(item -> TARGET_TYPE_MEDIA.equals(item.getTargetType()))
                .map(ManagementTaskItem::getTargetId).distinct().toList();
        if (!chapterIds.isEmpty()) {
            chapterMapper.selectBatchIds(chapterIds)
                    .forEach(chapter -> chapterComicIds.put(chapter.getId(), chapter.getComicId()));
        }
        if (!mediaIds.isEmpty()) {
            mediaMapper.selectBatchIds(mediaIds)
                    .forEach(media -> mediaChapterIds.put(media.getId(), media.getChapterId()));
        }
        List<Long> mediaChapterIdList = mediaChapterIds.values().stream()
                .filter(Objects::nonNull).distinct().toList();
        if (!mediaChapterIdList.isEmpty()) {
            chapterMapper.selectBatchIds(mediaChapterIdList)
                    .forEach(chapter -> chapterComicIds.putIfAbsent(chapter.getId(), chapter.getComicId()));
        }
        firstItems.values().forEach(item -> {
            Long comicId = switch (item.getTargetType()) {
                case TARGET_TYPE_COMIC -> item.getTargetId();
                case TARGET_TYPE_CHAPTER -> chapterComicIds.get(item.getTargetId());
                case TARGET_TYPE_MEDIA -> chapterComicIds.get(mediaChapterIds.get(item.getTargetId()));
                default -> null;
            };
            parentComicIds.put(item.getTaskId(), comicId);
        });
        return parentComicIds;
    }
}
