package com.comicatlas.worker.file.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 统一压缩包访问接口，导入和预扫描只依赖此接口，不感知具体格式。 */
public interface ArchiveReader extends AutoCloseable {

    boolean supports(Path archive);

    ArchiveFormat detectFormat(Path archive) throws IOException;

    List<Path> detectVolumes(Path archive) throws IOException;

    ArchiveSession open(Path archive, Duration timeout) throws IOException;

    @Override
    default void close() {
        // Reader 本身无状态，资源由 ArchiveSession 管理。
    }

    interface ArchiveSession extends AutoCloseable {
        List<ArchiveEntry> listEntries() throws IOException;

        InputStream readEntry(String name) throws IOException;

        void testIntegrity() throws IOException;

        @Override
        void close() throws IOException;
    }
}
