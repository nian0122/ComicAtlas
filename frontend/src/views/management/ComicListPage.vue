<template>
  <div class="manage-comic-list-page" data-testid="comic-list-page">
    <header class="page-header">
      <div class="header-left">
        <p class="page-eyebrow">CATALOG / CONTROL</p>
        <h1 class="page-title">漫画管理</h1>
        <p class="page-subtitle">共 {{ store.total }} 部漫画</p>
      </div>
      <div class="header-actions">
        <button class="action-btn action-btn--primary" type="button" data-testid="list-import" @click="router.push('/manage/import')">
          + 导入漫画
        </button>
      </div>
    </header>

    <div class="filter-toolbar">
      <el-input
        v-model="filters.keyword"
        placeholder="搜索标题/作者/标签"
        clearable
        class="filter-input"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      />
      <el-select v-model="filters.category" placeholder="分类" clearable class="filter-select" @change="applyFilters">
        <el-option label="未分类" value="_NONE" />
        <el-option
          v-for="c in categoryStore.list"
          :key="c.id"
          :label="c.name"
          :value="c.name"
        />
      </el-select>
      <el-select v-model="filters.status" placeholder="生命周期" clearable class="filter-select" @change="applyFilters">
        <el-option v-for="s in LIFECYCLE_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select
        v-model="filters.tags"
        multiple
        collapse-tags
        collapse-tags-tooltip
        placeholder="标签"
        clearable
        class="filter-select--wide"
        @change="applyFilters"
      >
        <el-option
          v-for="t in tagStore.list"
          :key="t.id"
          :label="t.name"
          :value="t.name"
        />
        <el-option label="无标签" value="_NONE" />
      </el-select>
      <el-select v-if="filters.tags.length > 1" v-model="filters.tagMode" class="filter-select--mini" @change="applyFilters">
        <el-option label="任一" value="OR" />
        <el-option label="全部" value="AND" />
      </el-select>
      <el-select v-model="filters.sort" placeholder="排序" class="filter-select" @change="applyFilters">
        <el-option v-for="s in SORT_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-button text @click="resetFilters">重置</el-button>
    </div>

    <!-- 批量选择栏（跨页保持 + 筛选全部） -->
    <div
      v-if="selection.hasSelection || selection.mode === 'FILTER'"
      class="batch-bar"
      :data-mode="selection.mode"
      data-testid="batch-bar"
    >
      <div class="batch-bar-info">
        <el-icon :size="16"><Check /></el-icon>
        <span data-testid="batch-count">
          已选 <strong>{{ batchCount }}</strong> 项
        </span>
        <span v-if="selection.mode === 'FILTER' && selection.excludedIds.length > 0" class="batch-excluded" data-testid="batch-excluded-count">
          排除 {{ selection.excludedIds.length }} 项
        </span>
      </div>
      <div class="batch-bar-actions">
        <button class="batch-link" type="button" data-testid="select-current-page" @click="selectCurrentPage">
          全选当前页
        </button>
        <button class="batch-link" type="button" data-testid="select-all-filtered" @click="selectAllFiltered">
          选择筛选全部
        </button>
        <button class="batch-link batch-link--danger" type="button" @click="clearSelection">取消选择</button>
      </div>
    </div>

    <div v-if="store.loading && store.list.length === 0" class="state loading" data-testid="list-loading">
      <div class="action-btn-spinner" aria-hidden="true" />
      <span>加载中...</span>
    </div>

    <div v-else-if="store.error" class="state error" data-testid="list-error">
      <el-icon :size="32"><WarningFilled /></el-icon>
      <span>{{ store.error }}</span>
      <button class="action-btn action-btn--secondary" type="button" data-testid="list-retry" @click="store.fetchList()">重试</button>
    </div>

    <div v-else-if="store.list.length === 0" class="state empty" data-testid="list-empty">
      <el-icon :size="48"><PictureFilled /></el-icon>
      <span>暂无漫画</span>
      <button class="action-btn action-btn--primary" type="button" data-testid="list-empty-import" @click="router.push('/manage/import')">导入漫画</button>
    </div>

    <section v-else class="comic-table-section">
      <div class="comic-grid">
        <div
          v-for="comic in store.list"
          :key="comic.id"
          class="comic-row"
          :data-testid="`comic-row-${comic.id}`"
          @click="goWorkspace(comic.id)"
        >
          <label class="comic-checkbox" @click.stop>
            <input
              type="checkbox"
              class="checkbox-input"
              :checked="isSelected(comic.id)"
              :data-testid="`comic-select-${comic.id}`"
              @change="() => toggleSelect(comic.id)"
            />
          </label>
          <div class="comic-cover">
            <img
              v-if="comic.coverUrl"
              :src="comic.coverUrl"
              alt=""
              @error="hideBrokenImage"
            >
          </div>
          <div class="comic-info">
            <h3 class="comic-title">{{ comic.title }}</h3>
            <p class="comic-meta">
              <span>{{ comic.author || '未知作者' }}</span>
              <span>· {{ comic.pageCount }} 页</span>
              <span
                class="status-tag"
                :class="lifecycleTone(comic)"
                :data-testid="`comic-lifecycle-${comic.id}`"
                role="status"
              >
                {{ lifecycleLabel(comic) }}
              </span>
            </p>
            <div v-if="comic.activeTask" class="comic-task" :data-testid="`comic-active-task-${comic.id}`">
              <span class="task-chip">{{ comic.activeTask.taskType.value }}</span>
              <span class="task-pct">{{ comic.activeTask.progress }}%</span>
            </div>
          </div>
          <div class="comic-actions">
            <button
              v-for="action in ACTION_DEFS"
              :key="action.op"
              class="action-btn"
              :class="action.variant"
              :disabled="!comic.allowedOperations.allowed.includes(action.op)"
              :data-testid="`comic-action-${comic.id}-${action.op}`"
              @click.stop="runAction(comic, action.op)"
            >
              {{ action.label }}
            </button>
            <span
              v-for="action in blockedActions(comic)"
              :key="`blocked-${action.op}`"
              class="blocked-reason"
              :data-testid="`comic-blocked-${comic.id}-${action.op}`"
            >
              {{ action.reason }}
            </span>
          </div>
          <button
            v-if="selection.mode === 'FILTER'"
            class="exclude-btn"
            type="button"
            :data-testid="`comic-exclude-${comic.id}`"
            @click.stop="toggleSelect(comic.id)"
          >
            排除
          </button>
        </div>
      </div>

      <div class="pagination-wrapper">
        <el-pagination
          :current-page="store.page"
          :page-size="24"
          :total="store.total"
          layout="prev, pager, next"
          background
          @current-change="onPageChange"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, onMounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Check, PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import { useComicListStore } from '@/stores/management/comicList'
