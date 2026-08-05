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
 * 跨卷 → copy 到临时文件（.tmp 后缀）→ size 校验 → atomic rename → 删除源（目标名永不见半截文件）。
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
        Path tempPath = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.copy(source, tempPath, StandardCopyOption.REPLACE_EXISTING);
            verifyCopySize(source, tempPath);
            moveAtomically(tempPath, target);
            Files.deleteIfExists(source);
            log.info("move (跨卷 copy+rename): {} -> {}", source, target);
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    /** package-private：大小校验，供测试直接验证校验逻辑。 */
    void verifyCopySize(Path source, Path tempPath) throws IOException {
        long srcSize = Files.size(source);
        long tempSize = Files.size(tempPath);
        if (tempSize != srcSize) {
            throw new IOException("跨卷复制大小校验失败: " + source
                    + " expected=" + srcSize + " actual=" + tempSize);
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

    private boolean sameFileStore(Path first, Path second) {
        try {
            return Files.getFileStore(first).equals(Files.getFileStore(second));
        } catch (IOException e) {
            log.warn("无法判断文件存储是否同卷，将使用跨卷移动: {} / {}", first, second, e);
            return false;
        }
    }
}
