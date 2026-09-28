package com.comicatlas.api.upload.service.impl;

import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.api.upload.domain.UploadSessionStatus;
import com.comicatlas.api.upload.persistence.entity.UploadFile;
import com.comicatlas.api.upload.persistence.entity.UploadSession;
import com.comicatlas.api.upload.persistence.mapper.UploadFileMapper;
import com.comicatlas.api.upload.persistence.mapper.UploadSessionMapper;
import com.comicatlas.api.upload.service.UploadSessionService;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.contract.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadCompletionServiceImplTest {

    @Mock
    private MediaMapper mediaMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private UploadFileMapper uploadFileMapper;
    @Mock
    private UploadSessionMapper uploadSessionMapper;
    @Mock
    private UploadSessionService uploadSessionService;
    @Mock
    private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock
    private ComicStatsService comicStatsService;
    @InjectMocks
    private UploadCompletionServiceImpl uploadCompletionService;

    @Test
    void rejectsCompletionWhenMediaRecordWasNotUpdated() {
        long uploadSessionId = 19L;
        long mediaId = 42L;
        UploadSession uploadSession = new UploadSession();
        uploadSession.setId(uploadSessionId);
        uploadSession.setChapterId(7L);
        uploadSession.setStatus(UploadSessionStatus.COMPLETED);

        UploadFile uploadFile = new UploadFile();
        uploadFile.setMediaId(mediaId);

        when(uploadSessionMapper.selectById(uploadSessionId)).thenReturn(uploadSession);
        when(uploadFileMapper.selectBySessionId(uploadSessionId)).thenReturn(List.of(uploadFile));
        when(mediaMapper.applyUploadCompleted(any(), eq(false))).thenReturn(0);

        MediaUploadCompletedEvent event = new MediaUploadCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1, 5L, 6L, 1,
                "MEDIA_UPLOAD", "UPLOAD_SESSION", uploadSessionId,
                List.of(new MediaAnalysisResult(mediaId, "IMAGE", 100, 200,
                        null, null, null, null, 1024L, "HQ", "1/7/image.jpg")));

        assertThatThrownBy(() -> uploadCompletionService.applyUploadCompletedBusiness(event))
                .isInstanceOf(BusinessException.class);
        verify(comicStatsService, never()).refreshByChapter(any());
        verify(uploadSessionService, never()).cleanupSessionAfterProcessed(uploadSessionId);
    }
}
