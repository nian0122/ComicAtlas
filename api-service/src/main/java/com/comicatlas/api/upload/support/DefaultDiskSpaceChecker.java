package com.comicatlas.api.upload.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 默认磁盘空闲空间探测（真实 FileStore）。
 */
@Slf4j
@Component
public class DefaultDiskSpaceChecker implements DiskSpaceChecker {

    @Override
    public SpaceInfo spaceInfo(Path dir) {
        try {
            FileStore store = Files.getFileStore(dir);
            return new SpaceInfo(store.getUsableSpace(), store.getTotalSpace());
        } catch (IOException ex) {
            // 探测失败按无可用空间处理（安全默认：拒绝新建上传会话），保留现场供排查
            log.warn("磁盘空间探测失败，按无可用空间处理: dir={}", dir, ex);
            return new SpaceInfo(0, 0);
        }
    }
}
