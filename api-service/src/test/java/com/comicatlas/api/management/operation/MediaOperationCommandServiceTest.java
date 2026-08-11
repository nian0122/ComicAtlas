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
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashLifecycleService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TranscodeStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体操作命令编排服务单元测试（章节级转码）。
 */
@ExtendWith(MockitoExtension.class)
class MediaOperationCommandServiceTest {

    @Mock private ChapterMapper chapterMapper;
    @Mock private MediaMapper mediaMapper;
    @Mock private ComicMapper comicMapper;
    @Mock private ManagementTaskService managementTaskService;
    @Mock private OutboxService outboxService;
    @Mock private TrashLifecycleService trashLifecycleService;
    @InjectMocks private MediaOperationCommandService service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // 单元测试无 Spring 上下文，需注册 Media 的 TableInfo 以支持 LambdaQueryWrapper 解析
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Media.class);
    }

    @Test
    void requestTranscodeForChapter_仅选中章节下待转码视频() {
        Chapter chapter = new Chapter();
        chapter.setId(9L);
        when(chapterMapper.selectById(9L)).thenReturn(chapter);

        // 待转码：container=null（需要转码）；兼容：container=mp4（无需转码）
        Media needTranscode = video(11L, 9L, null, TranscodeStatus.NOT_NEEDED);
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

        OperationSubmitResultDTO result = service.requestTranscodeForChapter(9L);

        assertEquals(100L, result.getTaskId());
        assertEquals("TRANSCODE", result.getTaskType());
        assertEquals(1, result.getItemCount());

        // 仅 1 个 MEDIA target：mp4 兼容视频不进入转码目标
        verify(managementTaskService).createTask(any(), any(), any());
        // markTranscodeQueued 仅对 11L 生效一次
        verify(mediaMapper, times(1)).update(any(), any());
        // enqueue 仅一次
        verify(outboxService, times(1)).enqueue(any(), any(), any(), any(), any(), anyInt());
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
    void requestTranscodeForChapter_状态REQUIRED的视频可被转码() {
        Chapter chapter = new Chapter();
        chapter.setId(9L);
        when(chapterMapper.selectById(9L)).thenReturn(chapter);

        // 回归：V18 迁移把不兼容视频标记为 REQUIRED，此前枚举缺失导致 NPE
        Media required = video(11L, 9L, "mkv", TranscodeStatus.REQUIRED);
        when(mediaMapper.selectList(any())).thenReturn(List.of(required));

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

        OperationSubmitResultDTO result = service.requestTranscodeForChapter(9L);

        assertEquals(100L, result.getTaskId());
        assertEquals(1, result.getItemCount());
        verify(outboxService, times(1)).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void requestTranscodeForChapter_未知状态映射为null时不抛NPE() {
        Chapter chapter = new Chapter();
        chapter.setId(9L);
        when(chapterMapper.selectById(9L)).thenReturn(chapter);

        // 回归：EnumTypeHandlers.safeValueOf 对未知枚举值返回 null，此前 Set.contains(null) 抛 NPE
        Media unknown = video(11L, 9L, "mkv", null);
        when(mediaMapper.selectList(any())).thenReturn(List.of(unknown));

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

        OperationSubmitResultDTO result = service.requestTranscodeForChapter(9L);

        assertEquals(100L, result.getTaskId());
        assertEquals(1, result.getItemCount());
        verify(outboxService, times(1)).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void requestTranscodeForChapter_mp4容器mpeg4编码需要转码() {
        Chapter chapter = new Chapter();
        chapter.setId(9L);
        when(chapterMapper.selectById(9L)).thenReturn(chapter);

        // 回归：mp4 容器 + mpeg4(MPEG-4 Part 2) 编码浏览器无法解码（只出声不出画），
        // 此前 isTranscodeEligible 只看容器名导致 mpeg4-in-mp4 被误判"无需转码"
        Media mpeg4InMp4 = video(11L, 9L, "mp4", TranscodeStatus.NOT_NEEDED);
        mpeg4InMp4.setVideoCodec("mpeg4");
        when(mediaMapper.selectList(any())).thenReturn(List.of(mpeg4InMp4));

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

        OperationSubmitResultDTO result = service.requestTranscodeForChapter(9L);

        assertEquals(100L, result.getTaskId());
        assertEquals(1, result.getItemCount());
        verify(outboxService, times(1)).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void requestTranscodeForChapter_mp4容器h264编码无需转码() {
        Chapter chapter = new Chapter();
        chapter.setId(9L);
        when(chapterMapper.selectById(9L)).thenReturn(chapter);

        // 浏览器可直接播放的 mp4+h264 不应进入转码目标
        Media h264InMp4 = video(12L, 9L, "mp4", TranscodeStatus.NOT_NEEDED);
        h264InMp4.setVideoCodec("h264");
        when(mediaMapper.selectList(any())).thenReturn(List.of(h264InMp4));

        OperationSubmitResultDTO result = service.requestTranscodeForChapter(9L);

        assertNull(result.getTaskId());
        assertEquals(0, result.getItemCount());
        verify(managementTaskService, never()).createTask(any(), any(), any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    private static Media video(Long id, Long chapterId, String container, TranscodeStatus status) {
        Media media = new Media();
        media.setId(id);
        media.setChapterId(chapterId);
        media.setMediaType("VIDEO");
        media.setHqStatus(HqStatus.READY);
        media.setContainer(container);
        media.setTranscodeStatus(status);
        return media;
    }
}
