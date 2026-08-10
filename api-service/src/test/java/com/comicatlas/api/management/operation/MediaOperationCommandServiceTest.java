package com.comicatlas.api.management.operation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashLifecycleService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.common.enums.TranscodeStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体操作命令编排服务单元测试（转码资格 + CAS 冲突语义）。
 * <p>
 * 使用真实 {@link TranscodeMediaSelector}（mapper 为 mock）：资格判定与
 * CAS（mediaId + REQUIRED|FAILED → QUEUED，0 行 → 409）走真实逻辑。
 */
@ExtendWith(MockitoExtension.class)
class MediaOperationCommandServiceTest {

    @Mock private ChapterMapper chapterMapper;
    @Mock private MediaMapper mediaMapper;
    @Mock private ComicMapper comicMapper;
    @Mock private ManagementTaskService managementTaskService;
    @Mock private OutboxService outboxService;
    @Mock private TrashLifecycleService trashLifecycleService;

    private MediaOperationCommandService service;
    private TranscodeMediaSelector selector;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // 单元测试无 Spring 上下文，需注册 Media/Chapter 的 TableInfo 以支持 LambdaQueryWrapper 解析
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Media.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Chapter.class);
    }

    @BeforeEach
    void setUp() {
        selector = new TranscodeMediaSelector(chapterMapper, mediaMapper);
        service = new MediaOperationCommandService(
                chapterMapper, mediaMapper, comicMapper,
                managementTaskService, outboxService, trashLifecycleService, selector);
    }

    @Test
    void requestTranscodeForChapter_仅选中章节下待转码视频() {
        Chapter chapter = new Chapter();
        chapter.setId(9L);
        when(chapterMapper.selectById(9L)).thenReturn(chapter);

        // REQUIRED 需转码；NOT_NEEDED（mp4 兼容）不入选
        Media needTranscode = video(11L, 9L, "avi", TranscodeStatus.REQUIRED);
        Media alreadyCompat = video(12L, 9L, "mp4", TranscodeStatus.NOT_NEEDED);
        when(mediaMapper.selectList(any())).thenReturn(List.of(needTranscode, alreadyCompat));

        ManagementTaskResponse task = new ManagementTaskResponse();
        task.setId(100L);
        task.setStatus(ManagementTaskStatus.QUEUED);
        when(managementTaskService.createTask(any(), any(), any())).thenReturn(task);

        ManagementTaskItemResponse item = new ManagementTaskItemResponse();
        item.setId(200L);
        item.setTaskId(100L);
        item.setTargetType("MEDIA");
        item.setTargetId(11L);
        item.setAttempt(1);
        when(managementTaskService.getTaskItems(100L)).thenReturn(List.of(item));

        when(mediaMapper.update(any(), any())).thenReturn(1);

        OperationSubmitResultDTO result = service.requestTranscodeForChapter(9L);

        assertEquals(100L, result.getTaskId());
        assertEquals("TRANSCODE", result.getTaskType());
        assertEquals(1, result.getItemCount());

        // 仅 1 个 MEDIA target：mp4 兼容视频不进入转码目标
        verify(managementTaskService).createTask(any(), any(), any());
        // CAS 置 QUEUED 仅对 11L 生效一次
        verify(mediaMapper, times(1)).update(any(), any());
        // enqueue 仅一次（MEDIA 目标）
        verify(outboxService, times(1)).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void requestTranscodeForChapter_状态非法不入选_NOTAUTOMATIC() {
        Chapter chapter = new Chapter();
        chapter.setId(9L);
        when(chapterMapper.selectById(9L)).thenReturn(chapter);

        // 图片、HQ DELETED、QUEUED/TRANSCODING/READY 均不入选
        Media image = image(20L, 9L);
        Media hqDeleted = video(21L, 9L, "avi", TranscodeStatus.REQUIRED);
        hqDeleted.setHqStatus(HqStatus.DELETED);
        Media queued = video(22L, 9L, "avi", TranscodeStatus.QUEUED);
        Media transcoding = video(23L, 9L, "avi", TranscodeStatus.TRANSCODING);
        Media ready = video(24L, 9L, "mp4", TranscodeStatus.READY);
        when(mediaMapper.selectList(any())).thenReturn(List.of(image, hqDeleted, queued, transcoding, ready));

        OperationSubmitResultDTO result = service.requestTranscodeForChapter(9L);

        assertNull(result.getTaskId());
        assertEquals(0, result.getItemCount());
        verify(managementTaskService, never()).createTask(any(), any(), any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void requestTranscodeForChapter_无待转码时返回空任务() {
        Chapter chapter = new Chapter();
        chapter.setId(9L);
        when(chapterMapper.selectById(9L)).thenReturn(chapter);

        // 全部已就绪（READY 的 mp4 视频）
        Media alreadyReady = video(12L, 9L, "mp4", TranscodeStatus.READY);
        when(mediaMapper.selectList(any())).thenReturn(List.of(alreadyReady));

        OperationSubmitResultDTO result = service.requestTranscodeForChapter(9L);

        assertNull(result.getTaskId());
        assertEquals(0, result.getItemCount());
        verify(managementTaskService, never()).createTask(any(), any(), any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void requestTranscodeForChapter_章节不存在抛出404() {
        when(chapterMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requestTranscodeForChapter(9L));

        assertEquals(HttpStatusCodes.NOT_FOUND, ex.getCode());
    }

    @Test
    void requestTranscodeForMedia_单个MEDIA_仅REQUIRED或FAILED() {
        // FAILED 允许重试转码
        Media failed = video(31L, 9L, "avi", TranscodeStatus.FAILED);
        when(mediaMapper.selectById(31L)).thenReturn(failed);

        ManagementTaskResponse task = new ManagementTaskResponse();
        task.setId(300L);
        task.setStatus(ManagementTaskStatus.QUEUED);
        when(managementTaskService.createTask(any(), any(), any())).thenReturn(task);

        ManagementTaskItemResponse item = new ManagementTaskItemResponse();
        item.setId(400L);
        item.setTaskId(300L);
        item.setTargetType("MEDIA");
        item.setTargetId(31L);
        item.setAttempt(1);
        when(managementTaskService.getTaskItems(300L)).thenReturn(List.of(item));

        when(mediaMapper.update(any(), any())).thenReturn(1);

        OperationSubmitResultDTO result = service.requestTranscodeForMedia(31L);

        assertEquals(300L, result.getTaskId());
        assertEquals(1, result.getItemCount());
        verify(managementTaskService).createTask(any(), any(), any());
        verify(mediaMapper, times(1)).update(any(), any());
    }

    @Test
    void requestTranscodeForMedia_NOT_NEEDED跳过() {
        Media notNeeded = video(41L, 9L, "mp4", TranscodeStatus.NOT_NEEDED);
        when(mediaMapper.selectById(41L)).thenReturn(notNeeded);

        OperationSubmitResultDTO result = service.requestTranscodeForMedia(41L);

        assertNull(result.getTaskId());
        assertEquals(0, result.getItemCount());
        verify(managementTaskService, never()).createTask(any(), any(), any());
    }

    @Test
    void requestTranscodeForComic_展开为逐视频MEDIAItem() {
        Chapter ch1 = new Chapter();
        ch1.setId(9L);
        ch1.setComicId(100L);
        Chapter ch2 = new Chapter();
        ch2.setId(10L);
        ch2.setComicId(100L);
        when(chapterMapper.selectList(any())).thenReturn(List.of(ch1, ch2));

        Media v1 = video(11L, 9L, "avi", TranscodeStatus.REQUIRED);
        Media v2 = video(12L, 10L, "avi", TranscodeStatus.REQUIRED);
        when(mediaMapper.selectList(any())).thenReturn(List.of(v1, v2));

        ManagementTaskResponse task = new ManagementTaskResponse();
        task.setId(500L);
        task.setStatus(ManagementTaskStatus.QUEUED);
        when(managementTaskService.createTask(any(), any(), any())).thenReturn(task);

        ManagementTaskItemResponse i1 = new ManagementTaskItemResponse();
        i1.setId(600L);
        i1.setTaskId(500L);
        i1.setTargetType("MEDIA");
        i1.setTargetId(11L);
        i1.setAttempt(1);
        ManagementTaskItemResponse i2 = new ManagementTaskItemResponse();
        i2.setId(601L);
        i2.setTaskId(500L);
        i2.setTargetType("MEDIA");
        i2.setTargetId(12L);
        i2.setAttempt(1);
        when(managementTaskService.getTaskItems(500L)).thenReturn(List.of(i1, i2));

        when(mediaMapper.update(any(), any())).thenReturn(1);

        OperationSubmitResultDTO result = service.requestTranscodeForComic(100L);

        assertEquals(500L, result.getTaskId());
        assertEquals(2, result.getItemCount());

        // 任务 targets 全部为 MEDIA（不允许 COMIC/CHAPTER 聚合转码 item）
        ArgumentCaptor<CreateManagementTaskRequest> reqCaptor =
                ArgumentCaptor.forClass(CreateManagementTaskRequest.class);
        verify(managementTaskService).createTask(reqCaptor.capture(), any(), any());
        List<CreateManagementTaskRequest.TaskTarget> targets = reqCaptor.getValue().getTargets();
        assertEquals(2, targets.size());
        assertTrue(targets.stream().allMatch(t ->
                "MEDIA".equals(t.getTargetType())
                        && t.getOperationType() == TaskType.TRANSCODE));

        verify(mediaMapper, times(2)).update(any(), any());
        verify(outboxService, times(2)).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void markTranscodeQueued_CAS影响0行_抛409且不产生孤儿任务() {
        // 并发竞争场景：两个请求同时提交，第二个 CAS 0 行 → 409
        when(mediaMapper.selectById(11L)).thenReturn(video(11L, 9L, "avi", TranscodeStatus.REQUIRED));

        ManagementTaskResponse task = new ManagementTaskResponse();
        task.setId(700L);
        task.setStatus(ManagementTaskStatus.QUEUED);
        when(managementTaskService.createTask(any(), any(), any())).thenReturn(task);

        ManagementTaskItemResponse item = new ManagementTaskItemResponse();
        item.setId(800L);
        item.setTaskId(700L);
        item.setTargetType("MEDIA");
        item.setTargetId(11L);
        item.setAttempt(1);
        when(managementTaskService.getTaskItems(700L)).thenReturn(List.of(item));

        // CAS 0 行：状态已被并发方改为 QUEUED 等
        when(mediaMapper.update(any(), any())).thenReturn(0);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.requestTranscodeForMedia(11L));

        assertEquals(409, ex.getCode());
        // 冲突后不得产生 Outbox 命令（孤儿任务）
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void requestTranscodeForComic_无待转码视频返回空任务() {
        when(chapterMapper.selectList(any())).thenReturn(List.of(new Chapter()));

        OperationSubmitResultDTO result = service.requestTranscodeForComic(100L);

        assertNull(result.getTaskId());
        assertEquals(0, result.getItemCount());
        verify(managementTaskService, never()).createTask(any(), any(), any());
    }

    private static Media video(Long id, Long chapterId, String container, TranscodeStatus status) {
        Media media = new Media();
        media.setId(id);
        media.setChapterId(chapterId);
        media.setMediaType("VIDEO");
        media.setHqStatus(HqStatus.READY);
        media.setStatus(MediaLifecycleStatus.READY);
        media.setContainer(container);
        media.setTranscodeStatus(status);
        return media;
    }

    private static Media image(Long id, Long chapterId) {
        Media media = new Media();
        media.setId(id);
        media.setChapterId(chapterId);
        media.setMediaType("IMAGE");
        media.setHqStatus(HqStatus.READY);
        media.setStatus(MediaLifecycleStatus.READY);
        media.setTranscodeStatus(TranscodeStatus.NOT_NEEDED);
        return media;
    }
}
