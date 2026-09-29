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

/** 记录目录级移动计划，任务重投时无需重新扫描或枚举媒体文件。 */
public final class DirectoryMoveCheckpoint {
    private static final String VERSION = "DIRECTORY_MOVE_V2";

    private DirectoryMoveCheckpoint() { }

    public static void save(Path checkpointPath, DirectoryExportPlan plan) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(VERSION);
        lines.add("root\t" + encode(plan.rootDirName()));
        lines.add("sourceRoot\t" + encode(plan.sourceRootKey()));
        lines.add("metadata\t" + encode(plan.metadataJson()));
        lines.add("comicInfo\t" + encode(plan.comicInfoXml() == null ? "" : plan.comicInfoXml()));
        lines.add("size\t" + plan.estimatedSize());
        for (String directory : plan.catalogDirectories()) {
            lines.add("catalog\t" + encode(directory));
        }
        for (DirectoryExportPlan.ChapterMove move : plan.chapterMoves()) {
            lines.add("chapter\t" + move.chapterId() + "\t"
                    + encode(move.sourceRelativePath()) + "\t" + encode(move.targetRelativePath()));
        }
        Path tempPath = checkpointPath.resolveSibling(checkpointPath.getFileName() + ".tmp");
        Files.write(tempPath, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tempPath, checkpointPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(tempPath, checkpointPath);
        }
    }

    public static DirectoryExportPlan load(Path checkpointPath) throws IOException {
        List<String> lines = Files.readAllLines(checkpointPath, StandardCharsets.UTF_8);
        if (lines.size() < 6 || !VERSION.equals(lines.get(0))) {
            throw new IOException("目录移动检查点格式无效");
        }
        String rootDirName = decodeField(lines.get(1), "root");
        String sourceRootKey = decodeField(lines.get(2), "sourceRoot");
        String metadataJson = decodeField(lines.get(3), "metadata");
        String comicInfoXml = decodeField(lines.get(4), "comicInfo");
        String[] sizeFields = lines.get(5).split("\\t", -1);
        if (sizeFields.length != 2 || !"size".equals(sizeFields[0])) {
            throw new IOException("目录移动检查点缺少估算大小");
        }
        long estimatedSize;
        try {
            estimatedSize = Long.parseLong(sizeFields[1]);
        } catch (NumberFormatException exception) {
            throw new IOException("目录移动检查点估算大小无效", exception);
        }
        List<String> catalogDirectories = new ArrayList<>();
        List<DirectoryExportPlan.ChapterMove> chapterMoves = new ArrayList<>();
        for (int index = 6; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\\t", -1);
            try {
                if (fields.length == 2 && "catalog".equals(fields[0])) {
                    catalogDirectories.add(decode(fields[1]));
                } else if (fields.length == 4 && "chapter".equals(fields[0])) {
                    chapterMoves.add(new DirectoryExportPlan.ChapterMove(Long.valueOf(fields[1]),
                            decode(fields[2]), decode(fields[3])));
                } else {
                    throw new IOException("目录移动检查点记录无效");
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("目录移动检查点记录无法解析", exception);
            }
        }
        return new DirectoryExportPlan(rootDirName, sourceRootKey, metadataJson,
                comicInfoXml.isBlank() ? null : comicInfoXml,
                catalogDirectories, chapterMoves, estimatedSize);
    }

    private static String decodeField(String line, String fieldName) throws IOException {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 2 || !fieldName.equals(fields[0])) {
            throw new IOException("目录移动检查点缺少 " + fieldName);
        }
        try {
            return decode(fields[1]);
        } catch (IllegalArgumentException exception) {
            throw new IOException("目录移动检查点字段无法解析: " + fieldName, exception);
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
