package com.comicatlas.worker.file.archive;

/** 压缩包中的一个目录或文件条目。路径始终使用正斜杠。 */
public record ArchiveEntry(
        String name,
        boolean directory,
        long size,
        String crc
) {
    public ArchiveEntry {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("压缩包条目名称不能为空");
        }
        name = name.replace('\\', '/');
    }
}
