package com.comicatlas.api.admin.service.impl;

import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import com.comicatlas.api.admin.dto.ComicTranscodeStatus;
import com.comicatlas.api.admin.mapper.StorageMapper;
import com.comicatlas.api.common.storage.FileUrlResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
                        new ComicTranscodeStatus(1L, "PENDING"),
                        new ComicTranscodeStatus(2L, "DONE")));

        List<ComicStorageDTO> result = service.listComics(new ComicStorageQuery(), 1, 20);

        assertEquals(2, result.size());
        assertEquals("PENDING", result.get(0).getTranscodeStatus());
        assertEquals("DONE", result.get(1).getTranscodeStatus());

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

    private static ComicStorageDTO comicDto(Long id, String hqStatus, String lqStatus) {
        ComicStorageDTO dto = new ComicStorageDTO();
        dto.setComicId(id);
        dto.setHqStatus(hqStatus);
        dto.setLqStatus(lqStatus);
        dto.setPageCount(10);
        return dto;
    }
}
