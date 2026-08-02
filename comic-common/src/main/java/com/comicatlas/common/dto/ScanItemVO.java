package com.comicatlas.common.dto;

/**
 * 目录扫描结果项：父目录下的一个漫画候选子目录。
 * API 与 Worker 通过该 DTO 共享扫描结果。
 */
public record ScanItemVO(String name, String path, int imageCount) {
}
