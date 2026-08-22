package com.comicatlas.common.storage;

import java.nio.file.Path;

/**
 * 导入暂存路径契约。
 *
 * <p>暂存目录必须与正式的 {@code {comicId}/{chapterId}} 命名空间隔离，
 * 否则 chapterId 与 globalOrder 数值重叠时会发生跨章节覆盖或冲突。</p>
 */
public final class ImportStagingPath {

    /** HQ 下导入暂存目录名。 */
    public static final String DIRECTORY_NAME = ".staging";

    private ImportStagingPath() {
    }

    /** 返回相对 MANGA_ROOT 的暂存章节目录。 */
    public static Path chapterDirectory(Long comicId, Long taskId, Integer globalOrder) {
        if (comicId == null || taskId == null || globalOrder == null) {
            throw new IllegalArgumentException("导入暂存路径参数不能为空");
        }
        return Path.of("hq", DIRECTORY_NAME, String.valueOf(taskId),
                String.valueOf(comicId), String.valueOf(globalOrder));
    }

    /** 返回相对 HQ 根的暂存章节目录。 */
    public static Path chapterRelativeToHq(Long comicId, Long taskId, Integer globalOrder) {
        if (comicId == null || taskId == null || globalOrder == null) {
            throw new IllegalArgumentException("导入暂存路径参数不能为空");
        }
        return Path.of(DIRECTORY_NAME, String.valueOf(taskId), String.valueOf(comicId),
                String.valueOf(globalOrder));
    }

    /** 返回相对 HQ 根的暂存漫画目录。 */
    public static Path comicRelativeToHq(Long comicId, Long taskId) {
        if (comicId == null || taskId == null) {
            throw new IllegalArgumentException("导入暂存路径参数不能为空");
        }
        return Path.of(DIRECTORY_NAME, String.valueOf(taskId), String.valueOf(comicId));
    }
}
