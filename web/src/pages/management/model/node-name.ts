export const NODE_NAME_MAX_BYTES = 500

/** 按后端结构名称规则校验：去除首尾空白后非空，且不超过 UTF-8 字节上限。 */
export function nodeNameError(title: string) {
  const name = title.trim()
  if (!name) return '请输入名称'
  const bytes = new TextEncoder().encode(name).length
  return bytes > NODE_NAME_MAX_BYTES ? `名称最多 ${NODE_NAME_MAX_BYTES} 字节（当前 ${bytes} 字节）` : ''
}
