package com.comicatlas.api.admin.dto;

/**
 * 恢复进度记录 — 每处理一个漫画目录后返回，包含该次处理的计数器（0 或 1）和详情。
 * 调用方负责累加各计数器以生成聚合进度。
 */
public record RecoveryProgress(
    int totalComics,       // 已处理总数（含本次）
    int recoveredComics,   // 本次恢复 0 或 1
    int skippedComics,     // 本次跳过（DB 中已存在）0 或 1
    int placeholderComics, // 本次创建占位 0 或 1
    int errorComics,       // 本次出错 0 或 1
    String lastError,      // 最近错误信息，无错误时为 null
    int restoredChapters,  // 本次恢复的章节数
    int restoredPages      // 本次恢复的页面数
) {}