import { useBatchSelectionStore } from '@/stores/management/selection'
import { useCategoryStore } from '@/stores/management/category'
import { useTagStore } from '@/stores/tag-store'
import { ComicLifecycleStatus, OperationName } from '@/types/management/enums'
import type { OperationName as OperationNameType } from '@/types/management/enums'
import type { ComicListEntry } from '@/types/management/comic'
import type { ComicListQuery } from '@/types'
import { lqApi, hqApi } from '@/services/api'
import { ElMessage } from 'element-plus'

const router = useRouter()
const store = useComicListStore()
const selection = useBatchSelectionStore()
const categoryStore = useCategoryStore()
const tagStore = useTagStore()

function hideBrokenImage(event: Event): void {
  const image = event.currentTarget as HTMLImageElement
  image.hidden = true
}

const LIFECYCLE_LABELS: Readonly<Record<string, string>> = {
  DRAFT: '草稿',
  IMPORTING: '导入中',
  IMPORT_FAILED: '导入失败',
  READY: '已就绪',
  RECOVERY_REQUIRED: '待恢复',
  DELETING: '删除中',
  TRASHING: '回收中',
  TRASHED: '已回收',
  RESTORING: '恢复中',
  PURGING: '清除中',
  DELETED: '已删除',
}

const LIFECYCLE_TONES: Readonly<Record<string, string>> = {
  DRAFT: 'status-tag--neutral',
  IMPORTING: 'status-tag--warning',
  IMPORT_FAILED: 'status-tag--danger',
  READY: 'status-tag--success',
  RECOVERY_REQUIRED: 'status-tag--warning',
  DELETING: 'status-tag--danger',
  TRASHING: 'status-tag--warning',
  TRASHED: 'status-tag--neutral',
  RESTORING: 'status-tag--warning',
  PURGING: 'status-tag--danger',
  DELETED: 'status-tag--neutral',
}

