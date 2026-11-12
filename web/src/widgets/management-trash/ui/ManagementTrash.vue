<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { comicApi } from '@/entities/comic'
import type { TrashEntry } from '@/entities/trash'
import { useSelection } from '@/shared/lib/selection'
import { ManagementIcon, ManagementImage, formatDateFull } from '@/shared/ui/management'
import { ManagementPagedFeed } from '@/shared/ui/pagination'

const props = defineProps<{
  entries: TrashEntry[]
  writable: boolean
  loading: boolean
  loadingMore: boolean
  total: number
  page: number
  keyword: string
  appendError: string
}>()
const emit = defineEmits<{
  audit: [id: string]
  restore: [ids: string[]]
  purge: [ids: string[]]
  'page-change': [page: number]
  'load-more': []
  retry: []
}>()

type TargetType = TrashEntry['target']['targetType']
const labels: Record<TargetType, string> = { comic: '漫画', chapter: '章节', media: '媒体' }
const kindIcons: Record<TargetType, 'book' | 'layers' | 'image'> = {
  comic: 'book',
  chapter: 'layers',
  media: 'image',
}

const entryList = computed(() => props.entries)
const selection = useSelection(entryList)
const selected = selection.selected,
  allSelected = selection.allSelected,
  partial = selection.partial
const focused = ref('')
const current = computed(() => props.entries.find((entry) => entry.id === focused.value))
const panel = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const narrow = ref(false)
let media: MediaQueryList | null = null
let focusReturn: HTMLElement | null = null
let locked = false
let previousOverflow = ''

type LocationState = { status: 'idle' | 'loading' | 'ready' | 'error'; path: string }
const location = ref<LocationState>({ status: 'idle', path: '' })
const locationCache = new Map<string, string>()
let locationGeneration = 0

