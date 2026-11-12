export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  if (bytes === 0) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  const index = Math.min(Math.floor(Math.log(Math.max(1, bytes)) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toLocaleString('zh-CN', { maximumFractionDigits: index > 1 ? 1 : 0 })} ${units[index]}`
}
export function formatDate(value: string | null | undefined) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(
        new Date(value),
      )
    : '—'
}
export function formatDateFull(value: string | null | undefined) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }).format(new Date(value))
    : '—'
}
export const taskStatusLabels = {
  queued: '排队中',
  running: '执行中',
  cancelling: '正在取消',
  cancelled: '已取消',
  succeeded: '已完成',
  failed: '失败',
}
export function taskTypeLabel(type: string) {
  const names: Record<string, string> = {
    directory_import: '目录导入',
    archive_import: '归档导入',
    image_lq: '生成轻量副本',
    metadata_refresh: '核对媒体',
    metadata_sync: '同步资料',
    media_upload: '上传发布',
    video_transcode: '视频转码',
    hq_release: '释放原件',
    command_batch: '批量维护',
    metadata_batch: '批量资料',
    storage_refresh: '占用核对',
    directory_scan: '扫描导入目录',
    archive_export: '导出归档',
    lifecycle: '回收与恢复',
    lifecycle_audit: '生命周期核对',
    directory_move: '移出到目录',
    directory_preflight: '目录预检',
    disk_recovery: '磁盘恢复',
    recovery_repair: '恢复修复',
    ai_analysis: '分析媒体',
    import_batch: '批量导入',
  }
  return names[type] ?? type
}
export function taskStageLabel(stage: string) {
  const labels: Record<string, string> = {
    abandoned: '已放弃发布',
    queued: '等待开始',
    pending: '等待处理',
    scanning: '扫描中',
    processing: '处理中',
    importing: '导入中',
    verifying: '核对中',
    publishing: '发布中',
    writing: '写入中',
    coordinating: '分派与汇总',
    completed: '处理结束',
    finished: '处理结束',
    failed: '执行失败',
    cancelled: '已取消',
    cancelling: '正在取消',
    decoding: '解码中',
    encoding: '编码中',
    preparing: '准备中',
    auditing: '核对文件',
    exporting: '导出中',
    restoring: '恢复中',
    purging: '清理中',
    purge_preflight_failed: '永久清理预检失败',
    trashing: '回收中',
    trash_preparation_failed: '回收准备失败，漫画已恢复',
    extracting: '解压中',
    normalizing: '整理目录',
    analyzing: '分析中',
    detecting: '识别中',
    validating: '校验中',
  }
  return stage ? (labels[stage] ?? `未识别阶段（${stage}）`) : '尚未开始'
}
export function registrationLabel(state: string) {
  const labels: Record<string, string> = {
    empty: '无登记文件',
    available: '原件已登记',
    mixed: '部分原件已释放',
    releasing: '原件释放中',
    released: '原件已释放',
    missing: '副本未就绪',
    partial: '部分副本就绪',
    ready: '副本已就绪',
  }
  return labels[state] ?? `未识别状态（${state}）`
}
