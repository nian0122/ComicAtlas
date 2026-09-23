package com.comicatlas.worker.importer.archive;

/** 压缩包中的一个目录或文件条目。路径始终使用正斜杠。 */
public final class ArchiveEntry {
    private final String name;
    private final boolean directory;
    private final long size;
    private final String crc;

    public ArchiveEntry(String name, boolean directory, long size, String crc) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("压缩包条目名称不能为空");
        }
        this.name = name.replace('\\', '/');
        this.directory = directory;
        this.size = size;
        this.crc = crc;
    }

    public String name() {
        return name;
    }

    public boolean directory() {
        return directory;
    }

    public long size() {
        return size;
    }

    public String crc() {
        return crc;
    }
}