function catalogTrail(
  id: string | null | undefined,
  catalogs: { id: string; parentId: string | null; title: string }[],
) {
  const parts: string[] = []
  const seen = new Set<string>()
  let cursor = id ?? ''
  while (cursor && !seen.has(cursor)) {
    seen.add(cursor)
    const item = catalogs.find((catalog) => catalog.id === cursor)
    if (!item) break
    parts.unshift(item.title)
    cursor = item.parentId ?? ''
  }
  return parts
}
async function loadLocation(entry: TrashEntry) {
  const generation = ++locationGeneration
  const target = entry.target
  if (target.targetType === 'comic') {
    location.value = { status: 'ready', path: '漫画库' }
    return
  }
  const cached = locationCache.get(target.comicId)
  if (cached) {
    location.value = { status: 'ready', path: cached }
    return
  }
  location.value = { status: 'loading', path: '' }
  try {
    const detail = await comicApi.detail(target.comicId)
    if (generation !== locationGeneration) return
    const chapter = detail.chapters.find((item) => item.id === target.chapterId)
    const parts = [detail.title, ...catalogTrail(chapter?.catalogId, detail.catalogs)]
    if (target.targetType === 'media' && chapter) parts.push(chapter.title)
    const path = parts.filter(Boolean).join(' / ')
    locationCache.set(target.comicId, path)
    location.value = { status: 'ready', path }
  } catch {
    if (generation === locationGeneration) location.value = { status: 'error', path: '' }
  }
}
async function inspect(entry: TrashEntry) {
  if (focused.value === entry.id) return
  if (!focused.value) focusReturn = document.activeElement instanceof HTMLElement ? document.activeElement : null
  focused.value = entry.id
  void loadLocation(entry)
  if (narrow.value) {
    await nextTick()
    closeButton.value?.focus()
  }
}
function close() {
  const returnTo = focusReturn
  ++locationGeneration
  focused.value = ''
  location.value = { status: 'idle', path: '' }
  if (narrow.value && returnTo?.isConnected) void nextTick(() => returnTo.focus())
}
function lockScroll() {
  if (locked) return
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  locked = true
}
function unlockScroll() {
  if (!locked) return
  document.body.style.overflow = previousOverflow
  locked = false
}
function syncScrollLock() {
  if (narrow.value && current.value) lockScroll()
  else unlockScroll()
}
function onKeydown(event: KeyboardEvent) {
  if (!current.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (!narrow.value || event.key !== 'Tab' || !panel.value) return
  const items = [
    ...panel.value.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    ),
  ].filter((element) => !element.hasAttribute('disabled'))
  if (!items.length) return
  const first = items[0]!
  const last = items[items.length - 1]!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
function onMediaChange(event: MediaQueryListEvent) {
  narrow.value = event.matches
}

watch(
  () => props.entries,
  (entries) => {
    selection.retain(entries.map((entry) => entry.id))
    if (focused.value && !entries.some((entry) => entry.id === focused.value)) close()
  },
)
watch([narrow, () => !!current.value], syncScrollLock, { flush: 'post' })
onMounted(() => {
  media = window.matchMedia('(max-width: 1050px)')
  narrow.value = media.matches
  media.addEventListener('change', onMediaChange)
  window.addEventListener('keydown', onKeydown)
  syncScrollLock()
})
onBeforeUnmount(() => {
  media?.removeEventListener('change', onMediaChange)
  window.removeEventListener('keydown', onKeydown)
  unlockScroll()
})
</script>

<template>
  <p v-if="loading && !entries.length" class="mw-trash-loading" role="status">
    <ManagementIcon name="loading" class="mw-spin" :size="16" />正在读取回收内容…
  </p>
  <div v-else-if="!entries.length" class="mw-empty mw-trash-empty">
    <ManagementIcon name="trash" :size="40" />
    <h2>{{ keyword ? '没有匹配的回收内容' : '回收站是空的' }}</h2>
    <p v-if="keyword">换一个关键词，或清除搜索后查看全部回收内容。</p>
    <p v-else>从漫画库移入回收站的漫画、章节和媒体会出现在这里。</p>
    <RouterLink v-if="!keyword" class="mw-button mw-trash-empty-link" to="/manage">去漫画管理</RouterLink>
  </div>
  <div v-else class="mw-trash-workspace" :class="{ 'has-detail': current }">
    <section class="mw-trash-list" :aria-busy="loading">
      <div class="mw-trash-list-head">
        <label class="mw-trash-select-all">
          <input
            type="checkbox"
            aria-label="选择已加载全部回收内容"
            :checked="allSelected"
            :indeterminate.prop="partial"
            :disabled="!writable"
            @change="selection.selectAll"
          />
          <span>全选已加载</span>
        </label>
        <span class="mw-trash-summary" aria-live="polite">
          {{ selected.length ? `已选 ${selected.length} 项` : `共 ${total} 项 · 最近回收在前` }}
        </span>
        <span v-if="loading" class="mw-trash-refreshing" role="status">正在刷新…</span>
        <div v-if="selected.length" class="mw-trash-batch">
          <button class="mw-button" :disabled="!writable" @click="emit('restore', [...selected])">恢复所选</button>
          <button class="mw-button mw-button-danger" :disabled="!writable" @click="emit('purge', [...selected])">
            核对永久清理
          </button>
          <button class="mw-trash-clear" type="button" @click="selection.clear">取消选择</button>
        </div>
      </div>
      <ManagementPagedFeed
        :items="entries"
        :page="page"
        :total="total"
        :loading="loading"
        :loading-more="loadingMore"
        :append-error="appendError"
        :disabled="!writable"
        label="回收站分页"
        @page-change="emit('page-change', $event)"
        @load-more="emit('load-more')"
        @retry="emit('retry')"
      >
        <template #default="{ items }">
          <article
            v-for="entry in items"
            :key="entry.id"
            class="mw-trash-row"
            :class="{ selected: selected.includes(entry.id), active: current?.id === entry.id }"
          >
            <label class="mw-trash-check">
              <input
                type="checkbox"
                :aria-label="`选择回收内容${entry.target.title}`"
                :checked="selected.includes(entry.id)"
                :disabled="!writable"
                @change="selection.toggle(entry.id)"
              />
            </label>
            <button class="mw-trash-thumb" :aria-label="`查看回收内容${entry.target.title}`" @click="inspect(entry)">
              <ManagementImage :src="entry.coverUrl ?? null" :alt="entry.target.title" />
            </button>
            <div class="mw-trash-copy">
              <div class="mw-trash-title-row">
                <span class="mw-trash-kind"
                  ><ManagementIcon :name="kindIcons[entry.target.targetType]" :size="12" />{{
                    labels[entry.target.targetType]
                  }}</span
                >
                <button class="mw-trash-title" :title="entry.target.title" @click="inspect(entry)">
                  {{ entry.target.title }}
                </button>
              </div>
              <p class="mw-trash-meta">
                回收于 {{ formatDateFull(entry.updatedAt) }}<span v-if="entry.origin === 'imported'"> · 历史导入</span>
              </p>
            </div>
            <button
              class="mw-button mw-trash-restore"
              :aria-label="`恢复${entry.target.title}`"
              :disabled="!writable"
              @click="emit('restore', [entry.id])"
            >
              <ManagementIcon name="restore" :size="14" /><span>恢复</span>
            </button>
          </article>
        </template>
      </ManagementPagedFeed>
    </section>
    <div v-if="current" class="mw-trash-scrim" aria-hidden="true" @click="close"></div>
    <aside
      v-if="current"
      ref="panel"
      class="mw-trash-detail"
      :role="narrow ? 'dialog' : undefined"
      :aria-modal="narrow ? 'true' : undefined"
      aria-label="回收内容详情"
    >
      <div class="mw-trash-detail-head">
        <h3>回收内容</h3>
        <button ref="closeButton" class="mw-icon-button" aria-label="关闭回收内容详情" @click="close">
          <ManagementIcon name="close" />
        </button>
      </div>
      <div v-if="current.coverUrl" class="mw-trash-preview">
        <ManagementImage :src="current.coverUrl" :alt="current.target.title" />
      </div>
      <div v-else class="mw-trash-preview is-empty">
        <ManagementIcon name="image" :size="22" /><span>没有可用的预览图</span>
      </div>
      <span class="mw-trash-kind"
        ><ManagementIcon :name="kindIcons[current.target.targetType]" :size="12" />{{
          labels[current.target.targetType]
        }}</span
      >
      <h2>{{ current.target.title }}</h2>
      <dl class="mw-trash-facts">
        <div>
          <dt>回收时间</dt>
          <dd>{{ formatDateFull(current.updatedAt) }}</dd>
        </div>
        <div>
          <dt>原位置</dt>
          <dd v-if="current.target.targetType === 'comic'">漫画库</dd>
          <dd v-else-if="location.status === 'ready'">{{ location.path }}</dd>
          <dd v-else-if="location.status === 'loading'" class="is-muted">正在读取原位置…</dd>
          <dd v-else-if="location.status === 'error'" class="is-muted">暂不可读取，作品可能已整体回收</dd>
          <dd v-else class="is-muted">—</dd>
        </div>
        <div>
          <dt>来源</dt>
          <dd>{{ current.origin === 'imported' ? '历史导入' : '本机回收' }}</dd>
        </div>
      </dl>
      <p class="mw-trash-note">恢复会提交独立任务；章节与媒体追加到原位置末尾。永久清理会删除文件，无法恢复。</p>
      <div class="mw-trash-detail-actions">
        <button class="mw-button" :disabled="!writable" @click="emit('restore', [current.id])">恢复此项</button>
        <button class="mw-button" :disabled="!writable" @click="emit('audit', current.id)">核对登记文件</button>
      </div>
      <div class="mw-trash-danger">
        <p>仅清理这一条记录与实际文件。</p>
        <button class="mw-button mw-button-danger" :disabled="!writable" @click="emit('purge', [current.id])">
          永久清理
        </button>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.mw-trash-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 40px 0;
  color: var(--atlas-muted);
  font-size: 12px;
}
.mw-trash-empty {
  padding: 80px 20px;
}
.mw-trash-empty-link {
  margin-top: 16px;
  text-decoration: none;
}
.mw-trash-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 24px;
  align-items: start;
}
.mw-trash-workspace.has-detail {
  grid-template-columns: minmax(0, 1fr) 340px;
}
.mw-trash-list {
  min-width: 0;
}
.mw-trash-list-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  min-height: 44px;
  padding: 0 2px 8px;
  border-bottom: 1px solid var(--atlas-line);
  color: var(--atlas-muted);
  font-size: 12px;
}
.mw-trash-select-all {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--atlas-text);
}
.mw-trash-summary {
  color: var(--atlas-muted);
  font-variant-numeric: tabular-nums;
}
.mw-trash-refreshing {
  color: var(--atlas-faint);
}
.mw-trash-batch {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-left: auto;
}
.mw-trash-batch .mw-button {
  min-height: 32px;
  padding: 5px 12px;
  font-size: 12px;
}
.mw-trash-clear {
  padding: 4px 2px;
  border: 0;
  background: transparent;
  color: var(--atlas-muted);
  font: inherit;
  font-size: 12px;
}
.mw-trash-clear:hover {
  color: var(--atlas-text);
}
.mw-trash-row {
  display: grid;
  grid-template-columns: 28px 44px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 11px 10px;
  margin: 0 -10px;
  border-bottom: 1px solid var(--atlas-line);
  border-radius: 8px;
}
.mw-trash-row:hover {
  background: var(--atlas-raised);
}
.mw-trash-row.selected {
  background: var(--atlas-selected);
}
.mw-trash-row.active {
  box-shadow: inset 3px 0 var(--atlas-accent);
}
.mw-trash-check {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 44px;
}
.mw-trash-check input {
  width: 16px;
  height: 16px;
  margin: 0;
  accent-color: var(--atlas-accent);
}
.mw-trash-thumb {
  display: grid;
  place-items: center;
  width: 44px;
  height: 58px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: var(--atlas-bg);
  overflow: hidden;
}
.mw-trash-thumb :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.mw-trash-thumb :deep(.mw-image-empty) {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
}
.mw-trash-copy {
  min-width: 0;
}
.mw-trash-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.mw-trash-kind {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
  padding: 1px 7px;
  border: 1px solid var(--atlas-line);
  border-radius: 999px;
  color: var(--atlas-muted);
  font-size: 11px;
  line-height: 1.6;
}
.mw-trash-title {
  display: -webkit-box;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--atlas-text);
  font: inherit;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.45;
  text-align: left;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.mw-trash-title:hover {
  color: var(--atlas-accent);
}
.mw-trash-meta {
  margin: 4px 0 0;
  color: var(--atlas-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.mw-trash-restore {
  min-height: 32px;
  padding: 5px 10px;
  border-color: transparent;
  background: transparent;
  color: var(--atlas-muted);
  font-size: 12px;
}
.mw-trash-restore:hover:not(:disabled) {
  border-color: var(--atlas-control-border);
  background: var(--atlas-raised);
  color: var(--atlas-text);
}
.mw-trash-scrim {
  display: none;
}
.mw-trash-detail {
  position: sticky;
  top: 20px;
  align-self: start;
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--atlas-line);
  border-radius: 10px;
  background: var(--atlas-surface);
}
.mw-trash-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
.mw-trash-detail-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}
.mw-trash-preview {
  display: grid;
  place-items: center;
  max-width: 100%;
  margin: 0 auto;
  border-radius: 8px;
}
.mw-trash-preview :deep(img) {
  width: auto;
  max-width: 100%;
  max-height: 250px;
  height: auto;
  border-radius: 8px;
  object-fit: contain;
}
.mw-trash-preview.is-empty {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  min-height: 120px;
  padding: 12px;
  background: var(--atlas-bg);
  color: var(--atlas-muted);
  font-size: 12px;
}
.mw-trash-detail > .mw-trash-kind {
  margin-top: 14px;
}
.mw-trash-detail h2 {
  margin: 8px 0 12px;
  font-size: 18px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}
