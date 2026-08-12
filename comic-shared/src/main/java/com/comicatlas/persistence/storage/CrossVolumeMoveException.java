package com.comicatlas.persistence.storage;

/**
 * 跨卷移动异常 — 当文件移动涉及不同文件系统卷时抛出。
 * 跨卷移动不可恢复（非原子 rename），调用方必须显式处理。
 */
public class CrossVolumeMoveException extends RuntimeException {
    private final String sourceVolume;
    private final String targetVolume;

    public CrossVolumeMoveException(String sourceVolume, String targetVolume, String detail) {
        super("跨卷移动拒绝: " + sourceVolume + " -> " + targetVolume + " (" + detail + ")");
        this.sourceVolume = sourceVolume;
        this.targetVolume = targetVolume;
    }

    public String getSourceVolume() {
        return sourceVolume;
    }

    public String getTargetVolume() {
        return targetVolume;
    }
}
