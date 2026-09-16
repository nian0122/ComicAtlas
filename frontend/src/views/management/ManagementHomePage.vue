<template>
  <div class="management-home-page">
    <ManagementPageHeader title="仓库控制台" description="本地漫画仓库的最近活动与运行状态。">
      <span class="page-updated">{{ updatedAt ? `更新于 ${updatedAt}` : '正在加载' }}</span>
    </ManagementPageHeader>

    <el-alert v-if="error" :title="error" type="warning" show-icon />

    <ManagementPanel class="task-panel" aria-labelledby="recent-task-title">
      <PanelHeader title-id="recent-task-title" title="最近任务" description="导入、整理和存储操作的最新状态。">
        <router-link to="/manage/tasks" class="section-link">查看全部 <span aria-hidden="true">→</span></router-link>
      </PanelHeader>

      <div v-if="recentTasks.length" class="task-table-wrap">
        <table class="task-table">
          <thead>
            <tr>
              <th>任务名称</th>
              <th>类型</th>
              <th>状态</th>
              <th>进度</th>
              <th>开始时间</th>
              <th>结束时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in recentTasks" :key="task.id">
              <td class="task-name">{{ task.targetName || task.operation }}</td>
              <td>{{ taskTypeLabel(task.taskType) }}</td>
              <td>
                <TaskStatusTag :status="task.status" appearance="dot" />
              </td>
              <td class="progress-cell">
                <span>{{ task.progress ?? 0 }}%</span>
                <span class="progress-track"><span :style="{ width: `${task.progress ?? 0}%` }" /></span>
              </td>
              <td>{{ formatDateTime(task.startedAt || task.createdAt) }}</td>
              <td>{{ formatDateTime(task.completedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else description="暂无管理任务" />
    </ManagementPanel>

    <div class="dashboard-columns">
      <ManagementPanel class="recent-panel" aria-labelledby="recent-management-title">
        <PanelHeader title-id="recent-management-title" title="最近管理" description="最近更新过的漫画。">
          <router-link to="/manage/workbench?tab=status" class="section-link"
            >查看全部 <span aria-hidden="true">→</span></router-link
          >
        </PanelHeader>

        <div v-if="recentComics.length" class="comic-list">
          <router-link
            v-for="comic in recentComics"
            :key="comic.id"
            :to="`/manage/comics/${comic.id}?tab=operations`"
            class="comic-row"
          >
            <img :src="comic.coverUrl" :alt="`${comic.title} 封面`" class="comic-cover" loading="lazy" />
            <span class="comic-row-main">
              <strong>{{ comic.title }}</strong>
              <small>{{ comic.pageCount }} 页 · {{ comic.author || '作者未知' }}</small>
            </span>
            <span class="comic-row-action">编辑信息</span>
            <ComicStatusTag :status="comic.status" appearance="dot" />
          </router-link>
        </div>
        <EmptyState v-else description="暂无漫画记录" />
      </ManagementPanel>

      <ManagementPanel class="quick-panel" aria-labelledby="quick-action-title">
        <PanelHeader title-id="quick-action-title" title="快速操作" description="常用管理入口。"> </PanelHeader>
        <nav class="quick-actions" aria-label="快速操作">
          <router-link v-for="action in quickActions" :key="action.to" :to="action.to" class="quick-action">
            <el-icon :size="18"><component :is="action.icon" /></el-icon>
            <span>{{ action.label }}</span>
            <span class="quick-arrow" aria-hidden="true">›</span>
          </router-link>
        </nav>
      </ManagementPanel>
    </div>

    <StatGrid class="stats-strip" aria-label="仓库概览" :columns="5">
      <StatCard label="漫画总数" :value="comicTotal ?? '—'" unit="本"
        ><template #icon
          ><el-icon :size="24"><Collection /></el-icon></template
      ></StatCard>
      <StatCard label="存储占用" :value="formatBytes(storage?.totalBytes)"
        ><template #icon
          ><el-icon :size="24"><FolderOpened /></el-icon></template
      ></StatCard>
      <StatCard label="任务总数" :value="taskTotal ?? '—'" unit="个"
        ><template #icon
          ><el-icon :size="24"><CircleCheck /></el-icon></template
      ></StatCard>
      <StatCard label="任务异常" :value="failedTaskCount ?? '—'" unit="个" tone="warning"
        ><template #icon
          ><el-icon :size="24"><Warning /></el-icon></template
      ></StatCard>
      <StatCard label="任务运行中" :value="activeTaskCount ?? '—'" unit="个"
        ><template #icon
          ><el-icon :size="24"><Clock /></el-icon></template
      ></StatCard>
    </StatGrid>
  </div>
</template>

<script setup lang="ts">
import ManagementPanel from '@/components/management/ManagementPanel.vue'
import StatGrid from '@/components/management/StatGrid.vue'
import ComicStatusTag from '@/features/comic/components/ComicStatusTag.vue'
import TaskStatusTag from '@/features/task/components/TaskStatusTag.vue'
import StatCard from '@/components/management/StatCard.vue'
import PanelHeader from '@/components/management/PanelHeader.vue'
import EmptyState from '@/components/management/EmptyState.vue'
import ManagementPageHeader from '@/components/management/ManagementPageHeader.vue'
import { onMounted, ref } from 'vue'
import {
  CircleCheck,
  Clock,
  Collection,
  Coin,
  Delete,
  FolderOpened,
  List,
  Refresh,
  UploadFilled,
  Warning,
} from '@element-plus/icons-vue'
import { managementComicApi } from '@/features/comic/management-api'
import { managementTaskApi } from '@/features/task/api'
import { storageService } from '@/features/storage/service'
import { getApiErrorMessage } from '@/services/http'
import { managementTaskTypeLabel } from '@/features/task/labels'
import type { ComicListVO } from '@/entities/comic/types'
import type { ManagementTaskType, ManagementTaskVO } from '@/features/task/types'
import type { StorageStats } from '@/features/storage/types'

const quickActions = [
  { to: '/manage/import', label: '导入漫画', icon: UploadFilled },
  { to: '/manage/workbench?tab=status', label: '扫描更新', icon: Refresh },
  { to: '/manage/tasks', label: '任务中心', icon: List },
  { to: '/manage/workbench?tab=storage', label: '存储管理', icon: Coin },
  { to: '/manage/trash', label: '回收站', icon: Delete },
] as const

const recentTasks = ref<readonly ManagementTaskVO[]>([])
const recentComics = ref<readonly ComicListVO[]>([])
const storage = ref<StorageStats | null>(null)
const comicTotal = ref<number | null>(null)
const taskTotal = ref<number | null>(null)
const failedTaskCount = ref<number | null>(null)
const activeTaskCount = ref<number | null>(null)
const updatedAt = ref('')
const error = ref('')

function errorMessage(reason: unknown): string {
  return getApiErrorMessage(reason, '未知错误')
}

function formatDateTime(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatBytes(bytes: number | undefined): string {
  if (bytes === undefined) return '—'
  if (bytes >= 1024 ** 4) return `${(bytes / 1024 ** 4).toFixed(2)} TB`
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}

function taskTypeLabel(type: ManagementTaskType): string {
  return managementTaskTypeLabel(type)
}

onMounted(async () => {
  try {
    const [comicSummary, comics, tasks, storageSummary] = await Promise.all([
      managementComicApi.list({ page: 1, size: 1 }),
      managementComicApi.list({ page: 1, size: 5, sort: 'updatedAt' }),
      managementTaskApi.list({ page: 1, size: 50 }),
      storageService.fetchSummary(),
    ])
    comicTotal.value = comicSummary.data.total
    recentComics.value = comics.data.records
    recentTasks.value = tasks.data.records.slice(0, 5)
    taskTotal.value = tasks.data.total
    failedTaskCount.value = tasks.data.records.filter((task) =>
      ['FAILED', 'PARTIALLY_SUCCEEDED'].includes(task.status),
    ).length
    activeTaskCount.value = tasks.data.records.filter((task) =>
      ['QUEUED', 'RUNNING', 'CANCELLING'].includes(task.status),
    ).length
    storage.value = storageSummary
    updatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } catch (reason: unknown) {
    error.value = errorMessage(reason)
  }
})
</script>

<style scoped>
.management-home-page {
  display: grid;
  gap: var(--space-6);
  min-width: 0;
}
.page-updated {
  margin: var(--space-1) 0 0;
  color: var(--text-muted);
  font-size: var(--text-sm);
}
.page-updated {
  white-space: nowrap;
}

.section-link {
  color: var(--text-muted);
  font-size: var(--text-sm);
  white-space: nowrap;
}
.section-link:hover {
  color: var(--text-primary);
}

.task-table-wrap,
.comic-list {
  margin-top: var(--space-5);
  overflow-x: auto;
}
.task-table {
  width: 100%;
  min-width: 820px;
  border-collapse: collapse;
  font-size: var(--text-sm);
}
.task-table th,
.task-table td {
  padding: var(--space-3) var(--space-2);
  border-bottom: 1px solid var(--border);
  text-align: left;
  white-space: nowrap;
}
.task-table th {
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-weight: 600;
}
.task-table td {
  color: var(--text-secondary);
}
.task-table tr:last-child td {
  border-bottom: 0;
}
.task-table > tbody > tr > td.task-name {
  max-width: 240px;
  overflow: hidden;
  color: var(--text-primary);
  text-overflow: ellipsis;
}

.progress-cell {
  display: grid;
  grid-template-columns: 42px minmax(90px, 1fr);
  align-items: center;
  gap: var(--space-2);
}
.progress-track {
  display: block;
  height: 4px;
  overflow: hidden;
  border-radius: var(--radius-pill);
  background: var(--color-progress-track);
}
.progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
}

.dashboard-columns {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.65fr);
  gap: var(--space-6);
  min-width: 0;
}
.comic-list {
  display: grid;
  gap: 0;
}
.comic-row {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: var(--space-3);
  min-width: 0;
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--border);
}
.comic-row:last-child {
  border-bottom: 0;
}
.comic-row:hover strong {
  color: var(--accent);
}
.comic-cover {
  width: 36px;
  height: 48px;
  border-radius: var(--radius-xs);
  object-fit: cover;
  background: var(--bg-secondary);
}
.comic-row-main {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
}
.comic-row-main strong {
  overflow: hidden;
  color: var(--text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color var(--transition-fast);
}
.comic-row-main small {
  overflow: hidden;
  color: var(--text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.comic-row-action {
  color: var(--text-muted);
  font-size: var(--text-xs);
}

.quick-actions {
  display: grid;
  gap: var(--space-2);
  margin-top: var(--space-5);
}
.quick-action {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-3);
  min-height: 44px;
  padding: 0 var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  transition:
    border-color var(--transition-fast),
    background-color var(--transition-fast),
    color var(--transition-fast);
}
.quick-action:hover {
  border-color: var(--border-strong);
  background: var(--surface-highlight);
  color: var(--text-primary);
}
.quick-arrow {
  color: var(--text-muted);
  font-size: 22px;
  line-height: 1;
}

@media (max-width: 1100px) {
  .dashboard-columns {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 700px) {
  .comic-row {
    grid-template-columns: 36px minmax(0, 1fr) auto;
  }
  .comic-row-action {
    display: none;
  }
}
</style>
