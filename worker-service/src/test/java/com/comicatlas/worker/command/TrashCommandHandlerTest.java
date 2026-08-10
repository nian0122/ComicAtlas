package com.comicatlas.worker.command;

import com.comicatlas.common.dto.TrashManifestDTO;
import com.comicatlas.common.dto.TrashManifestItemDTO;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TrashCommandHandler 单元测试：严格按 manifest 逐文件同卷移动、绝不覆盖；
 * 缺失源逐条 MISSING、重复命令幂等、目标已存在时反向补偿回 COMPENSATED。
 */
@ExtendWith(MockitoExtension.class)
class TrashCommandHandlerTest {

    @TempDir
    Path tempRoot;

    @Mock
    private ManagementCommandPublisher publisher;

    private StorageProperties storageProperties;
    private TrashManifestStore manifestStore;
    private TrashCommandHandler handler;
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
        roots.put("HQ", rootOf(Files.createDirectories(tempRoot.resolve("hq"))));
        roots.put("LQ", rootOf(Files.createDirectories(tempRoot.resolve("lq"))));
        roots.put("TRASH", rootOf(Files.createDirectories(tempRoot.resolve("trash"))));
        storageProperties.setRoots(roots);
        manifestStore = new TrashManifestStore(storageProperties, objectMapper);
        handler = new TrashCommandHandler(storageProperties, manifestStore, publisher);
    }

    private static StorageRoot rootOf(Path path) {
        StorageRoot root = new StorageRoot();
        root.setPath(path);
        return root;
    }

    private ManagementCommandRequestedEvent cmd() {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, TASK_ID, ITEM_ID, ATTEMPT,
                "CHAPTER_TRASH", "CHAPTER", 1L);
    }

    private void writeManifest(List<TrashManifestDTO.Entry> entries) throws Exception {
        Path manifestDir = manifestStore.manifestDir("CHAPTER", 1L, TASK_ID);
        Files.createDirectories(manifestDir);
        TrashManifestDTO manifest = new TrashManifestDTO(
                TrashManifestDTO.CURRENT_VERSION, "CHAPTER", 1L, TASK_ID, Instant.now(), entries);
        Files.writeString(manifestDir.resolve("manifest.json"),
                objectMapper.writeValueAsString(manifest), StandardCharsets.UTF_8);
    }

    private TrashManifestItemDTO readActual() throws Exception {
        Path actual = manifestStore.manifestDir("CHAPTER", 1L, TASK_ID).resolve("actual.json");
        return objectMapper.readValue(Files.readString(actual), TrashManifestItemDTO.class);
    }

    private void writeSource(String rootKey, String relative, String content) throws Exception {
        Path file = storageProperties.getRoots().get(rootKey).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("回收：按 manifest 逐文件移动 HQ/LQ，actual.json=TRASHED")
    void trash_movesPerFileEntries_writesActualTrashed() throws Exception {
        // 逐媒体条目（真实 hqPath/lqPath，非目录猜测）
        writeSource("HQ", "1/100/001.jpg", "hq-1");
        writeSource("HQ", "1/100/002.jpg", "hq-2");
        writeSource("LQ", "1/100/001.webp", "lq-1");
        writeManifest(List.of(
                new TrashManifestDTO.Entry("HQ", "1/100/001.jpg", "hq/1/100/001.jpg"),
                new TrashManifestDTO.Entry("HQ", "1/100/002.jpg", "hq/1/100/002.jpg"),
                new TrashManifestDTO.Entry("LQ", "1/100/001.webp", "lq/1/100/001.webp")));
        ManagementCommandRequestedEvent cmd = cmd();

        handler.trash(cmd);

        assertThat(Files.exists(tempRoot.resolve("hq/1/100/001.jpg"))).isFalse();
        assertThat(Files.exists(tempRoot.resolve("hq/1/100/002.jpg"))).isFalse();
        assertThat(Files.exists(tempRoot.resolve("lq/1/100/001.webp"))).isFalse();
        assertThat(Files.exists(tempRoot.resolve("trash/CHAPTER/1/" + TASK_ID + "/hq/1/100/001.jpg"))).isTrue();
        assertThat(Files.exists(tempRoot.resolve("trash/CHAPTER/1/" + TASK_ID + "/lq/1/100/001.webp"))).isTrue();
        assertThat(readActual().status()).isEqualTo(TrashManifestItemDTO.STATUS_TRASHED);
        assertThat(readActual().entries()).allMatch(
                e -> TrashManifestItemDTO.Entry.STATE_TRASHED.equals(e.state()));
        verify(publisher).completed(cmd);
        verify(publisher, never()).failed(any(ManagementCommandRequestedEvent.class), anyString());
    }

    @Test
    @DisplayName("回收：缺失源逐条 MISSING，不伪装为已移入 TRASH")
    void trash_missingSource_recordsMISSING() throws Exception {
        // 源文件缺失（真实缺失，manifest 指向真实路径）
        writeManifest(List.of(
                new TrashManifestDTO.Entry("HQ", "1/100/001.jpg", "hq/1/100/001.jpg")));
        ManagementCommandRequestedEvent cmd = cmd();

        handler.trash(cmd);

        TrashManifestItemDTO actual = readActual();
        assertThat(actual.status()).isEqualTo(TrashManifestItemDTO.STATUS_TRASHED);
        assertThat(actual.entries()).singleElement().satisfies(e -> {
            assertThat(e.sourceRelativePath()).isEqualTo("1/100/001.jpg");
            assertThat(e.state()).isEqualTo(TrashManifestItemDTO.Entry.STATE_MISSING);
        });
        verify(publisher).completed(cmd);
        verify(publisher, never()).failed(any(ManagementCommandRequestedEvent.class), anyString());
    }

    @Test
    @DisplayName("回收：重复命令幂等，源已移走 → 全部 MISSING 不报错")
    void trash_duplicateCommand_idempotent() throws Exception {
        writeSource("HQ", "1/100/001.jpg", "hq-1");
        writeManifest(List.of(
                new TrashManifestDTO.Entry("HQ", "1/100/001.jpg", "hq/1/100/001.jpg")));
        ManagementCommandRequestedEvent cmd = cmd();

        handler.trash(cmd);
        assertThat(Files.exists(tempRoot.resolve("hq/1/100/001.jpg"))).isFalse();

        // 第二次执行同一命令：源已缺失 → 全部 MISSING，不报错、不重复移动
        handler.trash(cmd);
        assertThat(Files.exists(tempRoot.resolve("trash/CHAPTER/1/" + TASK_ID + "/hq/1/100/001.jpg"))).isTrue();
        assertThat(readActual().status()).isEqualTo(TrashManifestItemDTO.STATUS_TRASHED);
        verify(publisher, never()).failed(any(ManagementCommandRequestedEvent.class), anyString());
    }

    @Test
    @DisplayName("回收：目标已存在绝不覆盖 → 反向补偿 → COMPENSATED")
    void trash_targetExists_compensatesBack() throws Exception {
        writeSource("HQ", "1/100/001.jpg", "hq-1");
        writeManifest(List.of(
                new TrashManifestDTO.Entry("HQ", "1/100/001.jpg", "hq/1/100/001.jpg")));
        // 预占 TRASH 目标
        Path target = tempRoot.resolve("trash/CHAPTER/1/" + TASK_ID + "/hq/1/100/001.jpg");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "occupied");
        ManagementCommandRequestedEvent cmd = cmd();

        handler.trash(cmd);

        assertThat(Files.exists(tempRoot.resolve("hq/1/100/001.jpg"))).isTrue();
        assertThat(readActual().status()).isEqualTo(TrashManifestItemDTO.STATUS_COMPENSATED);
        verify(publisher).failed(any(ManagementCommandRequestedEvent.class), anyString());
    }
}
