const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const
const BYTES_PER_UNIT = 1024

/** 按 1024 换算展示大小；字节为整数，其余单位默认保留一位小数。 */
export function formatBytes(bytes: number | null | undefined, fractionDigits = 1): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes <= 0) return '0 B'
  let size = bytes
  let unitIndex = 0
  while (size >= BYTES_PER_UNIT && unitIndex < BYTE_UNITS.length - 1) {
    size /= BYTES_PER_UNIT
    unitIndex++
  }
  return `${size.toFixed(unitIndex === 0 ? 0 : fractionDigits)} ${BYTE_UNITS[unitIndex]}`
}
