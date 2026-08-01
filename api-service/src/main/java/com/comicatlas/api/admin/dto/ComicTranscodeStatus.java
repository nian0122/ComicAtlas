package com.comicatlas.api.admin.dto;

/** 单漫画转码状态聚合结果（comicId + 逗号分隔的 transcode_status 集合）。 */
public record ComicTranscodeStatus(Long comicId, String transcodeStatus) {
}
