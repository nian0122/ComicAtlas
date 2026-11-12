package domain

// 失败重试也必须遵守快照读取与媒体变更的互斥，不能绕过首次受理。
func RetryMediaConflict(kind, other string) bool {
	if kind == "archive_export" || kind == "hq_release" || kind == "video_transcode" || kind == "media_upload" || kind == "metadata_refresh" {
		return other == "archive_export" || other == "image_lq" || other == "hq_release" || other == "video_transcode" || other == "media_upload" || other == "metadata_refresh"
	}
	return kind == "image_lq" && (other == "archive_export" || other == "hq_release" || other == "video_transcode" || other == "media_upload" || other == "metadata_refresh")
}
