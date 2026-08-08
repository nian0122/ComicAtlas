package com.comicatlas.common.dto;

import java.util.List;

/**
 * 目录扫描结果：父目录及其下的漫画候选子目录列表。
 * API 与 Worker 通过该 DTO 共享扫描结果。
 */
public record ScanResultDTO(String parentPath, int total, List<ScanItemDTO> items) {
}
