package com.comicatlas.api.upload;

import java.nio.file.Path;

/**
 * 磁盘空闲空间探测 — 抽象以便测试注入磁盘不足场景。
 */
@FunctionalInterface
public interface DiskSpaceChecker {

    SpaceInfo spaceInfo(Path dir);

    record SpaceInfo(long usable, long total) {}
}
