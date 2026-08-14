package com.comicatlas.worker.importer;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.extract.ZipExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ZIP 导入处理器 — 解压到任务唯一临时目录后委托 {@link DirectoryImportHandler}。
 *
 * <p>安全语义：解压复用 {@link ZipExtractor}（含标准分卷 .zNN 支持与全套安全校验）；
 * 无论成功失败，finally 一律用 NIO {@link Files#walk} 逆序递归删除临时目录；删除失败
 * 不得静默（聚合记录 cause），但绝不掩盖主异常 cause（主异常优先保留）。
 * 日志与异常消息不含源 zip 完整路径，只记录文件名。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZipImportHandler {

    /** 解压产物子目录名（任务临时目录下）。 */
    private static final String EXTRACT_DIR_NAME = "extracted";

    private final ZipExtractor zipExtractor;
    private final WorkerConfig config;
    private final DirectoryImportHandler directoryHandler;

    public Path importZip(ImportContext ctx, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        Path zipFile = ctx.sourcePath();
        if (!Files.exists(zipFile)) {
            throw new IllegalArgumentException("ZIP 文件不存在: " + zipFile.getFileName());
        }

        // 任务唯一临时目录：temp/{taskId}/extracted，互不干扰
        Path tempRoot = config.resolveTempDir().resolve(taskId.toString());
        Path extractDir = tempRoot.resolve(EXTRACT_DIR_NAME);
        Files.createDirectories(extractDir);

        try {
            zipExtractor.extract(zipFile, extractDir);
            log.info("ZIP 解压完成: archive={}", zipFile.getFileName());

            String fileName = zipFile.getFileName().toString();
            int lastDotIndex = fileName.lastIndexOf('.');
            String titleHint = lastDotIndex >= 0 ? fileName.substring(0, lastDotIndex) : fileName;
            // 保留原始来源类型（ZIP），使 parser 对解压根执行"恰有一个有效子目录时剥离一层
            // 传输包装"的语义；不得改写成 DIRECTORY，否则单层包装目录无法被剥离。
            ImportContext extractCtx = new ImportContext(
                ctx.sourceType(), extractDir, ctx.generateLq(), ctx.overwrite(), titleHint
            );
            return directoryHandler.handle(extractCtx, taskId, comicId, mangaRoot);
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    /**
     * NIO 逆序递归删除临时目录。删除失败聚合后记录（首个失败保留 cause 日志），
     * 但不抛出——finally 中不得掩盖主异常；日志只含任务目录名，不含源 zip 完整路径。
     */
    private void deleteRecursively(Path tempRoot) {
        if (tempRoot == null || !Files.exists(tempRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> failed = new ArrayList<>();
        IOException firstFailure = null;
        try (Stream<Path> walk = Files.walk(tempRoot)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    failed.add(path);
                    if (firstFailure == null) {
                        firstFailure = e;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("临时目录清理失败（遍历阶段），主异常优先保留，不掩盖 cause: temp/{}",
                tempRoot.getFileName(), e);
            return;
        }
        if (!failed.isEmpty()) {
            String detail = failed.stream()
                .map(path -> String.valueOf(path.getFileName()))
                .limit(5)
                .collect(Collectors.joining(", "));
            log.warn("临时目录清理失败（{} 项，示例: {}），主异常优先保留，不掩盖 cause: temp/{}",
                failed.size(), detail, tempRoot.getFileName(), firstFailure);
        }
    }
}
