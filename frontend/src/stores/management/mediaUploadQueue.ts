import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useUploadStore } from './upload'
import { toErrorMessage } from '@/services/management/http'
import { displayFileName, sanitizeErrorMessage, detectMediaType } from '@/types/management/media'
import type { UploadQueueEntry, UploadFileStatus } from '@/types/management/media'
import type { CreateUploadSessionRequest } from '@/types/management/upload'

/**
 * 媒体上传队列 Store（Task 19）——复用 T17 上传 Store 作为传输层。
 *
 * 职责：
 * - 浏览器 File 分块（File.slice）与 SHA-256 计算，会话清单 → createSession
 * - 每个文件一条独立 pump 循环：按服务端 receivedBytes 续传（断点续传）
 * - 暂停/继续/取消/逐文件重试，替换（replaceMediaId 保留原媒体 ID）
 * - 错误文案与文件名一律脱敏，绝不展示客户端绝对路径
 *
 * 视图层在 canComplete 变真时调用 completeUpload()，成功后自行刷新章节列表。
 */

export const useMediaUploadQueueStore = defineStore('mediaUploadQueue', () => {
  const uploadStore = useUploadStore()

  const entries = ref<readonly UploadQueueEntry[]>([])
  const replaceMediaId = ref<number | null>(null)
  const activeSessionId = ref<string | null>(null)
  const completing = ref(false)
  const queueError = ref<string | null>(null)

  /** 是否存在进行中的上传（上传中/等待中） */
  const busy = computed(() => entries.value.some((e) => e.status === 'uploading' || e.status === 'queued'))

  /** 全部条目已完成且尚未触发 complete 时，可提交会话 */
  const canComplete = computed(
    () =>
      entries.value.length > 0 &&
      !completing.value &&
      entries.value.every((e) => e.status === 'completed'),
  )

  const totalBytes = computed(() => entries.value.reduce((sum, e) => sum + e.size, 0))
  const uploadedBytes = computed(() =>
    entries.value.reduce((sum, e) => sum + Math.min(e.receivedBytes, e.size), 0),
  )

  const overallPercent = computed(() => {
    if (totalBytes.value <= 0) return 0
    return Math.min(100, Math.round((uploadedBytes.value / totalBytes.value) * 100))
  })

  function setReplaceTarget(mediaId: number | null): void {
    replaceMediaId.value = mediaId
  }

  // 上传目标由视图层注入（漫画/章节上下文）
  const uploadComicId = ref(0)
  const uploadChapterId = ref(0)

  function setTarget(comicId: number, chapterId: number): void {
    uploadComicId.value = comicId
    uploadChapterId.value = chapterId
  }

  // ======================== 分块传输 ========================

  /**
   * 不可变更新单个队列条目：始终替换 entries 数组（而非就地改代理字段），
   * 保证模板 v-for 依赖的数组引用变化后必然重渲染。
   */
  function updateEntry(
    index: number,
    patch: Partial<Pick<UploadQueueEntry, 'status' | 'receivedBytes' | 'error'>>,
  ): void {
    entries.value = entries.value.map((e) => (e.index === index ? { ...e, ...patch } : e))
  }

  const entryAt = (index: number): UploadQueueEntry | undefined =>
    entries.value.find((e) => e.index === index)

  /** 单个文件的分块上传循环：按服务端 receivedBytes 续传 */
  async function pump(index: number, chunkSize: number): Promise<void> {
    while (true) {
      const entry = entryAt(index)
      if (!entry || entry.status !== 'uploading' || entry.receivedBytes >= entry.size) break
      const start = entry.receivedBytes
      const end = Math.min(start + chunkSize, entry.size) - 1
      const chunk = entry.file.slice(start, end + 1)
      try {
        const resp = await uploadStore.uploadChunk(entry.fileId, chunk, {
          start,
          end,
          total: entry.size,
        })
        updateEntry(index, { receivedBytes: resp.receivedBytes })
      } catch (err: unknown) {
        updateEntry(index, {
          status: 'failed',
          error: sanitizeErrorMessage(toErrorMessage(err, '分块上传失败')),
        })
        return
      }
      const latest = entryAt(index)
      if (!latest || latest.status !== 'uploading') return
    }
    const final = entryAt(index)
    if (final && final.receivedBytes >= final.size) {
      updateEntry(index, { status: 'completed', error: null })
    }
  }

  async function computeSha256(file: File): Promise<string> {
    const buffer = await file.arrayBuffer()
    const digest = await crypto.subtle.digest('SHA-256', buffer)
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('')
  }

  /** 追加文件：计算摘要后创建上传会话并启动各文件 pump */
  async function addFiles(files: readonly File[]): Promise<void> {
    if (files.length === 0) return
    // 新一轮上传前清掉上一轮的已完成/已取消条目（保留失败项以便重试）
    entries.value = entries.value.filter(
      (e) => e.status !== 'completed' && e.status !== 'cancelled',
    )
    const nextIndex = entries.value.length
    const nextEntries: UploadQueueEntry[] = files.map((file, offset) => {
      const index = nextIndex + offset
      return {
        index,
        fileId: String(index),
        file,
        name: displayFileName(file.name),
        size: file.size,
        mediaType: detectMediaType(file.name, file.type),
        contentType: file.type || 'application/octet-stream',
        status: 'uploading' as UploadFileStatus,
        receivedBytes: 0,
        error: null,
      }
    })
    entries.value = [...entries.value, ...nextEntries]
    queueError.value = null

    try {
      const manifests = await Promise.all(
        nextEntries.map(async (e) => ({
          fileId: e.fileId,
          name: e.name,
          contentType: e.contentType,
          size: e.size,
          sha256: await computeSha256(e.file),
        })),
      )
      const payload: CreateUploadSessionRequest = {
        comicId: uploadComicId.value,
        chapterId: uploadChapterId.value,
        replaceMediaId: replaceMediaId.value ?? undefined,
        files: manifests,
      }
      const session = await uploadStore.createSession(payload)
      activeSessionId.value = session.sessionId
      for (const entry of nextEntries) {
        void pump(entry.index, session.chunkSize)
      }
    } catch (err: unknown) {
      const message = sanitizeErrorMessage(toErrorMessage(err, '创建上传会话失败'))
      queueError.value = message
      for (const entry of nextEntries) {
        updateEntry(entry.index, { status: 'failed', error: message })
      }
    }
  }

  // ======================== 暂停 / 继续 / 取消 / 重试 ========================

  function pauseEntry(index: number): void {
    const entry = entryAt(index)
    if (entry && entry.status === 'uploading') {
      updateEntry(index, { status: 'paused' })
    }
  }

  function resumeEntry(index: number): void {
    const entry = entryAt(index)
    if (entry && entry.status === 'paused' && activeSessionId.value !== null) {
      updateEntry(index, { status: 'uploading', error: null })
      const chunkSize = uploadStore.session?.chunkSize ?? 1024 * 1024
      void pump(index, chunkSize)
    }
  }

  function retryEntry(index: number): void {
    const entry = entryAt(index)
    if (entry && entry.status === 'failed' && activeSessionId.value !== null) {
      updateEntry(index, { status: 'uploading', error: null })
      const chunkSize = uploadStore.session?.chunkSize ?? 1024 * 1024
      void pump(index, chunkSize)
    }
  }

  /** 取消整个会话：所有未完成条目进入已取消 */
  async function cancelAll(): Promise<void> {
    const sessionId = activeSessionId.value
    const cancelledIndexes = entries.value
      .filter((e) => e.status === 'uploading' || e.status === 'queued' || e.status === 'paused')
      .map((e) => e.index)
    for (const index of cancelledIndexes) {
      updateEntry(index, { status: 'cancelled' })
    }
    if (sessionId !== null) {
      try {
        await uploadStore.cancel(sessionId)
      } catch (err: unknown) {
        queueError.value = sanitizeErrorMessage(toErrorMessage(err, '取消上传失败'))
      }
      activeSessionId.value = null
    }
  }

  /** 提交会话（全部完成后由视图层调用一次） */
  async function completeUpload(): Promise<void> {
    const sessionId = activeSessionId.value
    if (sessionId === null || completing.value) return
    completing.value = true
    queueError.value = null
    try {
      await uploadStore.complete(sessionId)
      activeSessionId.value = null
      replaceMediaId.value = null
    } catch (err: unknown) {
      queueError.value = sanitizeErrorMessage(toErrorMessage(err, '提交上传失败'))
      throw err
    } finally {
      completing.value = false
    }
  }

  function clear(): void {
    entries.value = []
    activeSessionId.value = null
    replaceMediaId.value = null
    completing.value = false
    queueError.value = null
  }

  return {
    entries,
    replaceMediaId,
    activeSessionId,
    completing,
    queueError,
    busy,
    canComplete,
    overallPercent,
    setTarget,
    setReplaceTarget,
    addFiles,
    pauseEntry,
    resumeEntry,
    retryEntry,
    cancelAll,
    completeUpload,
    clear,
  }
})
