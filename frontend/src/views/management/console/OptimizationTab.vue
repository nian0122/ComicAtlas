<template>
  <div class="optimization-tab" data-testid="opt-tab">
    <div class="level-switch" role="group" aria-label="目标层级">
      <button
        class="level-btn"
        :class="{ active: level === 'COMIC' }"
        data-testid="opt-level-comic"
        @click="setLevel('COMIC')"
      >
        漫画
      </button>
      <button
        class="level-btn"
        :class="{ active: level === 'CHAPTER' }"
        data-testid="opt-level-chapter"
        @click="setLevel('CHAPTER')"
      >
        章节
      </button>
      <button
        class="level-btn"
        :class="{ active: level === 'MEDIA' }"
        data-testid="opt-level-media"
        @click="setLevel('MEDIA')"
      >
        媒体
      </button>
    </div>

    <div class="picker-grid">
      <label class="picker-group">
        <span class="picker-label">漫画</span>
        <select
          class="picker-select"
          data-testid="opt-comic-select"
          :value="comicId ?? ''"
          :disabled="comicsLoading"
          @change="onComicChange"
        >
          <option value="" disabled>选择漫画</option>
          <option v-for="c in comics" :key="c.id" :value="c.id">{{ c.title }}</option>
        </select>
      </label>

      <label v-if="level !== 'COMIC'" class="picker-group">
        <span class="picker-label">章节</span>
        <select
          class="picker-select"
          data-testid="opt-chapter-select"
          :value="chapterId ?? ''"
          :disabled="chaptersLoading || comicId === null"
          @change="onChapterChange"
        >
          <option value="" disabled>选择章节</option>
          <option v-for="ch in chapters" :key="ch.chapterId" :value="ch.chapterId">
            {{ ch.title || `第 ${ch.chapterNo} 章` }}
          </option>
        </select>
      </label>

      <label v-if="level === 'MEDIA'" class="picker-group">
        <span class="picker-label">媒体</span>
        <select
          class="picker-select"
          data-testid="opt-media-select"
          :value="mediaId ?? ''"
          :disabled="mediaLoading || chapterId === null"
          @change="onMediaChange"
        >
          <option value="" disabled>选择媒体</option>
          <option v-for="m in mediaList" :key="m.id" :value="m.id">
            {{ mediaLabel(m) }}
          </option>
        </select>
      </label>
    </div>

    <p v-if="error" class="state error" data-testid="opt-error">{{ error }}</p>

    <div v-if="ops" class="ops-panel">
      <div class="ops-head">
        <h3 class="ops-title">操作</h3>
        <span class="ops-target">{{ targetText }}</span>
      </div>
      <div class="ops-grid">
        <button
          v-for="op in OPERATIONS"
          :key="op.name"
          class="op-btn"
          :class="op.className"
          :data-testid="`opt-op-${op.name}`"
          :disabled="!allowedStore.can(ops, op.name) || submitting"
          :title="blockedReasonOf(op.name)"
          @click="onSubmit(op)"
        >
          {{ op.label }}
        </button>
      </div>
      <p v-if="blockedNote" class="blocked-note">{{ blockedNote }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from '@/services/management/http'
import { adminApi } from '@/services/api'
import { taskApi } from '@/services/management/task'
import { useAllowedOperationsStore } from '@/stores/management/allowedOperations'
import { OperationName, TaskType } from '@/types/management/enums'
import type { OperationName as OperationNameType } from '@/types/management/enums'
import type { AllowedOperations, OperationTarget } from '@/types/management/operation'
import type { MediaItemInfo } from '@/types'
import type { ChapterStorageItem } from '@/types'
import type { ComicListVO } from '@/types'

type Level = 'COMIC' | 'CHAPTER' | 'MEDIA'

interface OperationDef {
  readonly name: OperationNameType
  readonly label: string
  readonly className: string
}

const OPERATIONS: readonly OperationDef[] = [
  { name: OperationName.LQ_GENERATE, label: '生成 LQ', className: 'is-secondary' },
  { name: OperationName.LQ_REGENERATE, label: '重新生成 LQ', className: 'is-secondary' },
  { name: OperationName.HQ_DELETE, label: '删除 HQ', className: 'is-warning' },
  { name: OperationName.TRANSCODE, label: '视频转码', className: 'is-secondary' },
  { name: OperationName.METADATA_REFRESH, label: '刷新元数据', className: 'is-secondary' },
]

const allowedStore = useAllowedOperationsStore()

const level = ref<Level>('COMIC')
const comics = ref<readonly ComicListVO[]>([])
const chapters = ref<readonly ChapterStorageItem[]>([])
const mediaList = ref<readonly MediaItemInfo[]>([])
const comicId = ref<number | null>(null)
const chapterId = ref<number | null>(null)
const mediaId = ref<number | null>(null)
const comicsLoading = ref(false)
const chaptersLoading = ref(false)
const mediaLoading = ref(false)
const ops = ref<AllowedOperations | null>(null)
const error = ref('')
const submitting = ref(false)

const targetText = computed<string>(() => {
  const comic = comics.value.find((c) => c.id === comicId.value)
  const chapter = chapters.value.find((c) => c.chapterId === chapterId.value)
  const media = mediaList.value.find((m) => m.id === mediaId.value)
  switch (level.value) {
    case 'COMIC':
      return comic ? `漫画：${comic.title}` : '未选择漫画'
    case 'CHAPTER':
      return comic && chapter ? `章节：${chapter.title || `第 ${chapter.chapterNo} 章`}` : '未选择章节'
    case 'MEDIA':
      return media ? `媒体：#${media.id} ${mediaLabel(media)}` : '未选择媒体'
    default:
      return ''
  }
})

const blockedNote = computed<string | null>(() => {
  if (!ops.value) return null
  const reasons: string[] = []
  for (const op of OPERATIONS) {
    if (!allowedStore.can(ops.value, op.name)) {
      const reason = allowedStore.blockedReason(ops.value, op.name)
      if (reason) reasons.push(`${op.label}：${reason}`)
    }
  }
  return reasons.length > 0 ? reasons.join('；') : null
})

function blockedReasonOf(name: OperationNameType): string | undefined {
  if (!ops.value) return undefined
  if (allowedStore.can(ops.value, name)) return '允许执行'
  return allowedStore.blockedReason(ops.value, name) ?? '当前状态不允许'
}

function mediaLabel(m: MediaItemInfo): string {
  const type = m.mediaType === 'VIDEO' ? '视频' : '图片'
  return `#${m.id} P${m.pageNumber} ${type}${m.container ? ` · ${m.container}` : ''}`
}

async function setLevel(next: Level): Promise<void> {
  level.value = next
  comicId.value = null
  chapterId.value = null
  mediaId.value = null
  ops.value = null
  await loadComics()
}

async function loadComics(): Promise<void> {
  comicsLoading.value = true
  error.value = ''
  try {
    const res = await request<{ records: readonly ComicListVO[] }>({
      method: 'GET',
      url: '/comics',
      params: { status: 'READY', page: 1, size: 200 },
    })
    comics.value = res.records ?? []
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '加载漫画列表失败'
  } finally {
    comicsLoading.value = false
  }
}

async function onComicChange(event: Event): Promise<void> {
  const value = (event.target as HTMLSelectElement).value
  comicId.value = value === '' ? null : Number(value)
  chapterId.value = null
  mediaId.value = null
  ops.value = null
  if (comicId.value === null) return
  if (level.value === 'CHAPTER' || level.value === 'MEDIA') {
    await loadChapters(comicId.value)
  }
  await loadOps(currentTarget())
}

async function loadChapters(comic: number): Promise<void> {
  chaptersLoading.value = true
  error.value = ''
  try {
    const res = await adminApi.storageChapters(comic)
    chapters.value = (res.data as readonly ChapterStorageItem[]) ?? []
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '加载章节失败'
  } finally {
    chaptersLoading.value = false
  }
}

async function onChapterChange(event: Event): Promise<void> {
  const value = (event.target as HTMLSelectElement).value
  chapterId.value = value === '' ? null : Number(value)
  mediaId.value = null
  ops.value = null
  if (chapterId.value === null || comicId.value === null) return
  if (level.value === 'MEDIA') {
    await loadMedia(comicId.value, chapterId.value)
  }
  await loadOps(currentTarget())
}

async function loadMedia(comic: number, chapter: number): Promise<void> {
  mediaLoading.value = true
  error.value = ''
  try {
    const raw = await request<{ pages: readonly MediaItemInfo[] }>({
      method: 'GET',
      url: `/comics/${comic}/chapters/${chapter}/pages`,
    })
    mediaList.value = raw.pages ?? []
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '加载媒体失败'
  } finally {
    mediaLoading.value = false
  }
}

async function onMediaChange(event: Event): Promise<void> {
  const value = (event.target as HTMLSelectElement).value
  mediaId.value = value === '' ? null : Number(value)
  ops.value = null
  if (mediaId.value === null) return
  await loadOps(currentTarget())
}

function currentTarget(): OperationTarget | null {
  switch (level.value) {
    case 'COMIC':
      return comicId.value === null ? null : { targetType: 'COMIC', targetId: comicId.value }
    case 'CHAPTER':
      return chapterId.value === null ? null : { targetType: 'CHAPTER', targetId: chapterId.value }
    case 'MEDIA':
      return mediaId.value === null ? null : { targetType: 'MEDIA', targetId: mediaId.value }
    default:
      return null
  }
}

async function loadOps(target: OperationTarget | null): Promise<void> {
  ops.value = null
  if (!target) return
  try {
    ops.value = await allowedStore.fetchAllowed(target)
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '加载操作权限失败'
  }
}

async function onSubmit(op: OperationDef): Promise<void> {
  const target = currentTarget()
  if (!target || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const message = await submitOperation(op.name, target)
    ElMessage.success(message)
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '操作提交失败'
  } finally {
    submitting.value = false
  }
}

async function submitOperation(
  op: OperationNameType,
  target: OperationTarget,
): Promise<string> {
  switch (target.targetType) {
    case 'COMIC':
      return submitComicOperation(op, target.targetId)
    case 'CHAPTER':
      return submitChapterOperation(op, target.targetId)
    case 'MEDIA':
      return submitMediaOperation(op, target.targetId)
    default:
      return assertNever(target)
  }
}

async function submitComicOperation(op: OperationNameType, comic: number): Promise<string> {
  switch (op) {
    case OperationName.LQ_GENERATE:
      await request({ method: 'POST', url: `/comics/${comic}/lq` })
      return 'LQ 生成任务已提交'
    case OperationName.LQ_REGENERATE:
      await request({ method: 'POST', url: `/comics/${comic}/lq`, params: { regenerate: true } })
      return 'LQ 重新生成任务已提交'
    case OperationName.HQ_DELETE:
      await request({ method: 'POST', url: `/comics/${comic}/delete-hq` })
      return 'HQ 删除任务已提交'
    case OperationName.TRANSCODE:
      await request({ method: 'POST', url: `/admin/storage/comics/${comic}/transcode-videos` })
      return '视频转码任务已提交'
    case OperationName.METADATA_REFRESH:
      await request({ method: 'POST', url: `/admin/comics/${comic}/refresh-metadata` })
      return '元数据刷新已提交'
    default:
      throw new Error('不支持的操作: ' + op)
  }
}

async function submitChapterOperation(op: OperationNameType, chapter: number): Promise<string> {
  switch (op) {
    case OperationName.LQ_GENERATE:
      await request({ method: 'POST', url: `/chapters/${chapter}/lq` })
      return '章节 LQ 生成任务已提交'
    case OperationName.LQ_REGENERATE:
      await request({ method: 'POST', url: `/chapters/${chapter}/lq`, params: { regenerate: true } })
      return '章节 LQ 重新生成任务已提交'
    case OperationName.HQ_DELETE:
      await request({ method: 'POST', url: `/chapters/${chapter}/delete-hq` })
      return '章节 HQ 删除任务已提交'
    case OperationName.TRANSCODE:
      await taskApi.create({
        taskType: TaskType.TRANSCODE,
        operation: 'TRANSCODE',
        targetType: 'CHAPTER',
        targets: [{ targetType: 'CHAPTER', targetId: chapter }],
      })
      return '章节转码任务已提交'
    default:
      throw new Error('不支持的操作: ' + op)
  }
}

async function submitMediaOperation(op: OperationNameType, media: number): Promise<string> {
  switch (op) {
    case OperationName.TRANSCODE:
      await taskApi.create({
        taskType: TaskType.TRANSCODE,
        operation: 'TRANSCODE',
        targetType: 'MEDIA',
        targets: [{ targetType: 'MEDIA', targetId: media }],
      })
      return '媒体转码任务已提交'
    default:
      throw new Error('不支持的操作: ' + op)
  }
}

function assertNever(value: never): never {
  throw new Error(`不支持的操作: ${String(value)}`)
}

onMounted(loadComics)
</script>

<style scoped>
.optimization-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  min-width: 0;
}

