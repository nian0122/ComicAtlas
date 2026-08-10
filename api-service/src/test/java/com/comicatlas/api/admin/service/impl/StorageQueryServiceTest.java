package com.comicatlas.api.admin.service.impl;

import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import com.comicatlas.api.admin.dto.ComicTranscodeStatusVO;
import com.comicatlas.api.admin.mapper.StorageMapper;
import com.comicatlas.api.common.storage.FileUrlResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorageQueryServiceTest {

    @Mock
    private StorageMapper storageMapper;
    @Mock
    private FileUrlResolver fileUrlResolver;

    @InjectMocks
    private StorageQueryServiceImpl service;

    @Test
    void listComics_shouldBatchLoadTranscodeStatus_notPerRow() {
        ComicStorageDTO dto1 = comicDto(1L, "READY", "NOT_GENERATED");
        ComicStorageDTO dto2 = comicDto(2L, "READY", "NOT_GENERATED");
        when(storageMapper.selectComicStorageList(any(), anyInt(), anyInt()))
                .thenReturn(List.of(dto1, dto2));
        when(storageMapper.selectTranscodeStatusList(List.of(1L, 2L)))
                .thenReturn(List.of(
                        new ComicTranscodeStatusVO(1L, "QUEUED"),
                        new ComicTranscodeStatusVO(2L, "READY")));

        List<ComicStorageDTO> result = service.listComics(new ComicStorageQuery(), 1, 20);

        assertEquals(2, result.size());
        assertEquals("QUEUED", result.get(0).getTranscodeStatus());
        assertEquals("READY", result.get(1).getTranscodeStatus());

        verify(storageMapper).selectTranscodeStatusList(List.of(1L, 2L));
        verify(storageMapper, never()).selectTranscodeStatus(any());
    }

    @Test
    void listComics_shouldSkipTranscodeBatch_whenNoRows() {
        when(storageMapper.selectComicStorageList(any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        List<ComicStorageDTO> result = service.listComics(new ComicStorageQuery(), 1, 20);

        assertEquals(0, result.size());
        verify(storageMapper, never()).selectTranscodeStatusList(any());
    }

    @Test
    void listComics_shouldHandleMissingTranscodeStatus() {
        ComicStorageDTO dto1 = comicDto(1L, "READY", "NOT_GENERATED");
        when(storageMapper.selectComicStorageList(any(), anyInt(), anyInt()))
                .thenReturn(List.of(dto1));
        when(storageMapper.selectTranscodeStatusList(List.of(1L))).thenReturn(List.of());

        List<ComicStorageDTO> result = service.listComics(new ComicStorageQuery(), 1, 20);

        assertEquals("NOT_NEEDED", result.get(0).getTranscodeStatus());
        verify(storageMapper, times(1)).selectTranscodeStatusList(List.of(1L));
    }

    /** 聚合转码状态按 TRANSCODING > QUEUED > FAILED > REQUIRED > READY > NOT_NEEDED 优先级取值。 */
    @ParameterizedTest
    @CsvSource({
            "'READY,QUEUED', QUEUED",
            "'TRANSCODING,FAILED', TRANSCODING",
            "'FAILED,REQUIRED', FAILED",
            "'REQUIRED,READY', REQUIRED",
            "'NOT_NEEDED,READY', READY",
            "'QUEUED,NOT_NEEDED', QUEUED",
            "'NOT_NEEDED', NOT_NEEDED",
            "'PENDING', NOT_NEEDED",
            "'DONE', NOT_NEEDED",
    })
    void listComics_shouldAggregateTranscodeByPriority(String statuses, String expected) {
        ComicStorageDTO dto = comicDto(1L, "READY", "NOT_GENERATED");
        when(storageMapper.selectComicStorageList(any(), anyInt(), anyInt())).thenReturn(List.of(dto));
        when(storageMapper.selectTranscodeStatusList(List.of(1L)))
                .thenReturn(List.of(new ComicTranscodeStatusVO(1L, statuses)));

        List<ComicStorageDTO> result = service.listComics(new ComicStorageQuery(), 1, 20);

        assertEquals(expected, result.get(0).getTranscodeStatus());
    }

    @Test
    void getComic_shouldSetAggregatedTranscodeStatus() {
        ComicStorageDTO dto = comicDto(1L, "READY", "NOT_GENERATED");
        when(storageMapper.selectComicStorageById(1L)).thenReturn(dto);
        when(storageMapper.selectTranscodeStatus(1L)).thenReturn("TRANSCODING,FAILED");

        ComicStorageDTO result = service.getComic(1L);

        assertEquals("TRANSCODING", result.getTranscodeStatus());
    }

    @Test
    void getComic_shouldDefaultTranscodeStatus_whenNoVideos() {
        ComicStorageDTO dto = comicDto(1L, "READY", "NOT_GENERATED");
        when(storageMapper.selectComicStorageById(1L)).thenReturn(dto);
        when(storageMapper.selectTranscodeStatus(1L)).thenReturn(null);

        ComicStorageDTO result = service.getComic(1L);

        assertEquals("NOT_NEEDED", result.getTranscodeStatus());
    }

    /** LQ 聚合：GENERATING/QUEUED 活跃状态优先于失败与静止状态；其余混合保持 MIXED。 */
    @ParameterizedTest
    @CsvSource({
            "'GENERATING,FAILED', GENERATING",
            "'QUEUED,READY', QUEUED",
            "'GENERATING,NOT_GENERATED', GENERATING",
            "'QUEUED,FAILED', QUEUED",
            "'FAILED,READY', MIXED",
            "'READY,NOT_GENERATED', MIXED",
            "'READY', READY",
    })
    void listComics_shouldPrioritizeActiveLqStatus(String lqStatuses, String expected) {
        ComicStorageDTO dto = comicDto(1L, "READY", lqStatuses);
        when(storageMapper.selectComicStorageList(any(), anyInt(), anyInt())).thenReturn(List.of(dto));
        when(storageMapper.selectTranscodeStatusList(List.of(1L))).thenReturn(List.of());

        List<ComicStorageDTO> result = service.listComics(new ComicStorageQuery(), 1, 20);

        assertEquals(expected, result.get(0).getLqStatus());
    }

    private static ComicStorageDTO comicDto(Long id, String hqStatus, String lqStatus) {
        ComicStorageDTO dto = new ComicStorageDTO();
        dto.setComicId(id);
        dto.setHqStatus(hqStatus);
        dto.setLqStatus(lqStatus);
        dto.setPageCount(10);
        return dto;
    }
}