const LIFECYCLE_OPTIONS = [
  { label: '已就绪', value: ComicLifecycleStatus.READY },
  { label: '导入中', value: ComicLifecycleStatus.IMPORTING },
  { label: '导入失败', value: ComicLifecycleStatus.IMPORT_FAILED },
  { label: '草稿', value: ComicLifecycleStatus.DRAFT },
  { label: '待恢复', value: ComicLifecycleStatus.RECOVERY_REQUIRED },
  { label: '已回收', value: ComicLifecycleStatus.TRASHED },
]

const SORT_OPTIONS = [
  { label: '创建时间', value: 'createdAt' },
  { label: '更新时间', value: 'updatedAt' },
  { label: '标题', value: 'title' },
  { label: '页数', value: 'pageCount' },
  { label: '上次阅读', value: 'lastReadTime' },
]

const filters = reactive({
  keyword: '',
  category: '',
  status: '',
  tags: [] as string[],
  tagMode: 'OR' as 'AND' | 'OR',
  sort: 'createdAt' as NonNullable<ComicListQuery['sort']>,
})

const batchCount = computed(() => {
  if (selection.mode === 'FILTER') {
    return Math.max(0, store.total - selection.excludedIds.length)
  }
  return selection.ids.length
})

function isSelected(id: number): boolean {
  if (selection.mode === 'FILTER') {
    return !selection.excludedIds.includes(id)
  }
  return selection.ids.includes(id)
}

function toggleSelect(id: number): void {
  if (selection.mode === 'FILTER') {
    if (selection.excludedIds.includes(id)) {
      const next = selection.excludedIds.filter((ex) => ex !== id)
      selection.setFilter(store.filterQuery, next)
    } else {
      selection.addExcluded(id)
    }
    return
  }
  selection.toggle(id)
}

function selectCurrentPage(): void {
  store.selectCurrentPage()
}

function selectAllFiltered(): void {
  store.selectAllFiltered()
}

function clearSelection(): void {
  store.clearSelection()
}

function lifecycleLabel(comic: ComicListEntry): string {
  if (comic.lifecycle.kind === 'known') {
    return LIFECYCLE_LABELS[comic.lifecycle.value] ?? comic.lifecycle.value
  }
  return `未知状态 (${comic.lifecycle.value})`
}

function lifecycleTone(comic: ComicListEntry): string {
  if (comic.lifecycle.kind === 'known') {
    return LIFECYCLE_TONES[comic.lifecycle.value] ?? 'status-tag--neutral'
  }
  return 'status-tag--neutral'
}

/** 允许操作 → 列表可见动作定义（顺序稳定） */
const ACTION_DEFS: readonly { readonly op: OperationNameType; readonly label: string; readonly variant: string }[] = [
  { op: OperationName.EDIT, label: '编辑', variant: 'action-btn--secondary' },
  { op: OperationName.LQ_GENERATE, label: '生成LQ', variant: 'action-btn--secondary' },
  { op: OperationName.HQ_DELETE, label: '删除HQ', variant: 'action-btn--danger-ghost' },
  { op: OperationName.DELETE, label: '删除', variant: 'action-btn--danger-ghost' },
  { op: OperationName.RECOVER, label: '恢复', variant: 'action-btn--secondary' },
  { op: OperationName.PURGE, label: '彻底删除', variant: 'action-btn--danger-filled' },
]

/** 被阻塞的动作（显示原因） */
function blockedActions(comic: ComicListEntry): readonly { readonly op: string; readonly reason: string }[] {
  return ACTION_DEFS.flatMap((def) => {
    if (comic.allowedOperations.allowed.includes(def.op)) return []
    const reason =
      comic.allowedOperations.blockedReasons[def.op] ??
      comic.allowedOperations.blockedReasons['*'] ??
      '当前状态不允许'
    return [{ op: def.op, reason }]
  })
}

