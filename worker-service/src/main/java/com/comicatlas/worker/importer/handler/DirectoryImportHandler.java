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
import com.comicatlas.worker.importer.model.ImportNormalizationManifest;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 导入编排：先保存结构计划、整理并暂存章节目录，再从暂存媒体分析和生成导入清单。 */
@Slf4j
@Component
public class DirectoryImportHandler {
    private static final int MANIFEST_VERSION = 1;
    private static final String NON_IMPORTED_DIRECTORY_NAME = "__comic_atlas_non_imported__";
    private static final String NON_IMPORTED_MARKER_NAME = ".comic-atlas-non-imported";

    private final DirectoryParser parser;
    private final MetadataAssembler assembler;
    private final StorageService storageService;
    private final ImportMetadataArtifactService metadataArtifactService;
    private final ImportCoverService importCoverService;
    private final CancelHandler cancelHandler;
    private final ImportManifestManager manifestManager;

    @Autowired
    public DirectoryImportHandler(DirectoryParser parser, MetadataAssembler assembler,
            StorageService storageService, ImportMetadataArtifactService metadataArtifactService,
            ImportCoverService importCoverService, CancelHandler cancelHandler,
            ImportManifestManager manifestManager) {
        this.parser = parser;
        this.assembler = assembler;
        this.storageService = storageService;
        this.metadataArtifactService = metadataArtifactService;
        this.importCoverService = importCoverService;
        this.cancelHandler = cancelHandler;
        this.manifestManager = manifestManager;
    }

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
            ImportNormalizationManifest normalization = loadOrCreateNormalization(
                    importContext, taskId, comicId, mangaRoot);
            if (cancelHandler.isCancelled(taskId)) {
                throw new TaskCancelledException(taskId);
            }
            prepareStagingDirectories(normalization, taskId, comicId);
            Path stagingComicRoot = Path.of(normalization.stagingComicRoot());
            ComicMetadata metadata = assembler.analyzePlannedFromStaging(
                    normalization.plannedMetadata(), stagingComicRoot);
            ManifestBuildResult buildResult = buildManifestFiles(metadata, taskId, comicId);
            var metadataNode = metadataArtifactService.buildNode(metadata, buildResult.nameMap());
            manifest = new ImportManifest(MANIFEST_VERSION, taskId, importContext.sourceType(),
                    stagingComicRoot.toString(), metadataNode, buildResult.files());
            manifestManager.write(mangaRoot, taskId, manifest);
            manifestManager.deleteNormalization(mangaRoot, taskId);
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

    public boolean hasRecoveryPoint(Path mangaRoot, Long taskId) {
        return manifestManager.exists(mangaRoot, taskId)
                || manifestManager.normalizationExists(mangaRoot, taskId);
    }

    /** 从持久化检查点恢复，不再访问或重新解析原始来源目录。 */
    public Path resumeExisting(Long taskId, Long comicId, Path mangaRoot) throws IOException {
        String sourceType;
        Path savedSourceRoot;
        if (manifestManager.exists(mangaRoot, taskId)) {
            ImportManifest manifest = manifestManager.read(mangaRoot, taskId);
            sourceType = manifest.sourceType();
            savedSourceRoot = Path.of(manifest.sourceRoot());
        } else {
            ImportNormalizationManifest normalization = manifestManager.readNormalization(mangaRoot, taskId);
            sourceType = normalization.sourceType();
            savedSourceRoot = Path.of(normalization.sourceRoot());
        }
        return handle(new ImportContext(sourceType, savedSourceRoot, false, false),
                taskId, comicId, mangaRoot);
    }

