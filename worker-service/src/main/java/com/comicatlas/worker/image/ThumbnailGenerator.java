package com.comicatlas.worker.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.file.*;

@Slf4j
@Component
public class ThumbnailGenerator {
    public void generate(Path sourceImage, Path destThumb) throws Exception {
        Files.createDirectories(destThumb.getParent());
        Files.copy(sourceImage, destThumb, StandardCopyOption.REPLACE_EXISTING);
    }
}
