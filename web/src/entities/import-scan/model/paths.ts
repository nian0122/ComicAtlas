export function requireImportPath(value: string): string {
  const path = value.trim()
  if (
    !path ||
    new TextEncoder().encode(path).length > 4096 ||
    Array.from(path).some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)
  )
    throw new Error('请输入有效的服务端绝对路径，最多 4096 字节')
  if (!/^(?:\/|[a-z]:[\\/]|\\\\[^\\]+\\[^\\]+)/i.test(path))
    throw new Error('请填写宿主机绝对路径，例如 D:\\ComicAtlas-import\\漫画集；不能使用相对路径')
  return path
}

export function archiveImportPaths(value: string): string[] {
  const paths = value
    .split(/\r?\n/)
    .map((line, index) => ({ path: line.trim(), line: index + 1 }))
    .filter((entry) => entry.path)
  if (!paths.length || paths.length > 1000) throw new Error('请填写 1 至 1000 个压缩包路径，每行一个')
  const result = paths.map((entry) => {
    try {
      const absolute = requireImportPath(entry.path)
      if (!/\.(?:zip|cbz)$/i.test(absolute)) throw new Error('仅支持 ZIP / CBZ 压缩包')
      return absolute
    } catch (error) {
      throw new Error(`第 ${entry.line} 行：${error instanceof Error ? error.message : '路径无效'}`, { cause: error })
    }
  })
  if (new Set(result).size !== result.length) throw new Error('压缩包路径重复，请移除重复项')
  return result
}
