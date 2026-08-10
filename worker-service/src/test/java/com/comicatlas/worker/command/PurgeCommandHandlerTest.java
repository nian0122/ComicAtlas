package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.worker.file.trash.TrashManifestStore;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PurgeCommandHandler 单元测试：永久清理只删除本目标 TRASH 目录
 * （TRASH/{targetType}/{targetId}），不误删其他目标；目录不存在仍视为成功。
 */
@ExtendWith(MockitoExtension.class)
class PurgeCommandHandlerTest {

    @TempDir
    Path tempRoot;

    @Mock
    private ManagementCommandPublisher publisher;

    private StorageProperties storageProperties;
    private TrashManifestStore manifestStore;
    private PurgeCommandHandler handler;
    private ObjectMapper objectMapper;

    private static final long TASK_ID = 100L;
    private static final long ITEM_ID = 200L;
    private static final int ATTEMPT = 1;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        storageProperties = new StorageProperties();
        Map<String, StorageRoot> roots = new HashMap<>();
        roots.put("TRASH", rootOf(Files.createDirectories(tempRoot.resolve("trash"))));
        storageProperties.setRoots(roots);
        manifestStore = new TrashManifestStore(storageProperties, objectMapper);
        handler = new PurgeCommandHandler(manifestStore, publisher);
    }

    private static StorageRoot rootOf(Path path) {
        StorageRoot root = new StorageRoot();
        root.setPath(path);
        return root;
    }

    private ManagementCommandRequestedEvent chapterCmd(Long chapterId, Long manifestTaskId) {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, TASK_ID, ITEM_ID, ATTEMPT,
                "CHAPTER_PURGE", "CHAPTER", chapterId, manifestTaskId);
    }

    private void writeTrashFile(String targetType, Long targetId, Long taskId, String relative, String content)
            throws Exception {
        Path file = tempRoot.resolve("trash").resolve(targetType + "/" + targetId + "/" + taskId + "/" + relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("purge：只删除本目标 TRASH 目录，不误删其他目标目录")
    void purge_deletesOnlyOwnTargetDir_keepsOthers() throws Exception {
        writeTrashFile("CHAPTER", 1L, 10L, "hq/1/100/001.jpg", "a");
        writeTrashFile("CHAPTER", 2L, 11L, "hq/1/200/001.jpg", "b");
        ManagementCommandRequestedEvent cmd = chapterCmd(1L, 10L);

        handler.purge(cmd);

        assertThat(Files.exists(tempRoot.resolve("trash/CHAPTER/1"))).isFalse();
        assertThat(Files.exists(tempRoot.resolve("trash/CHAPTER/2"))).isTrue();
        assertThat(Files.exists(tempRoot.resolve("trash/CHAPTER/2/11/hq/1/200/001.jpg"))).isTrue();
        verify(publisher).completed(cmd);
        verify(publisher, never()).failed(any(ManagementCommandRequestedEvent.class), anyString());
    }

    @Test
    @DisplayName("purge：目录不存在仍视为成功（幂等）")
    void purge_missingDir_stillSucceeds() throws Exception {
        ManagementCommandRequestedEvent cmd = chapterCmd(999L, 10L);

        handler.purge(cmd);

        verify(publisher).completed(cmd);
        verify(publisher, never()).failed(any(ManagementCommandRequestedEvent.class), anyString());
    }
}
