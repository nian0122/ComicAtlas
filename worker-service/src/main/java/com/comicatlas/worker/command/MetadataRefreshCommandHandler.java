package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.export.MetadataJsonExporter;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 元数据刷新命令处理器（新 envelope 路由）。
 * <p>
 * 依据 command.targetId（comicId）重扫 HQ 目录并导出 metadata.json，
 * 完成后回传 completed/failed。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataRefreshCommandHandler {

    private final MetadataJsonExporter metadataJsonExporter;
    private final WorkerConfig config;
    private final ManagementCommandPublisher publisher;

    public void refresh(ManagementCommandRequestedEvent cmd) {
        Long comicId = cmd.targetId();
        try {
            publisher.progress(cmd, 10, "开始刷新元数据");
            String metadataJson = metadataJsonExporter.exportJson(comicId);

            Path metadataDir = config.getMetadataDir() != null
                    ? Path.of(config.getMetadataDir())
                    : Path.of(config.getMangaRoot(), "metadata");
            Files.createDirectories(metadataDir);
            Path metadataFile = metadataDir.resolve(comicId + ".json");
            Files.writeString(metadataFile, metadataJson, StandardCharsets.UTF_8);

            publisher.progress(cmd, 100, "元数据刷新完成");
            publisher.completed(cmd);
            log.info("元数据刷新命令完成: comicId={}, size={} bytes", comicId, Files.size(metadataFile));
        } catch (Exception e) {
            log.error("元数据刷新命令失败: comicId={}", comicId, e);
            publisher.failed(cmd, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
