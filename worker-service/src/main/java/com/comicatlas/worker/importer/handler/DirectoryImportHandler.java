package com.comicatlas.worker.importer.handler;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.storage.ImportStagingPath;
import com.comicatlas.worker.importer.metadata.ImportCoverService;
import com.comicatlas.worker.importer.metadata.ImportMetadataArtifactService;
import com.comicatlas.worker.importer.manifest.ImportManifestManager;
import com.comicatlas.worker.importer.metadata.MetadataAssembler;
import com.comicatlas.worker.importer.model.ComicInfoMetadata;
import com.comicatlas.worker.importer.model.DirectoryTree;
import com.comicatlas.worker.importer.model.ImportContext;
import com.comicatlas.worker.importer.model.ImportManifest;
import com.comicatlas.worker.importer.parser.ComicInfoParser;
import com.comicatlas.worker.importer.parser.DirectoryParser;
import com.comicatlas.worker.importer.metadata.CoverCandidateSelector;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageService;
import com.comicatlas.worker.storage.TransferMode;
import com.comicatlas.worker.task.command.CancelHandler;
import com.comicatlas.worker.task.exception.TaskCancelledException;
import com.comicatlas.worker.storage.TransferService;
import com.comicatlas.worker.media.image.CoverGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 清单驱动的导入编排：解析、搬运和恢复，文件产物由专用服务负责。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryImportHandler {
    private static final int MANIFEST_VERSION = 1;

    private final DirectoryParser parser;
    private final MetadataAssembler assembler;
    private final StorageService storageService;
    private final ImportMetadataArtifactService metadataArtifactService;
    private final ImportCoverService importCoverService;
    private final CancelHandler cancelHandler;
    private final ImportManifestManager manifestManager;

    /** 兼容已有单元测试与扩展点的旧依赖构造器；实际职责仍由两个专用服务承担。 */
    public DirectoryImportHandler(DirectoryParser parser, MetadataAssembler assembler,
            TransferService transferService, ObjectMapper objectMapper, CoverGenerator coverGenerator,
            CoverCandidateSelector coverCandidateSelector, CancelHandler cancelHandler,
            ImportManifestManager manifestManager) {
        this(parser, assembler, transferService, new ImportMetadataArtifactService(objectMapper),
                new ImportCoverService(coverGenerator, coverCandidateSelector, transferService),
                cancelHandler, manifestManager);
    }

    public Path handle(ImportContext importContext, Long taskId, Long comicId, Path mangaRoot) throws IOException {
        ImportManifest manifest;
        if (manifestManager.exists(mangaRoot, taskId)) {
            manifest = manifestManager.read(mangaRoot, taskId);
            log.info("恢复中断导入: taskId={}, files={}", taskId, manifest.files().size());
        } else {
            DirectoryTree tree = parser.parse(importContext.sourcePath(), importContext.sourceType());
            Optional<ComicInfoMetadata> comicInfo = parseComicInfo(importContext, tree);
            ComicMetadata metadata = comicInfo.isPresent()
                    ? assembler.assemble(tree, importContext, comicInfo.get())
                    : assembler.assemble(tree, importContext);
            if (cancelHandler.isCancelled(taskId)) {
                throw new TaskCancelledException(taskId);
            }
            ManifestBuildResult buildResult = buildManifestFiles(metadata, taskId, comicId, tree.path());
            var metadataNode = metadataArtifactService.buildNode(metadata, buildResult.nameMap());
            manifest = new ImportManifest(MANIFEST_VERSION, taskId, importContext.sourceType(),
                    tree.path().toString(), metadataNode, buildResult.files());
            manifestManager.write(mangaRoot, taskId, manifest);
        }
        Path sourceRoot = Path.of(manifest.sourceRoot());
        for (ImportManifest.ImportFile file : manifest.files()) {
            if (cancelHandler.isCancelled(taskId)) {
                throw new TaskCancelledException(taskId);
            }
            Path source = sourceRoot.resolve(file.source());
            StorageRef storageRef = new StorageRef(StorageRootKeys.HQ, file.target());
            Path destination = storageService.resolve(storageRef);
            if (Files.exists(destination)) {
                if (Files.size(destination) == file.size()) {
                    continue;
                }
                throw new IOException("目标已存在但大小不匹配: " + destination);
            }
            if (!Files.exists(source)) {
                throw new IOException("源文件缺失且目标不存在: " + source);
            }
            storageService.transfer(source, storageRef, TransferMode.MOVE);
        }
        if (!importCoverService.generate(manifest.metadata(), taskId, comicId, mangaRoot)) {
            log.warn("全部封面候选生成失败，本漫画无封面: comicId={}", comicId);
        }
        return metadataArtifactService.write(manifest.metadata(), taskId, comicId, mangaRoot);
    }

    public static Optional<ComicInfoMetadata> parseComicInfo(ImportContext importContext, DirectoryTree tree)
            throws IOException {
        Optional<ComicInfoMetadata> sourceInfo = ComicInfoParser.parse(importContext.sourcePath());
        if (sourceInfo.isPresent() || importContext.sourcePath().equals(tree.path())) {
            return sourceInfo;
        }
        return ComicInfoParser.parse(tree.path());
    }

    private ManifestBuildResult buildManifestFiles(ComicMetadata metadata, Long taskId,
            Long comicId, Path importRoot) {
        List<ImportManifest.ImportFile> files = new ArrayList<>();
        Map<String, String> nameMap = new LinkedHashMap<>();
        for (ComicMetadata.ChapterInfo chapter : metadata.chapters()) {
            for (ComicMetadata.MediaInfo page : chapter.pages()) {
                Path source = importRoot.resolve(chapter.sourceDir()).resolve(page.fileName());
                if (!Files.exists(source)) {
                    source = importRoot.resolve(page.fileName());
                }
                if (Files.exists(source) && page.fileSize() > 0) {
                    String relative = importRoot.relativize(source).toString().replace('\\', '/');
                    String target = ImportStagingPath.chapterRelativeToHq(comicId, taskId, chapter.globalOrder())
                            .resolve(page.fileName()).toString().replace('\\', '/');
                    files.add(new ImportManifest.ImportFile(relative, target, page.fileSize()));
                    nameMap.put(relative, target);
                }
            }
        }
        return new ManifestBuildResult(files, nameMap);
    }

    private record ManifestBuildResult(List<ImportManifest.ImportFile> files, Map<String, String> nameMap) { }
}
