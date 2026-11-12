import { ref, shallowRef, computed, watch, onBeforeUnmount } from 'vue'
import { uploadApi, type UploadSession } from '@/entities/upload'
import { taskApi, type Task } from '@/entities/task'
import {
  uploadCommands,
  hashFile,
  validateFiles,
  missingParts,
  matchesUpload,
  type UploadRequest,
} from '@/features/upload-media'
import { managementCommands } from '@/features/manage-items'
import { getApiErrorMessage } from '@/shared/api/http'
export interface UploadTarget {
  chapterId: string
  comicId: string
  title: string
  replaceMediaId: string | null
  replaceTitle: string
}
const recentKey = 'comicatlas.management.upload-sessions'
const draftKey = 'comicatlas.management.upload-draft'
export function useMediaUpload(
  props: { open: boolean; target: UploadTarget | null; sessionId: string },
  accepted: (task: Task) => void,
) {
  const target = ref<UploadTarget | null>(null),
    session = ref<UploadSession | null>(null),
    files = shallowRef<File[]>([]),
    task = ref<Task | null>(null)
  const phase = ref<'idle' | 'hashing' | 'sending' | 'request'>('idle'),
    error = ref(''),
    notice = ref(''),
    hashName = ref(''),
    hashBytes = ref(0),
    restoreId = ref(''),
    page = ref(1),
    acknowledged = ref(false),
    cancelConfirm = ref(false)
  const recent = ref<string[]>([]),
    storageWarning = ref('')
  const key = ref(crypto.randomUUID()),
    hashes = new Map<File, string>()
  let controller: AbortController | null = null,
    disposed = false,
    polling = false,
    generation = 0
  try {
    const value: unknown = JSON.parse(localStorage.getItem(recentKey) ?? '[]')
    if (Array.isArray(value))
      recent.value = value.filter((v): v is string => typeof v === 'string' && /^us_[a-f0-9]{32}$/.test(v)).slice(0, 8)
  } catch {
    storageWarning.value = '无法读取本机上传记录，可输入会话编号恢复'
  }
  const processing = computed(() => phase.value !== 'idle')
  const received = computed(() => session.value?.files.reduce((n, f) => n + f.receivedBytes, 0) ?? 0)
  const completeBytes = computed(
    () =>
      session.value?.status === 'active' &&
      session.value.files.every(
        (f) => missingParts(f, session.value!.chunkSize).length === 0 && f.receivedBytes === f.size,
      ),
  )
  const canAbandon = computed(
    () =>
      session.value?.status === 'active' || (session.value?.status === 'submitted' && task.value?.status === 'failed'),
  )
  const visibleFiles = computed(() => files.value.slice((page.value - 1) * 20, page.value * 20))
  const visibleRemote = computed(() => session.value?.files.slice((page.value - 1) * 20, page.value * 20) ?? [])
  const totalFiles = computed(() => session.value?.files.length ?? files.value.length)
  function remember(id: string) {
    recent.value = [id, ...recent.value.filter((x) => x !== id)].slice(0, 8)
    try {
      localStorage.setItem(recentKey, JSON.stringify(recent.value))
    } catch {
      storageWarning.value = '浏览器未保存会话记录，请复制会话编号以便恢复'
    }
  }
  function resetFiles() {
    files.value = []
    hashes.clear()
    page.value = 1
    acknowledged.value = false
    key.value = crypto.randomUUID()
  }
  function choose(list: FileList | File[] | null) {
    if (processing.value || !list) return
    error.value = ''
    try {
      const next = Array.from(list)
      validateFiles(next, Boolean(session.value?.replaceMediaId ?? target.value?.replaceMediaId))
      files.value = next
      page.value = 1
      acknowledged.value = false
      if (!session.value) key.value = crypto.randomUUID()
    } catch (e) {
      error.value = getApiErrorMessage(e, '文件选择失败')
      if (files.value.length) error.value += '。本次选择未替换已选文件，请重新选择。'
    }
  }
  function reorder(index: number, direction: number) {
    if (session.value || processing.value) return
    const from = (page.value - 1) * 20 + index,
      to = from + direction
    if (to < 0 || to >= files.value.length) return
    const next = [...files.value]
    ;[next[from], next[to]] = [next[to]!, next[from]!]
    files.value = next
    key.value = crypto.randomUUID()
  }
  function pause() {
    if (!controller) return
    controller?.abort(new DOMException('已暂停传输', 'AbortError'))
    notice.value = '传输已暂停，服务端确认的分片会保留；继续时先核对会话状态'
  }
  async function guard(action: () => Promise<void>) {
    if (processing.value) return
    phase.value = 'request'
    error.value = ''
    try {
      await action()
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '上传操作失败')
    } finally {
      if (!disposed) phase.value = 'idle'
    }
  }
  async function refresh() {
    if (!session.value || polling) return
    polling = true
    const current = generation,
      id = session.value.id
    try {
      const value = await uploadApi.session(id)
      if (disposed || current !== generation) return
      session.value = value
      if (value.taskId) {
        const detail = await taskApi.detail(value.taskId)
        if (!disposed && current === generation) task.value = detail
      }
    } catch (e) {
      if (!disposed && current === generation) error.value = getApiErrorMessage(e, '读取上传状态失败')
    } finally {
      polling = false
    }
  }
  async function restore(id = restoreId.value) {
    if (processing.value) return
    await guard(async () => {
      const value = await uploadApi.session(id.trim())
      generation++
      cancelConfirm.value = false
      session.value = value
      task.value = null
      if (value.taskId) task.value = await taskApi.detail(value.taskId)
      target.value = null
      resetFiles()
      restoreId.value = value.id
      remember(value.id)
      notice.value = value.status === 'active' ? '请重新选择尚未传完的原文件，摘要核对后继续' : '会话状态已恢复'
    })
  }
  watch(
    () => [props.open, props.sessionId],
    async () => {
      if (!props.open) {
        pause()
        return
      }
      if (props.sessionId && session.value?.id !== props.sessionId) await restore(props.sessionId)
      else if (session.value) await refresh()
      else if (props.target) {
        target.value = { ...props.target }
        resetFiles()
        notice.value = ''
      }
    },
  )
  async function digest(file: File, signal: AbortSignal) {
    if (hashes.has(file)) return hashes.get(file)!
    phase.value = 'hashing'
    hashName.value = file.name
    hashBytes.value = 0
    const value = await hashFile(
      file,
      (n) => {
        hashBytes.value = n
      },
      signal,
    )
    hashes.set(file, value)
    return value
  }
  async function transfer() {
    if (processing.value) return
    if (!session.value && !target.value) return
    controller = new AbortController()
    const signal = controller.signal
    error.value = ''
    notice.value = ''
    phase.value = 'hashing'
    try {
      if (!session.value) {
        validateFiles(files.value, Boolean(target.value?.replaceMediaId))
        const manifest = []
        for (const file of files.value)
          manifest.push({ name: file.name, size: file.size, sha256: await digest(file, signal) })
        signal.throwIfAborted()
        const body: UploadRequest = {
          chapterId: target.value!.chapterId,
          replaceMediaId: target.value!.replaceMediaId,
          files: manifest,
        }
        try {
          const saved = JSON.parse(localStorage.getItem(draftKey) ?? 'null')
          if (saved && JSON.stringify(saved.body) === JSON.stringify(body) && typeof saved.key === 'string')
            key.value = saved.key
          localStorage.setItem(draftKey, JSON.stringify({ body, key: key.value }))
        } catch {
          storageWarning.value = '浏览器未保存待创建记录，刷新前请先确认会话编号'
        }
        phase.value = 'request'
        session.value = await uploadCommands.create(body, key.value, signal)
        remember(session.value.id)
        restoreId.value = session.value.id
        try {
          localStorage.removeItem(draftKey)
        } catch {
          storageWarning.value = '浏览器未清理待创建记录，请以会话编号核对后续操作'
        }
      }
      session.value = await uploadApi.session(session.value.id)
      if (session.value.status !== 'active') throw new Error('该会话当前不能接收文件，请查看状态与发布任务')
      const active = session.value
      for (const remote of active.files) {
        const parts = missingParts(remote, active.chunkSize)
        if (!parts.length) continue
        const local = files.value.find((f) => f.name === remote.name && f.size === remote.size)
        if (!local) throw new Error('请重新选择原文件：' + remote.name)
        const digestValue = await digest(local, signal)
        if (!matchesUpload(local, digestValue, remote))
          throw new Error('文件摘要不一致，不能作为续传来源：' + remote.name)
        const queue = [...parts]
        let failed = false
        const workers = Array.from({ length: Math.min(2, queue.length) }, async () => {
          while (!failed && queue.length) {
            const part = queue.shift()!
            signal.throwIfAborted()
            phase.value = 'sending'
            hashName.value = remote.name
            const start = part * active.chunkSize,
              blob = local.slice(start, start + active.chunkSize)
            try {
              const received = await uploadCommands.part(active.id, remote.id, blob, start, remote.size, signal)
              signal.throwIfAborted()
              remote.receivedParts.push(received.part)
              remote.receivedBytes += received.size
            } catch (e) {
              failed = true
              throw e
            }
          }
        })
        await Promise.all(workers)
      }
      notice.value = '所有字节已接收，请核对清单后单独确认发布；当前尚未完成发布'
    } catch (e) {
      if (!signal.aborted) error.value = getApiErrorMessage(e, '传输失败，请核对状态后继续')
    } finally {
      controller = null
      if (!disposed) phase.value = 'idle'
    }
  }
  async function publish() {
    if (!session.value || !acknowledged.value) return
    await guard(async () => {
      const value = await uploadCommands.complete(session.value!.id)
      task.value = value
      accepted(value)
      acknowledged.value = false
      await refresh()
      notice.value = '发布任务已受理，接收完成不代表已发布；请查看任务与会话状态'
    })
  }
  async function abandon() {
    if (!session.value || !cancelConfirm.value || !canAbandon.value) return
    await guard(async () => {
      await uploadCommands.cancel(session.value!.id)
      cancelConfirm.value = false
      await refresh()
      notice.value = '已放弃会话，暂存清理状态以服务端为准'
    })
  }
  async function retry() {
    if (!task.value?.canRetry) return
    await guard(async () => {
      task.value = await managementCommands.task(task.value!.id, 'retry')
      accepted(task.value)
      await refresh()
    })
  }
  function fresh() {
    if (processing.value) return
    generation++
    session.value = null
    task.value = null
    target.value = props.target ? { ...props.target } : null
    resetFiles()
    restoreId.value = ''
    notice.value = ''
    error.value = ''
    cancelConfirm.value = false
  }
  const timer = window.setInterval(() => {
    if (
      props.open &&
      !processing.value &&
      session.value &&
      ['submitted', 'activated'].includes(session.value.status) &&
      task.value?.status !== 'failed'
    )
      void refresh()
  }, 2000)
  onBeforeUnmount(() => {
    disposed = true
    generation++
    controller?.abort(new DOMException('组件已关闭', 'AbortError'))
    window.clearInterval(timer)
  })
  return {
    target,
    session,
    files,
    task,
    phase,
    error,
    notice,
    hashName,
    hashBytes,
    restoreId,
    page,
    acknowledged,
    cancelConfirm,
    recent,
    storageWarning,
    processing,
    received,
    completeBytes,
    canAbandon,
    visibleFiles,
    visibleRemote,
    totalFiles,
    choose,
    reorder,
    pause,
    refresh,
    restore,
    transfer,
    publish,
    abandon,
    retry,
    fresh,
  }
}
