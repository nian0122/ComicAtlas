package com.comicatlas.api.storage.application.service;

import com.comicatlas.api.storage.application.service.impl.StorageQueryServiceImpl;

import com.comicatlas.api.storage.interfaces.rest.dto.ComicStorageDTO;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicStorageQuery;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicTranscodeStatusVO;
import com.comicatlas.api.storage.infrastructure.persistence.mapper.StorageMapper;
import com.comicatlas.api.storage.application.port.out.StorageQueryPersistencePort;
import com.comicatlas.api.shared.application.port.out.FileUrlResolverPort;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import com.comicatlas.api.storage.infrastructure.adapter.StorageCapacityAdapter;
import com.comicatlas.persistence.storage.FileUrlResolver;
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
    private StorageQueryPersistencePort persistencePort;
    @Mock
    private FileUrlResolverPort fileUrlResolver;
    @Mock
    private ApiStorageProperties storageProperties;
    @Mock
    private StorageCapacityAdapter storageCapacityAdapter;

    @InjectMocks
    private StorageQueryServiceImpl service;

    @Test
    void listComics_shouldBatchLoadTranscodeStatus_notPerRow() {
        ComicStorageDTO dto1 = comicDto(1L, "READY", "NOT_GENERATED");
        ComicStorageDTO dto2 = comicDto(2L, "READY", "NOT_GENERATED");
        when(persistencePort.findComics(any(), anyInt(), anyInt()))
                .thenReturn(List.of(dto1, dto2));
        when(persistencePort.findTranscodeStatuses(List.of(1L, 2L)))
                .thenReturn(List.of(
                        new ComicTranscodeStatusVO(1L, "PENDING"),
                        new ComicTranscodeStatusVO(2L, "DONE")));

        List<ComicStorageDTO> result = service.listComics(new ComicStorageQuery(), 1, 20);

        assertEquals(2, result.size());
        assertEquals("PENDING", result.get(0).getTranscodeStatus());
        assertEquals("DONE", result.get(1).getTranscodeStatus());

        verify(persistencePort).findTranscodeStatuses(List.of(1L, 2L));
    }

    @Test
    void listComics_shouldSkipTranscodeBatch_whenNoRows() {
        when(persistencePort.findComics(any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        List<ComicStorageDTO> result = service.listComics(new ComicStorageQuery(), 1, 20);

        assertEquals(0, result.size());
        verify(persistencePort, never()).findTranscodeStatuses(any());
    }

    @Test
    void listComics_shouldHandleMissingTranscodeStatus() {
        ComicStorageDTO dto1 = comicDto(1L, "READY", "NOT_GENERATED");
        when(persistencePort.findComics(any(), anyInt(), anyInt()))
                .thenReturn(List.of(dto1));
        when(persistencePort.findTranscodeStatuses(List.of(1L))).thenReturn(List.of());

        List<ComicStorageDTO> result = service.listComics(new ComicStorageQuery(), 1, 20);

        assertEquals("NOT_NEEDED", result.get(0).getTranscodeStatus());
        verify(persistencePort, times(1)).findTranscodeStatuses(List.of(1L));
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
