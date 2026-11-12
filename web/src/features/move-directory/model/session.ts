import type { DirectoryTarget } from '@/entities/directory-move'
export interface DirectorySession {
  id: string
  token: string
  targets: DirectoryTarget[]
  key: string
  attempted: boolean
  rejected: boolean
}
const prefix = 'comicatlas.directory-move.'
export function saveDirectorySession(value: DirectorySession) {
  sessionStorage.setItem(prefix + value.id, JSON.stringify(value))
  sessionStorage.setItem(prefix + 'last', value.id)
}
export function loadDirectorySession(id: string) {
  const data = sessionStorage.getItem(prefix + id)
  if (!data) return null
  let value: DirectorySession
  try {
    value = JSON.parse(data) as DirectorySession
  } catch {
    throw new Error('当前标签页保存的预检信息无效，请从任务中心查看并重新预检')
  }
  if (
    !value ||
    typeof value !== 'object' ||
    value.id !== id ||
    typeof value.token !== 'string' ||
    typeof value.key !== 'string' ||
    typeof value.attempted !== 'boolean' ||
    typeof value.rejected !== 'boolean' ||
    !Array.isArray(value.targets) ||
    value.targets.some(
      (x) =>
        !x ||
        typeof x !== 'object' ||
        typeof x.id !== 'string' ||
        typeof x.title !== 'string' ||
        !(x.cover === null || typeof x.cover === 'string'),
    )
  )
    throw new Error('当前标签页保存的预检信息无效，请从任务中心查看并重新预检')
  return value
}
export function lastDirectorySession() {
  const id = sessionStorage.getItem(prefix + 'last')
  return id ? loadDirectorySession(id) : null
}