.mw-trash-facts {
  margin: 0;
  padding: 10px 0;
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
}
.mw-trash-facts > div {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 10px;
  padding: 5px 0;
  font-size: 12px;
}
.mw-trash-facts dt {
  color: var(--atlas-muted);
}
.mw-trash-facts dd {
  min-width: 0;
  margin: 0;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.mw-trash-facts dd.is-muted {
  color: var(--atlas-faint);
}
.mw-trash-note {
  margin: 12px 0 0;
  color: var(--atlas-muted);
  font-size: 12px;
  line-height: 1.6;
}
.mw-trash-detail-actions {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}
.mw-trash-detail-actions .mw-button {
  width: 100%;
}
.mw-trash-danger {
  display: grid;
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--atlas-line);
}
.mw-trash-danger p {
  margin: 0;
  color: var(--atlas-faint);
  font-size: 11px;
}
.mw-trash-danger .mw-button {
  width: 100%;
}
@media (max-width: 1180px) {
  .mw-trash-workspace.has-detail {
    grid-template-columns: minmax(0, 1fr) 300px;
  }
}
@media (max-width: 1050px) {
  .mw-trash-workspace.has-detail {
    grid-template-columns: minmax(0, 1fr);
  }
  .mw-trash-scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 12;
    background: var(--atlas-backdrop);
  }
  .mw-trash-detail {
    position: fixed;
    inset: 0 0 auto auto;
    z-index: 13;
    width: min(380px, 100vw);
    height: 100dvh;
    overflow-y: auto;
    overscroll-behavior: contain;
    padding: 20px;
    border: 0;
    border-left: 1px solid var(--atlas-line);
    border-radius: 0;
    box-shadow: -18px 0 48px #000000aa;
  }
}
@media (max-width: 760px) {
  .mw-trash-row {
    grid-template-columns: 28px 40px minmax(0, 1fr) 40px;
    gap: 10px;
    padding: 11px 8px;
    margin: 0 -8px;
  }
  .mw-trash-thumb {
    width: 40px;
    height: 54px;
  }
  .mw-trash-restore {
    width: 40px;
    height: 40px;
    min-height: 40px;
    padding: 0;
  }
  .mw-trash-restore span {
    display: none;
  }
  .mw-trash-batch {
    width: 100%;
    margin-left: 0;
  }
}
@media (max-width: 560px) {
  .mw-trash-detail {
    width: 100vw;
  }
}
</style>