async function runAction(comic: ComicListEntry, op: OperationNameType): Promise<void> {
  if (op === OperationName.EDIT) {
    router.push(`/manage/comics/${comic.id}`)
    return
  }
  try {
    if (op === OperationName.LQ_GENERATE) {
      await lqApi.generateComic(comic.id)
    } else if (op === OperationName.HQ_DELETE) {
      await hqApi.deleteComic(comic.id)
    } else if (op === OperationName.DELETE) {
      router.push(`/manage/comics/${comic.id}?tab=danger`)
      return
    } else if (op === OperationName.RECOVER || op === OperationName.PURGE) {
      router.push(`/manage/comics/${comic.id}?tab=danger`)
      return
    }
    ElMessage.success('操作已提交')
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '操作失败')
  }
}

function goWorkspace(id: number): void {
  router.push(`/manage/comics/${id}`)
}

watch(() => filters.tags, (val) => {
  if (val.includes('_NONE') && val.length > 1) {
    nextTick(() => {
      filters.tags = ['_NONE']
    })
  }
}, { deep: true })

function applyFilters(): void {
  void store.search({
    keyword: filters.keyword || undefined,
    category: filters.category || undefined,
    status: filters.status || undefined,
    tags: filters.tags.length > 0 ? filters.tags : undefined,
    tagMode: filters.tagMode,
    sort: filters.sort,
  })
}

function resetFilters(): void {
  filters.keyword = ''
  filters.category = ''
  filters.status = ''
  filters.tags = []
  filters.tagMode = 'OR'
  filters.sort = 'createdAt'
  store.reset()
  void store.fetchList()
}

function onPageChange(page: number): void {
  void store.goToPage(page)
}

onMounted(() => {
  categoryStore.fetchList()
  tagStore.fetchList()
  void store.fetchList()
})
</script>

<style scoped>
.manage-comic-list-page {
  max-width: 1120px;
  margin: 0 auto;
  min-width: 0;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--space-6);
  gap: var(--space-4);
  flex-wrap: wrap;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.page-eyebrow {
  margin: 0;
  color: var(--accent);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.page-title {
  font-size: var(--text-page);
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.page-subtitle {
  font-size: var(--text-sm);
  color: var(--text-secondary);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: var(--space-2);
}

.filter-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
}

.filter-input { width: 200px; }
.filter-select { width: 120px; }
.filter-select--wide { width: 180px; }
.filter-select--mini { width: 90px; }

.batch-bar {
  margin-bottom: var(--space-4);
}

.batch-excluded {
  color: var(--warning);
  font-size: var(--text-xs);
}

.comic-grid {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.comic-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-4);
  min-height: 76px;
  padding: var(--space-3) var(--space-4);
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.comic-row:hover {
  background: var(--bg-surface);
  box-shadow: inset 2px 0 var(--accent);
}

.comic-cover {
  position: relative;
  width: 40px;
  height: 60px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--bg-secondary);
}

.comic-cover::before {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  content: "CA";
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 800;
}

.comic-cover img {
  position: relative;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.comic-cover img[hidden] {
  display: none;
}

.comic-info {
  flex: 1;
  min-width: 0;
}

.comic-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 var(--space-1);
  display: -webkit-box;
  overflow: hidden;
  line-break: strict;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.comic-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-secondary);
  margin: 0;
}

.comic-meta .status-tag {
  min-height: 20px;
  padding: 0 8px;
  font-size: 10px;
}

.comic-task {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-1);
}

.task-chip {
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--accent-bg);
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 700;
}

.task-pct {
  font-size: var(--text-xs);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--warning);
}

.comic-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
  flex-wrap: wrap;
  max-width: 340px;
}

.comic-actions .action-btn {
  min-height: 32px;
  padding: 4px 10px;
  font-size: var(--text-xs);
}

.blocked-reason {
  display: block;
  width: 100%;
  font-size: 10px;
  color: var(--warning);
  line-break: strict;
  overflow-wrap: anywhere;
}

.comic-checkbox {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  cursor: pointer;
}

.checkbox-input {
  width: 18px;
  height: 18px;
  accent-color: var(--accent);
  cursor: pointer;
}

.exclude-btn {
  flex-shrink: 0;
  min-height: 32px;
  padding: 4px 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  color: var(--text-secondary);
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.exclude-btn:hover {
  border-color: var(--text-muted);
  color: var(--text-primary);
}

.exclude-btn:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  padding: var(--space-4) 0;
}

.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  padding: var(--space-16) 0;
  text-align: center;
}

.state.loading { color: var(--text-secondary); }
.state.error { color: var(--danger); }
.state.empty { color: var(--text-muted); }
</style>
