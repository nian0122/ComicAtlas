import type { UploadFile } from '@/entities/upload'
export const uploadAccept = '.jpg,.jpeg,.png,.gif,.webp,.mp4,.webm,.mov,.mkv,.avi'
export function validateFiles(files: readonly File[], replace: boolean) {
  if (!files.length || files.length > 10000) throw new Error('请选择 1 至 10000 个媒体文件')
  if (replace && files.length !== 1) throw new Error('替换只能选择一个文件')
  const names = new Set<string>()
  let bytes = 0
  for (const file of files) {
    if (
      !/\.(jpe?g|png|gif|webp|mp4|webm|mov|mkv|avi)$/i.test(file.name) ||
      new TextEncoder().encode(file.name).length > 255 ||
      file.name.trim() !== file.name ||
      /[\\/:<>"|?*]/u.test(file.name) ||
      [...file.name].some((c) => c.charCodeAt(0) < 32 || (c.charCodeAt(0) >= 127 && c.charCodeAt(0) <= 159))
    )
      throw new Error('文件名或类型不受支持：' + file.name)
    if (file.size < 1 || file.size > 20 * 1024 ** 3) throw new Error('文件必须大于 0 且不超过 20 GiB：' + file.name)
    if (names.has(file.name.toLowerCase())) throw new Error('文件名重复：' + file.name)
    names.add(file.name.toLowerCase())
    bytes += file.size
  }
  if (bytes > 100 * 1024 ** 3) throw new Error('单次上传总量不能超过 100 GiB')
}
export function missingParts(file: UploadFile, chunkSize: number) {
  if (!Number.isInteger(chunkSize) || chunkSize < 1) throw new Error('上传会话分片大小无效')
  const received = new Set(file.receivedParts)
  return Array.from({ length: Math.ceil(file.size / chunkSize) }, (_, i) => i).filter((i) => !received.has(i))
}
export function matchesUpload(file: File, digest: string, expected: UploadFile) {
  return file.name === expected.name && file.size === expected.size && digest === expected.sha256
}
