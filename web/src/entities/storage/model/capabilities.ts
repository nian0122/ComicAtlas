const messages: Record<string, string> = {
  LQ_NOT_READY: '待释放图片缺少来源匹配的有效轻量副本',
  NO_HQ_IMAGES: '没有可释放的图片原件',
  HQ_SOURCE_SHA256_MISSING: '图片原件缺少 SHA-256，请在漫画详情中核对媒体',
  HQ_STATE_INVALID: '图片原件状态不能用于释放',
  NO_IMAGES: '没有可处理的图片',
  TOO_MANY_IMAGES: '图片数量超出单次处理范围',
  TOO_MANY_MEDIA: '媒体数量超出单次处理范围',
  LQ_TASK_ACTIVE: '轻量副本任务正在执行',
  HQ_TASK_ACTIVE: '原件释放任务正在执行',
  EXPORT_TASK_ACTIVE: '导出任务正在执行',
  MEDIA_TASK_ACTIVE: '媒体处理任务正在执行',
  METADATA_REFRESH_ACTIVE: '媒体核对任务正在执行',
  UPLOAD_TASK_ACTIVE: '上传发布任务正在执行',
  TRANSCODE_TASK_ACTIVE: '视频转码任务正在执行',
  INCOMPLETE_RELEASE: '请先处理未完成的原件释放任务',
  INCOMPLETE_METADATA_REFRESH: '请先处理未完成的媒体核对任务',
  INCOMPLETE_UPLOAD: '请先处理未完成的上传发布任务',
  INCOMPLETE_TRANSCODE: '请先处理未完成的视频转码任务',
  QUEUE_FULL: '任务队列已满，请稍后重试',
  COMIC_SCOPE_REQUIRED: '该操作需要选择整部漫画',
}
export function operationBlockers(codes: string[]) {
  return codes.map((code) => messages[code] ?? code).join('；')
}
