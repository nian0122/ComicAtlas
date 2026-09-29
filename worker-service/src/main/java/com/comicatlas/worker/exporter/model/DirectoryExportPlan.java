package com.comicatlas.worker.exporter.model;

import java.util.List;

/** 文件夹移出计划：只记录漫画目录和章节目录，不展开媒体文件系统清单。 */
public record DirectoryExportPlan(
        String rootDirName,
        String sourceRootKey,
        String metadataJson,
        String comicInfoXml,
        List<String> catalogDirectories,
        List<ChapterMove> chapterMoves,
        long estimatedSize) {

    public DirectoryExportPlan {
        catalogDirectories = List.copyOf(catalogDirectories);
        chapterMoves = List.copyOf(chapterMoves);
    }

    /** 单次目录 rename；源和目标均相对各自存储根。 */
    public record ChapterMove(Long chapterId, String sourceRelativePath, String targetRelativePath) { }
}
