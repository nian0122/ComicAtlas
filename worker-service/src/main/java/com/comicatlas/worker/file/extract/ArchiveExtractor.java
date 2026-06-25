package com.comicatlas.worker.file.extract;

import java.nio.file.Path;
import java.util.List;

public interface ArchiveExtractor {
    List<Path> extract(Path archive, Path destDir) throws Exception;
    boolean supports(Path file);
}