.level-switch {
  display: inline-flex;
  gap: var(--space-1);
  padding: var(--space-1);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  width: fit-content;
}

.level-btn {
  min-height: 36px;
  padding-inline: var(--space-4);
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  font-size: var(--text-sm);
  font-weight: 600;
  font-family: var(--font-ui);
  cursor: pointer;
  transition:
    background-color var(--transition-fast),
    color var(--transition-fast);
}

.level-btn:hover {
  color: var(--text-primary);
}

.level-btn.active {
  background: var(--accent-bg);
  color: var(--accent);
}

.level-btn:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}

.picker-grid {
  display: flex;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.picker-group {
  display: grid;
  gap: var(--space-1);
  min-width: 220px;
}

.picker-label {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-muted);
  letter-spacing: 0.04em;
}

.picker-select {
  min-height: var(--control-min-size);
  padding-inline: var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-family: var(--font-ui);
}

.picker-select:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}

.state.error {
  padding: var(--space-3);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: rgb(240 107 112 / 10%);
  color: var(--danger);
  font-size: var(--text-sm);
}

.ops-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
}

.ops-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.ops-title {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 700;
  color: var(--text-primary);
}

.ops-target {
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.ops-grid {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.op-btn {
  min-height: var(--control-min-size);
  padding-inline: var(--space-4);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  font-family: var(--font-ui);
  cursor: pointer;
  transition:
    background-color var(--transition-fast),
    color var(--transition-fast),
    border-color var(--transition-fast);
}

.op-btn:hover:not(:disabled) {
  background: var(--surface-highlight);
  color: var(--text-primary);
}

.op-btn:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}

.op-btn.is-warning:hover:not(:disabled) {
  border-color: var(--warning);
  color: var(--warning);
}

.op-btn.is-secondary:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.op-btn:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}

.blocked-note {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--warning);
}
</style>
