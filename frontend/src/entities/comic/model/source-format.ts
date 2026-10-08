const SOURCE_TYPE_LABELS: Record<string, string> = {
  ZIP: 'ZIP 压缩包',
  CBZ: 'CBZ 漫画包',
  DIRECTORY: '目录',
  REGISTER: '注册目录',
  EHENTAI: 'E-Hentai',
}

export function sourceTypeLabel(sourceType?: string | null): string {
  if (!sourceType) return '未知'
  return SOURCE_TYPE_LABELS[sourceType] || sourceType
}