    /** 扫描并原子保存目录结构计划；恢复时复用计划，避免对已经整理的来源重新推断目录树。 */
    private ImportNormalizationManifest loadOrCreateNormalization(ImportContext importContext,
                                                                    Long taskId, Long comicId,
                                                                    Path mangaRoot) throws IOException {
        if (manifestManager.normalizationExists(mangaRoot, taskId)) {
            return manifestManager.readNormalization(mangaRoot, taskId);
        }
        DirectoryTree tree = parser.parse(importContext.sourcePath(), importContext.sourceType());
        Optional<ComicInfoMetadata> comicInfo = parseComicInfo(importContext, tree);
        ComicMetadata plannedMetadata = assembler.planStructure(tree, importContext,
                comicInfo.orElse(null));
        Path stagingComicRoot = storageService.resolve(new StorageRef(StorageRootKeys.HQ,
                ImportStagingPath.comicRelativeToHq(comicId, taskId).toString()));
        ImportNormalizationManifest normalization = new ImportNormalizationManifest(
                MANIFEST_VERSION, taskId, importContext.sourceType(), tree.path().toAbsolutePath().normalize().toString(),
                stagingComicRoot.toAbsolutePath().normalize().toString(), plannedMetadata);
        manifestManager.writeNormalization(mangaRoot, taskId, normalization);
        return normalization;
    }

    /** 将每章标准化为平铺暂存目录；纯章节目录整目录移动，根散页或混合节点只移动目标媒体。 */
    private void prepareStagingDirectories(ImportNormalizationManifest normalization, Long taskId, Long comicId)
            throws IOException {
        Path sourceRoot = Path.of(normalization.sourceRoot()).toAbsolutePath().normalize();
        Path stagingComicRoot = Path.of(normalization.stagingComicRoot()).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(sourceRoot) || !Files.isDirectory(sourceRoot)) {
            throw new IOException("导入来源根目录失效或为符号链接");
        }
        for (ComicMetadata.ChapterInfo chapter : normalization.plannedMetadata().chapters()) {
            if (cancelHandler.isCancelled(taskId)) {
                throw new TaskCancelledException(taskId);
            }
            Path stagingChapter = stagingComicRoot.resolve(String.valueOf(chapter.globalOrder())).normalize();
            if (!stagingChapter.startsWith(stagingComicRoot)) {
                throw new IOException("暂存章节路径越界: globalOrder=" + chapter.globalOrder());
            }
            if (Files.isSymbolicLink(stagingChapter)) {
                throw new IOException("暂存章节目标为符号链接，拒绝操作: globalOrder=" + chapter.globalOrder());
            }
            Path sourceChapter = chapter.sourceDir() == null || chapter.sourceDir().isBlank()
                    ? sourceRoot : sourceRoot.resolve(chapter.sourceDir()).normalize();
            if (!sourceChapter.startsWith(sourceRoot) || Files.isSymbolicLink(sourceChapter)) {
                throw new IOException("来源章节路径越界: globalOrder=" + chapter.globalOrder());
            }
            if (Files.exists(stagingChapter)) {
                if (Files.isDirectory(sourceChapter)) {
                    // 恢复逐文件标准化被中断的章节；已搬的文件留在暂存区，剩余文件续做。
                    moveChapterMediaFiles(sourceRoot, sourceChapter,
                            stagingChapter, chapter, comicId, taskId);
                }
                verifyStagingChapter(stagingChapter, chapter);
                continue;
            }
            if (!sourceChapter.startsWith(sourceRoot) || Files.isSymbolicLink(sourceChapter)
                    || !Files.isDirectory(sourceChapter, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("来源章节目录缺失或越界: globalOrder=" + chapter.globalOrder());
            }
            Files.createDirectories(stagingChapter.getParent());
            if (canMoveChapterDirectory(sourceChapter, sourceRoot, stagingChapter)) {
                isolateNonTargetFiles(sourceChapter, sourceRoot, chapter.sourceDir(), chapter, taskId);
                if (Files.exists(stagingChapter)) {
                    throw new IOException("暂存章节目标已存在，拒绝覆盖: globalOrder=" + chapter.globalOrder());
                }
                Files.move(sourceChapter, stagingChapter);
            } else {
                Files.createDirectories(stagingChapter);
                moveChapterMediaFiles(sourceRoot, sourceChapter,
                        stagingChapter, chapter, comicId, taskId);
            }
            verifyStagingChapter(stagingChapter, chapter);
        }
    }

