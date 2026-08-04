<template>
  <div class="comic-workspace-page" data-testid="workspace-page">
    <header class="ws-header">
      <div class="ws-header-left">
        <button class="back-link" type="button" data-testid="ws-back" @click="goBack">
          <el-icon :size="16"><Back /></el-icon>
          <span>返回列表</span>
        </button>
        <h1 class="ws-title" data-testid="ws-title">
          {{ store.detail?.title ?? '加载中…' }}
        </h1>
        <span
          v-if="lifecycleLabel"
          class="status-tag"
          :class="lifecycleTone"
          data-testid="ws-lifecycle"
          role="status"
        >
          {{ lifecycleLabel }}
        </span>
        <span v-if="store.detail" class="ws-id-badge">ID: {{ comicId }}</span>
      </div>
      <div class="ws-header-right">
        <span v-if="activeTask" class="ws-active-task" data-testid="ws-active-task">
          <el-icon :size="14"><Loading /></el-icon>
          {{ activeTaskLabel }}
        </span>
      </div>
    </header>

    <div v-if="store.loading" class="ws-state" data-testid="workspace-loading">
      <div class="action-btn-spinner" aria-hidden="true" />
      <span>加载中…</span>
    </div>

    <div v-else-if="store.error" class="ws-state ws-state--error">
      <el-icon :size="32"><WarningFilled /></el-icon>
      <span data-testid="workspace-error">{{ store.error }}</span>
      <button class="action-btn action-btn--secondary" type="button" data-testid="workspace-retry" @click="reload">
        重试
      </button>
    </div>

    <template v-else>
      <nav class="ws-tabs" role="tablist" aria-label="漫画工作区">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          class="ws-tab"
          :class="{ active: activeTab === tab.key }"
          role="tab"
          :aria-selected="activeTab === tab.key"
          :data-testid="`ws-tab-${tab.key}`"
          type="button"
          @click="switchTab(tab.key)"
        >
          {{ tab.label }}
        </button>
      </nav>

      <div class="ws-content" role="tabpanel">
        <OverviewTab v-if="activeTab === 'overview'" :comic-id="comicId" />
        <CatalogTab v-else-if="activeTab === 'catalog'" />
        <MediaTab v-else-if="activeTab === 'media'" />
        <OptimizationTab v-else-if="activeTab === 'optimization'" />
        <TasksTab v-else-if="activeTab === 'tasks'" />
        <DangerTab v-else-if="activeTab === 'danger'" />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Back, Loading, WarningFilled } from '@element-plus/icons-vue'
import { useComicWorkspaceStore } from '@/stores/management/workspace'
import type { ComicLifecycleStatus as LifecycleType } from '@/types/management/enums'
import OverviewTab from './OverviewTab.vue'
import CatalogTab from './CatalogTab.vue'
import MediaTab from './MediaTab.vue'
import OptimizationTab from './OptimizationTab.vue'
import TasksTab from './TasksTab.vue'
import DangerTab from './DangerTab.vue'

const props = defineProps<{ readonly id: string }>()

const route = useRoute()
const router = useRouter()
const store = useComicWorkspaceStore()

const comicId = computed(() => Number(props.id))

const TABS = [
  { key: 'overview', label: '概览' },
  { key: 'catalog', label: '目录' },
  { key: 'media', label: '媒体' },
  { key: 'optimization', label: '优化' },
  { key: 'tasks', label: '任务' },
  { key: 'danger', label: '危险操作' },
] as const

type TabKey = (typeof TABS)[number]['key']

const activeTab = ref<TabKey>('overview')

const LIFECYCLE_LABELS: Readonly<Record<LifecycleType, string>> = {
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

const LIFECYCLE_TONES: Readonly<Record<LifecycleType, string>> = {
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

const lifecycleLabel = computed<string | null>(() => {
  const lifecycle = store.lifecycle
  if (!lifecycle) return null
  if (lifecycle.kind === 'known') return LIFECYCLE_LABELS[lifecycle.value] ?? lifecycle.value
  return `未知状态 (${lifecycle.value})`
})

const lifecycleTone = computed(() => {
  const lifecycle = store.lifecycle
  if (lifecycle?.kind === 'known') return LIFECYCLE_TONES[lifecycle.value] ?? 'status-tag--neutral'
  return 'status-tag--neutral'
})

const activeTask = computed(() => store.activeTask)

const activeTaskLabel = computed(() => {
  const task = store.activeTask
  if (!task) return ''
  const type = task.taskType.kind === 'known' ? task.taskType.value : task.taskType.value
  const pct = task.progress
  return `${type} · ${pct}%`
})

function syncTab(): void {
  const raw = route.query.tab
  const candidate = TABS.find((t) => t.key === raw)
  activeTab.value = candidate ? candidate.key : 'overview'
}

function switchTab(key: TabKey): void {
  activeTab.value = key
  void router.replace({ query: { ...route.query, tab: key } })
}

function goBack(): void {
  router.push('/manage/comics')
}

async function reload(): Promise<void> {
  if (comicId.value > 0) {
    await store.load(comicId.value)
  }
}

watch(() => props.id, () => {
  void reload()
})

watch(() => route.query.tab, syncTab)

onMounted(() => {
  syncTab()
  void reload()
})
</script>

<style scoped>
.comic-workspace-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  padding-bottom: var(--space-16);
  min-width: 0;
}

.ws-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.ws-header-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  min-width: 0;
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-height: var(--control-min-size);
  padding-inline: var(--space-2);
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
}
.back-link:hover { color: var(--text-primary); }
.back-link:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}

.ws-title {
  margin: 0;
  font-size: var(--text-page);
  font-weight: 700;
  color: var(--text-primary);
  line-break: strict;
  overflow-wrap: anywhere;
  min-width: 0;
}

.ws-id-badge {
  padding: 2px 8px;
  border-radius: var(--radius-xs);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  font-family: var(--mono);
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.ws-header-right {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.ws-active-task {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-pill);
  background: var(--accent-bg);
  color: var(--accent-hover);
  font-size: var(--text-xs);
  font-weight: 600;
}

.ws-tabs {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1);
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
  min-block-size: 0;
}

.ws-tab {
  display: inline-flex;
  align-items: center;
  min-height: var(--control-min-size);
  padding: var(--space-2) var(--space-4);
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  border-bottom: 2px solid transparent;
  transition:
    color var(--transition-fast),
    border-color var(--transition-fast);
}

.ws-tab:hover { color: var(--text-primary); }

.ws-tab.active {
  color: var(--text-primary);
  border-bottom-color: var(--accent);
}

.ws-tab:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: -2px;
}

.ws-content {
  min-width: 0;
}

.ws-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  padding: var(--space-16) 0;
  text-align: center;
  color: var(--text-secondary);
  font-size: var(--text-sm);
}

.ws-state--error {
  color: var(--danger);
}
</style>
