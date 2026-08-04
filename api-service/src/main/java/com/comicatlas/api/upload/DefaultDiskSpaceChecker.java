package com.comicatlas.api.upload;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 默认磁盘空闲空间探测（真实 FileStore）。
 */
@Component
public class DefaultDiskSpaceChecker implements DiskSpaceChecker {

    @Override
    public SpaceInfo spaceInfo(Path dir) {
        try {
            var store = Files.getFileStore(dir);
            return new SpaceInfo(store.getUsableSpace(), store.getTotalSpace());
        } catch (IOException e) {
            return new SpaceInfo(0, 0);
        }
    }
}