    private boolean canMoveChapterDirectory(Path sourceChapter, Path sourceRoot, Path stagingChapter)
            throws IOException {
        if (sourceChapter.equals(sourceRoot) || Files.isSymbolicLink(sourceChapter)
                || !Files.isDirectory(sourceChapter)) {
            return false;
        }
        try (var children = Files.list(sourceChapter)) {
            if (children.anyMatch(path -> Files.isSymbolicLink(path) || Files.isDirectory(path))) {
                return false;
            }
        }
        return Files.getFileStore(sourceChapter).equals(Files.getFileStore(stagingChapter.getParent()));
    }

    /** 非媒体项留在来源树的 _non_imported/{taskId}/{原相对目录}，目标媒体目录可整体移动。 */
    private void isolateNonTargetFiles(Path sourceChapter, Path sourceRoot, String sourceRelative,
                                       ComicMetadata.ChapterInfo chapter, Long taskId) throws IOException {
        java.util.Set<String> targetNames = chapter.pages().stream()
                .map(ComicMetadata.MediaInfo::fileName).collect(java.util.stream.Collectors.toSet());
        Path nonImportedRoot = sourceRoot.resolve(NON_IMPORTED_DIRECTORY_NAME).resolve(String.valueOf(taskId));
        Path relativeDirectory = sourceRelative == null || sourceRelative.isBlank()
                ? Path.of("root") : Path.of(sourceRelative);
        try (var entries = Files.list(sourceChapter)) {
            List<Path> sourceEntries = entries.toList();
            boolean hasNonTargetFiles = sourceEntries.stream().anyMatch(sourceFile ->
                    !Files.isDirectory(sourceFile) && !targetNames.contains(sourceFile.getFileName().toString()));
            if (hasNonTargetFiles) {
                ensureNonImportedRoot(sourceRoot.resolve(NON_IMPORTED_DIRECTORY_NAME));
            }
            for (Path sourceFile : sourceEntries) {
                if (Files.isSymbolicLink(sourceFile)) {
                    throw new IOException("来源章节含符号链接，拒绝整理: " + sourceFile.getFileName());
                }
                if (Files.isDirectory(sourceFile) || targetNames.contains(sourceFile.getFileName().toString())) {
                    continue;
                }
                Path targetFile = nonImportedRoot.resolve(relativeDirectory)
                        .resolve(sourceFile.getFileName()).normalize();
                if (!targetFile.startsWith(nonImportedRoot)) {
                    throw new IOException("非目标文件隔离路径越界");
                }
                if (Files.exists(targetFile)) {
                    if (!Files.exists(sourceFile)) {
                        continue;
                    }
                    throw new IOException("非目标文件隔离位置已存在，拒绝覆盖: " + sourceFile.getFileName());
                }
                Files.createDirectories(targetFile.getParent());
                Files.move(sourceFile, targetFile);
            }
        }
    }

