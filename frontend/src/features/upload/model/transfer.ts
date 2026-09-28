/** 生成 HTTP Content-Range 使用的闭区间字节范围。 */
export function formatUploadContentRange(start: number, endExclusive: number, total: number): string {
  return `bytes ${start}-${endExclusive - 1}/${total}`
}
