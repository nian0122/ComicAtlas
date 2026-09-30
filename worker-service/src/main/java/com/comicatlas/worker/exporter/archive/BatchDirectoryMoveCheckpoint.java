package com.comicatlas.worker.exporter.archive;

import com.comicatlas.worker.exporter.model.DirectoryExportPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 批量文件夹导出的目录移动计划；重投时不再重新采集漫画结构。 */
public final class BatchDirectoryMoveCheckpoint {
    private static final String VERSION = "BATCH_DIRECTORY_MOVE_V1";

    private BatchDirectoryMoveCheckpoint() { }

    public static void save(Path checkpointPath, List<DirectoryExportPlan> plans) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(VERSION);
        for (DirectoryExportPlan plan : plans) {
            lines.add("comic\t" + plan.comicId() + "\t" + encode(plan.rootDirName()) + "\t"
                    + encode(plan.sourceRootKey()) + "\t" + plan.estimatedSize());
            for (String directory : plan.catalogDirectories()) {
                lines.add("catalog\t" + plan.comicId() + "\t" + encode(directory));
            }
            for (DirectoryExportPlan.ChapterMove move : plan.chapterMoves()) {
                lines.add("chapter\t" + plan.comicId() + "\t" + move.chapterId() + "\t"
                        + encode(move.sourceRelativePath()) + "\t" + encode(move.targetRelativePath()));
            }
        }
        Path temporaryPath = checkpointPath.resolveSibling(checkpointPath.getFileName() + ".tmp");
        Files.write(temporaryPath, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporaryPath, checkpointPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporaryPath, checkpointPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static List<DirectoryExportPlan> load(Path checkpointPath) throws IOException {
        List<String> lines = Files.readAllLines(checkpointPath, StandardCharsets.UTF_8);
        if (lines.size() < 2 || !VERSION.equals(lines.getFirst())) {
            throw new IOException("批量目录移动检查点格式无效");
        }
        List<PlanBuilder> builders = new ArrayList<>();
        for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
            String[] fields = lines.get(lineIndex).split("\\t", -1);
            try {
                if (fields.length == 5 && "comic".equals(fields[0])) {
                    builders.add(new PlanBuilder(Long.valueOf(fields[1]), decode(fields[2]), decode(fields[3]),
                            Long.parseLong(fields[4])));
                } else if (fields.length == 3 && "catalog".equals(fields[0])) {
                    builderFor(builders, Long.valueOf(fields[1])).catalogDirectories.add(decode(fields[2]));
                } else if (fields.length == 5 && "chapter".equals(fields[0])) {
                    builderFor(builders, Long.valueOf(fields[1])).chapterMoves.add(
                            new DirectoryExportPlan.ChapterMove(Long.valueOf(fields[2]), decode(fields[3]), decode(fields[4])));
                } else {
                    throw new IOException("批量目录移动检查点记录无效");
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("批量目录移动检查点记录无法解析", exception);
            }
        }
        return builders.stream().map(PlanBuilder::build).toList();
    }

    private static PlanBuilder builderFor(List<PlanBuilder> builders, Long comicId) throws IOException {
        return builders.stream().filter(builder -> builder.comicId.equals(comicId)).findFirst()
                .orElseThrow(() -> new IOException("检查点记录引用未知漫画"));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static final class PlanBuilder {
        private final Long comicId;
        private final String rootDirName;
        private final String sourceRootKey;
        private final long estimatedSize;
        private final List<String> catalogDirectories = new ArrayList<>();
        private final List<DirectoryExportPlan.ChapterMove> chapterMoves = new ArrayList<>();

        private PlanBuilder(Long comicId, String rootDirName, String sourceRootKey, long estimatedSize) {
            this.comicId = comicId;
            this.rootDirName = rootDirName;
            this.sourceRootKey = sourceRootKey;
            this.estimatedSize = estimatedSize;
        }

        private DirectoryExportPlan build() {
            return new DirectoryExportPlan(comicId, rootDirName, sourceRootKey,
                    catalogDirectories, chapterMoves, estimatedSize);
        }
    }
}
