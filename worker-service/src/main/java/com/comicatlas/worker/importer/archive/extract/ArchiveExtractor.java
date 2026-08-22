package com.comicatlas.worker.importer.archive.extract;

import java.nio.file.Path;
import java.util.List;

public interface ArchiveExtractor {
    List<Path> extract(Path archive, Path destDir) throws Exception;
    boolean supports(Path file);
}
