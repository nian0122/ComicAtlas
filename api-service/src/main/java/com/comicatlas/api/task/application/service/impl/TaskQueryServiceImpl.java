package com.comicatlas.api.task.application.service.impl;

import com.comicatlas.api.shared.application.model.PageResult;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.task.application.port.in.TaskQueryService;
import com.comicatlas.api.task.application.assembler.TaskResponseAssembler;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort;
import com.comicatlas.api.task.application.port.out.TaskViewQueryPort;
import com.comicatlas.api.task.application.port.out.TaskViewQueryPort.ItemSnapshot;
import com.comicatlas.api.task.application.port.out.TaskViewQueryPort.TaskSnapshot;
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
public class TaskQueryServiceImpl implements TaskQueryService {
    // 任务查询契约由应用服务公开，具体实现保持在任务业务包内。

    private static final String TARGET_TYPE_COMIC = "COMIC";
    private static final String TARGET_TYPE_CHAPTER = "CHAPTER";
    private static final String TARGET_TYPE_MEDIA = "MEDIA";

    private final TaskQueryPersistencePort taskCommandPort;
    private final TaskViewQueryPort persistencePort;
    private final TaskResponseAssembler taskResponseAssembler;

    /** 分页查询管理任务。 */
    public PageResult<ManagementTaskResponse> listTasks(int page, int size, TaskType type,
                                                    ManagementTaskStatus status, String batchId,
                                                    String targetType, Long targetId) {
        List<Long> targetTaskIds = null;
        if (targetId != null) {
            targetTaskIds = taskCommandPort.findTaskIdsByComicId(targetId);
            if (targetTaskIds.isEmpty()) {
                return emptyPage(page, size);
            }
        }
        PageResult<TaskSnapshot> taskPage = persistencePort.findTaskPage(page, size,
                type == null ? null : type.name(), status == null ? null : status.name(), batchId,
                targetType, targetTaskIds);
        List<ManagementTaskResponse> responses = taskPage.records().stream()
                .map(taskResponseAssembler::toResponse)
                .collect(Collectors.toList());
        enrichTargetSummaries(taskPage.records(), responses);
        return new PageResult<>(taskPage.current(), taskPage.size(), taskPage.total(), responses);
    }

    /** 查询任务详情。 */
    public ManagementTaskResponse getTask(Long taskId) {
        TaskSnapshot task = requireTask(taskId);
        ManagementTaskResponse response = taskResponseAssembler.toResponse(task);
        enrichTargetSummaries(List.of(task), List.of(response));
        return response;
    }

    /** 查询任务项。 */
    public List<ManagementTaskItemResponse> getTaskItems(Long taskId) {
        requireTask(taskId);
        return persistencePort.findTaskItemsById(taskId)
                .stream()
                .map(taskResponseAssembler::toItemResponse)
                .collect(Collectors.toList());
    }

    private TaskSnapshot requireTask(Long taskId) {
        TaskSnapshot task = persistencePort.findTaskView(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }
        return task;
    }

    private PageResult<ManagementTaskResponse> emptyPage(int page, int size) {
        return new PageResult<>(page, size, 0, List.of());
    }

    private void enrichTargetSummaries(List<TaskSnapshot> tasks,
                                       List<ManagementTaskResponse> responses) {
        if (tasks.isEmpty()) {
            return;
        }
        Map<Long, ManagementTaskResponse> responseByTaskId = new HashMap<>();
        responses.forEach(response -> responseByTaskId.put(response.getId(), response));
        List<ItemSnapshot> items = persistencePort.findTaskItemsByIds(
                tasks.stream().map(TaskSnapshot::id).toList());
        Map<Long, ItemSnapshot> firstItems = new HashMap<>();
        items.forEach(item -> firstItems.putIfAbsent(item.taskId(), item));
        Map<Long, Long> parentComicIds = resolveParentComicIds(firstItems);
        Map<Long, TaskViewQueryPort.ComicSnapshot> comics = new HashMap<>();
        List<Long> comicIds = parentComicIds.values().stream().filter(Objects::nonNull).distinct().toList();
        if (!comicIds.isEmpty()) {
            persistencePort.findComicViewsByIds(comicIds).forEach(comic -> comics.put(comic.id(), comic));
        }
        firstItems.values().forEach(item -> {
            ManagementTaskResponse response = responseByTaskId.get(item.taskId());
            if (response == null) {
                return;
            }
            Long parentComicId = parentComicIds.get(item.taskId());
            TaskViewQueryPort.ComicSnapshot comic = parentComicId == null
                    ? null : comics.get(parentComicId);
            response.setTargetId(TARGET_TYPE_COMIC.equals(response.getTargetType())
                    && parentComicId != null ? parentComicId : item.targetId());
            response.setTargetName(comic == null ? null : comic.title());
        });
    }

    private Map<Long, Long> resolveParentComicIds(Map<Long, ItemSnapshot> firstItems) {
        Map<Long, Long> parentComicIds = new HashMap<>();
        Map<Long, Long> chapterComicIds = new HashMap<>();
        Map<Long, Long> mediaChapterIds = new HashMap<>();
        List<Long> chapterIds = firstItems.values().stream()
                .filter(item -> TARGET_TYPE_CHAPTER.equals(item.targetType()))
                .map(ItemSnapshot::targetId).distinct().toList();
        List<Long> mediaIds = firstItems.values().stream()
                .filter(item -> TARGET_TYPE_MEDIA.equals(item.targetType()))
                .map(ItemSnapshot::targetId).distinct().toList();
        if (!chapterIds.isEmpty()) {
            persistencePort.findChapterViewsByIds(chapterIds)
                    .forEach(chapter -> chapterComicIds.put(chapter.id(), chapter.comicId()));
        }
        if (!mediaIds.isEmpty()) {
            persistencePort.findMediaViewsByIds(mediaIds)
                    .forEach(media -> mediaChapterIds.put(media.id(), media.chapterId()));
        }
        List<Long> mediaChapterIdList = mediaChapterIds.values().stream()
                .filter(Objects::nonNull).distinct().toList();
        if (!mediaChapterIdList.isEmpty()) {
            persistencePort.findChapterViewsByIds(mediaChapterIdList)
                    .forEach(chapter -> chapterComicIds.putIfAbsent(chapter.id(), chapter.comicId()));
        }
        firstItems.values().forEach(item -> {
            Long comicId = switch (item.targetType()) {
                case TARGET_TYPE_COMIC -> item.targetId();
                case TARGET_TYPE_CHAPTER -> chapterComicIds.get(item.targetId());
                case TARGET_TYPE_MEDIA -> chapterComicIds.get(mediaChapterIds.get(item.targetId()));
                default -> null;
            };
            parentComicIds.put(item.taskId(), comicId);
        });
        return parentComicIds;
    }
}
