package com.comicatlas.worker.file.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 安全移动策略：
 * 同卷 → atomic rename（瞬时、原子）；
 * 跨卷 → copy 到 .tmp → size 校验 → atomic rename → 删除源（目标名永不见半截文件）。
 */
@Slf4j
@Component
public class SafeMoveStrategy {

    public void move(Path source, Path target) throws IOException {
        if (sameFileStore(source, target.getParent())) {
            moveSameVolume(source, target);
        } else {
            moveCrossVolume(source, target);
        }
    }

    /** package-private：跨卷流程，供测试直接调用（同卷环境亦可验证逻辑）。 */
    void moveCrossVolume(Path source, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            long srcSize = Files.size(source);
            long tmpSize = Files.size(tmp);
            if (tmpSize != srcSize) {
                throw new IOException("跨卷复制大小校验失败: " + source
                        + " expected=" + srcSize + " actual=" + tmpSize);
            }
            moveAtomically(tmp, target);
            Files.deleteIfExists(source);
            log.info("move (跨卷 copy+rename): {} -> {}", source, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void moveSameVolume(Path source, Path target) throws IOException {
        moveAtomically(source, target);
        log.info("move (同卷 rename): {} -> {}", source, target);
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean sameFileStore(Path a, Path b) {
        try {
            return Files.getFileStore(a).equals(Files.getFileStore(b));
        } catch (IOException e) {
            return false;
        }
    }
}
