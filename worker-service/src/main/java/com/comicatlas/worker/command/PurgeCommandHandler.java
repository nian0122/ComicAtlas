package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.file.trash.TrashManifestStore;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 永久清理命令处理器（COMIC_PURGE / CHAPTER_PURGE / MEDIA_PURGE）。
 * <p>
 * 删除 TRASH/{targetType}/{targetId}/ 全部清单目录（含历史清单），
 * 目录不存在也必须回传成功；成功后 API 级联删除 DB。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurgeCommandHandler {

    private final TrashManifestStore manifestStore;
    private final ManagementCommandPublisher publisher;

    public void purge(ManagementCommandRequestedEvent cmd) {
        String targetType = cmd.targetType();
        Long targetId = cmd.targetId();
        Path targetDir = manifestStore.trashRoot().resolve(targetType + "/" + targetId);
        try {
            deleteTree(targetDir);
            publisher.completed(cmd);
            log.info("永久清理命令完成: {}/{}", targetType, targetId);
        } catch (Exception e) {
            log.error("永久清理命令异常: {}/{}", targetType, targetId, e);
            publisher.failed(cmd, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** 删除整棵目录树；目录不存在视为成功。 */
    private void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
