package com.comicatlas.api.importer.event;

import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportEventHandlerCacheTest {

    @Mock private ObjectMapper objectMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private ComicMapper comicMapper;
    @Mock private CatalogMapper catalogMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private MediaMapper mediaMapper;
    @Mock private ImportTaskMapper taskMapper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock private ManagementTaskService managementTaskService;
    @Mock private Channel channel;
    @InjectMocks private ImportEventHandler handler;

    @Test
    void handleComicImported_shouldEvictCatalogCache_whenImportCompletes() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(10L);
        task.setStatus("PROCESSING");
        Comic comic = new Comic();
        comic.setId(20L);
        Map<String, Object> metadata = Map.of(
                "comic", Map.of(),
                "catalogs", List.of(),
                "chapters", List.of());
        ImportTaskCompletedEvent event = new ImportTaskCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 20L, "metadata/10.json");

        when(redisTemplate.hasKey(any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(taskMapper.selectById(10L)).thenReturn(task);
        when(comicMapper.selectById(20L)).thenReturn(comic);
        doReturn(metadata).when(objectMapper).readValue(any(File.class), any(TypeReference.class));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(managementTaskService.findActiveItem(any(), any(), any())).thenReturn(null);

        handler.handleComicImported(event, channel, 1L);

        verify(catalogCacheInvalidator).evict(20L);
        verify(channel).basicAck(1L, false);
    }
}