    /** 仅复用带本程序标记的隔离根；来源目录中同名用户目录不得被覆盖或混用。 */
    private void ensureNonImportedRoot(Path nonImportedRoot) throws IOException {
        if (Files.exists(nonImportedRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(nonImportedRoot)
                    || !Files.isDirectory(nonImportedRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("非目标隔离路径已存在但不是安全目录");
            }
        } else {
            Files.createDirectories(nonImportedRoot);
        }
        Path marker = nonImportedRoot.resolve(NON_IMPORTED_MARKER_NAME);
        if (Files.exists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("非目标隔离目录标记无效");
            }
            return;
        }
        try (var entries = Files.list(nonImportedRoot)) {
            if (entries.findAny().isPresent()) {
                throw new IOException("非目标隔离目录同名冲突，拒绝混用用户目录");
            }
        }
        Files.writeString(marker, "ComicAtlas non-imported files\n",
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
    }

    private void moveChapterMediaFiles(Path sourceRoot, Path sourceChapter,
                                       Path stagingChapter, ComicMetadata.ChapterInfo chapter,
                                       Long comicId, Long taskId)
            throws IOException {
        for (ComicMetadata.MediaInfo page : chapter.pages()) {
            Path sourceFile = sourceChapter.resolve(page.fileName()).normalize();
            Path targetFile = stagingChapter.resolve(page.fileName()).normalize();
            if (!sourceFile.startsWith(sourceRoot) || !targetFile.startsWith(stagingChapter)) {
                throw new IOException("媒体整理路径越界: globalOrder=" + chapter.globalOrder());
            }
            if (Files.exists(targetFile)) {
                if (Files.exists(sourceFile)) {
                    throw new IOException("来源和暂存同时存在同名媒体，拒绝猜测恢复: " + page.fileName());
                }
                if (Files.size(targetFile) != page.fileSize()) {
                    throw new IOException("暂存媒体尺寸冲突: " + page.fileName());
                }
                continue;
            }
            if (!Files.isRegularFile(sourceFile)) {
                throw new IOException("来源媒体缺失: " + page.fileName());
            }
            StorageRef stagingRef = new StorageRef(StorageRootKeys.HQ,
                    ImportStagingPath.chapterRelativeToHq(
                            comicId, taskId, chapter.globalOrder())
                            .resolve(page.fileName()).toString().replace('\\', '/'));
            storageService.transfer(sourceFile, stagingRef, TransferMode.MOVE);
        }
    }

    private void verifyStagingChapter(Path stagingChapter, ComicMetadata.ChapterInfo chapter) throws IOException {
        if (!Files.isDirectory(stagingChapter)) {
            throw new IOException("暂存章节不是目录: globalOrder=" + chapter.globalOrder());
        }
        for (ComicMetadata.MediaInfo page : chapter.pages()) {
            Path stagedFile = stagingChapter.resolve(page.fileName());
            if (Files.isSymbolicLink(stagedFile)
                    || !Files.isRegularFile(stagedFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.size(stagedFile) != page.fileSize()) {
                throw new IOException("暂存媒体校验失败: globalOrder=" + chapter.globalOrder()
                        + ", fileName=" + page.fileName());
            }
        }
        java.util.Set<String> expectedNames = chapter.pages().stream()
                .map(ComicMetadata.MediaInfo::fileName).collect(java.util.stream.Collectors.toSet());
        try (var entries = Files.list(stagingChapter)) {
            List<Path> stagedEntries = entries.toList();
            if (stagedEntries.size() != expectedNames.size()
                    || stagedEntries.stream().anyMatch(path -> !Files.isRegularFile(path)
                    || !expectedNames.contains(path.getFileName().toString()))) {
                throw new IOException("暂存章节包含非目标文件或目录: globalOrder=" + chapter.globalOrder());
            }
        }
    }

    public static Optional<ComicInfoMetadata> parseComicInfo(ImportContext importContext, DirectoryTree tree)
            throws IOException {
        Optional<ComicInfoMetadata> sourceInfo = ComicInfoParser.parse(importContext.sourcePath());
        if (sourceInfo.isPresent() || importContext.sourcePath().equals(tree.path())) {
            return sourceInfo;
        }
        return ComicInfoParser.parse(tree.path());
    }

    private ManifestBuildResult buildManifestFiles(ComicMetadata metadata, Long taskId, Long comicId) {
        List<ImportManifest.ImportFile> files = new ArrayList<>();
        Map<String, String> nameMap = new LinkedHashMap<>();
        for (ComicMetadata.ChapterInfo chapter : metadata.chapters()) {
            for (ComicMetadata.MediaInfo page : chapter.pages()) {
                String relative = chapter.globalOrder() + "/" + page.fileName();
                String target = ImportStagingPath.chapterRelativeToHq(comicId, taskId, chapter.globalOrder())
                        .resolve(page.fileName()).toString().replace('\\', '/');
                files.add(new ImportManifest.ImportFile(relative, target, page.fileSize()));
                String logicalSource = chapter.sourceDir() == null || chapter.sourceDir().isBlank()
                        ? page.fileName() : chapter.sourceDir() + "/" + page.fileName();
                nameMap.put(logicalSource, target);
            }
        }
        return new ManifestBuildResult(files, nameMap);
    }

    private static final class ManifestBuildResult {
        private final List<ImportManifest.ImportFile> files;
        private final Map<String, String> nameMap;

        private ManifestBuildResult(List<ImportManifest.ImportFile> files, Map<String, String> nameMap) {
            this.files = files;
            this.nameMap = nameMap;
        }

        private List<ImportManifest.ImportFile> files() { return files; }
        private Map<String, String> nameMap() { return nameMap; }
    }
}
