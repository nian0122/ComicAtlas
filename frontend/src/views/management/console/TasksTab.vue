<template>
  <div class="tasks-tab" data-testid="tasks-tab">
    <div class="tasks-toolbar">
      <div class="tasks-filters">
        <label class="filter-group">
          <span class="filter-label">类型</span>
          <select
            class="filter-select"
            data-testid="tasks-filter-type"
            :value="store.typeFilter"
            @change="onTypeChange"
          >
            <option value="">全部类型</option>
            <option v-for="opt in TASK_TYPE_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </label>
        <label class="filter-group">
          <span class="filter-label">状态</span>
          <select
            class="filter-select"
            data-testid="tasks-filter-status"
            :value="store.statusFilter"
            @change="onStatusChange"
          >
            <option value="">全部状态</option>
            <option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </label>
      </div>
      <div class="tasks-actions">
        <span
          class="polling-dot"
          data-testid="tasks-polling"
          :data-polling="String(store.polling)"
          aria-label="轮询状态"
        />
        <button class="ghost-btn" data-testid="tasks-refresh" :disabled="store.loading" @click="onRefresh">
          刷新
        </button>
      </div>
    </div>

    <p v-if="store.error" class="state error" data-testid="tasks-error">{{ store.error }}</p>

    <div v-else class="table-scroll">
      <table class="tasks-table">
        <thead>
          <tr>
            <th class="col-id">ID</th>
            <th class="col-type">类型</th>
            <th class="col-status">状态</th>
            <th class="col-stage">阶段</th>
            <th class="col-progress">进度</th>
            <th class="col-attempt">尝试</th>
            <th class="col-error">错误</th>
            <th class="col-items" />
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="task in store.tasks" :key="task.id">
            <tr class="task-row" :data-testid="`task-row-${task.id}`">
              <td class="col-id" data-testid="task-id">{{ task.id }}</td>
              <td class="col-type" data-testid="task-type">{{ task.taskType.value }}</td>
              <td class="col-status">
                <span
                  class="status-badge"
                  data-testid="task-status"
                  :data-status="task.status.value"
                  :title="chineseStatusLabel(task.status)"
                  role="status"
                  :class="statusClass(task.status)"
                >
                  {{ task.status.value }}
                </span>
              </td>
              <td class="col-stage" data-testid="task-stage">{{ stageLabel(task.stage) }}</td>
              <td class="col-progress">
                <div class="progress-line">
                  <span
                    class="progress-fill"
                    :class="progressClass(task.status)"
                    :style="{ width: `${progressWidth(task)}%` }"
                    role="progressbar"
                    :aria-valuenow="task.progress"
                    aria-valuemin="0"
                    aria-valuemax="100"
                  />
                </div>
                <span class="progress-counts" data-testid="task-counts">{{ countsText(task) }}</span>
              </td>
              <td class="col-attempt" data-testid="task-attempt">{{ task.attempt }}</td>
              <td class="col-error">
                <span class="task-error" data-testid="task-error" :title="task.errorMessage">
                  {{ task.errorMessage || '—' }}
                </span>
              </td>
              <td class="col-items">
                <button
                  v-if="task.isBatch"
                  class="link-btn"
                  :data-testid="`task-toggle-${task.id}`"
                  @click="toggleItems(task)"
                >
                  {{ expandedId === task.id ? '收起' : '逐项' }}
                </button>
              </td>
              <td class="col-actions">
                <button
                  v-if="canCancel(task)"
                  class="ghost-btn small"
                  :data-testid="`task-cancel-${task.id}`"
                  :disabled="busyIds.includes(task.id)"
                  @click="onCancel(task)"
                >
                  {{ busyIds.includes(task.id) ? '取消中' : '取消' }}
                </button>
                <button
                  v-if="canRetry(task)"
                  class="ghost-btn small danger-hover"
                  :data-testid="`task-retry-${task.id}`"
                  :disabled="busyIds.includes(task.id)"
                  @click="onRetry(task)"
                >
                  {{ busyIds.includes(task.id) ? '重试中' : '重试' }}
                </button>
              </td>
            </tr>
            <tr v-if="expandedId === task.id" class="task-items-row">
              <td colspan="9">
                <div class="task-items">
                  <div
                    v-for="item in store.itemsByTask[task.id] ?? []"
                    :key="item.id"
                    class="task-item"
                    :data-testid="`task-item-${task.id}-${item.id}`"
                  >
                    <span class="task-item-id">#{{ item.id }}</span>
                    <span
                      class="status-badge small"
                      data-testid="task-item-status"
                      :title="chineseStatusLabel(item.status)"
                      :class="statusClass(item.status)"
                    >
                      {{ item.status.value }}
                    </span>
                    <span class="task-item-meta">attempt {{ item.attempt }}</span>
                    <span class="task-item-error" data-testid="task-item-error">
                      {{ item.errorMessage || '—' }}
                    </span>
                  </div>
                  <p v-if="(store.itemsByTask[task.id] ?? []).length === 0" class="task-items-empty">
                    暂无逐项数据
                  </p>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <p v-if="!store.loading && store.tasks.length === 0" class="state empty" data-testid="tasks-empty">
      暂无任务
    </p>

    <div v-if="store.total > 0" class="tasks-pagination" data-testid="tasks-pagination">
      <button
        class="ghost-btn small"
        :disabled="store.page <= 1"
        data-testid="tasks-prev"
        @click="onPage(-1)"
      >
        上一页
      </button>
      <span class="pagination-info">
        {{ store.page }} / {{ Math.max(1, Math.ceil(store.total / store.size)) }}
      </span>
      <button
        class="ghost-btn small"
        :disabled="store.page * store.size >= store.total"
        data-testid="tasks-next"
        @click="onPage(1)"
      >
        下一页
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useManagementTaskStore } from '@/stores/management/managementTask'
import { ManagementTaskStatus, TaskStage, TaskType } from '@/types/management/enums'
import type { EnumValue, ManagementTaskStatus as StatusValue } from '@/types/management/enums'
import type { ManagementTaskEntry } from '@/types/management/task'

const props = withDefaults(defineProps<{ active?: boolean }>(), { active: true })

const store = useManagementTaskStore()

const TASK_TYPE_OPTIONS: readonly { value: TaskType; label: string }[] = [
  { value: TaskType.IMPORT, label: '导入' },
  { value: TaskType.EXPORT, label: '导出' },
  { value: TaskType.RECOVERY, label: '存储恢复' },
  { value: TaskType.DIRECTORY_SCAN, label: '目录扫描' },
  { value: TaskType.LQ_GENERATE, label: '生成 LQ' },
  { value: TaskType.LQ_REGENERATE, label: '重新生成 LQ' },
  { value: TaskType.HQ_DELETE, label: '删除 HQ' },
  { value: TaskType.TRANSCODE, label: '视频转码' },
  { value: TaskType.METADATA_REFRESH, label: '元数据刷新' },
  { value: TaskType.COMIC_DELETE, label: '回收漫画' },
  { value: TaskType.COMIC_RESTORE, label: '恢复漫画' },
  { value: TaskType.COMIC_PURGE, label: '永久清理' },
]

const STATUS_OPTIONS: readonly { value: StatusValue; label: string }[] = [
  { value: ManagementTaskStatus.QUEUED, label: '排队中' },
  { value: ManagementTaskStatus.RUNNING, label: '运行中' },
  { value: ManagementTaskStatus.CANCELLING, label: '取消中' },
  { value: ManagementTaskStatus.CANCELLED, label: '已取消' },
  { value: ManagementTaskStatus.SUCCEEDED, label: '成功' },
  { value: ManagementTaskStatus.PARTIALLY_SUCCEEDED, label: '部分成功' },
  { value: ManagementTaskStatus.FAILED, label: '失败' },
]

const STATUS_LABELS: Readonly<Record<StatusValue, string>> = {
  [ManagementTaskStatus.QUEUED]: '排队中',
  [ManagementTaskStatus.RUNNING]: '运行中',
  [ManagementTaskStatus.CANCELLING]: '取消中',
  [ManagementTaskStatus.CANCELLED]: '已取消',
  [ManagementTaskStatus.SUCCEEDED]: '成功',
  [ManagementTaskStatus.PARTIALLY_SUCCEEDED]: '部分成功',
  [ManagementTaskStatus.FAILED]: '失败',
}

const expandedId = ref<number | null>(null)
const busyIds = ref<readonly number[]>([])

function statusLabel(status: EnumValue<StatusValue>): string {
  if (status.kind === 'unknown') return `未知状态 (${status.value})`
  return STATUS_LABELS[status.value] ?? status.value
}

function chineseStatusLabel(status: EnumValue<StatusValue>): string {
  return statusLabel(status)
}

function statusClass(status: EnumValue<StatusValue>): string {
  if (status.kind === 'unknown') return 'is-unknown'
  switch (status.value) {
    case ManagementTaskStatus.RUNNING:
    case ManagementTaskStatus.QUEUED:
    case ManagementTaskStatus.CANCELLING:
      return 'is-running'
    case ManagementTaskStatus.SUCCEEDED:
      return 'is-success'
    case ManagementTaskStatus.PARTIALLY_SUCCEEDED:
      return 'is-warning'
    case ManagementTaskStatus.CANCELLED:
      return 'is-neutral'
    case ManagementTaskStatus.FAILED:
      return 'is-danger'
    default:
      return 'is-unknown'
  }
}

function progressClass(status: EnumValue<StatusValue>): string {
  return statusClass(status)
}

function stageLabel(stage: EnumValue<TaskStage> | null): string {
  if (stage === null) return '—'
  if (stage.kind === 'unknown') return stage.value
  switch (stage.value) {
    case TaskStage.DOWNLOADING:
      return '下载中'
    case TaskStage.EXTRACTING:
      return '解压中'
    case TaskStage.PARSING:
      return '解析中'
    default:
      return '未知阶段'
  }
}

function progressWidth(task: ManagementTaskEntry): number {
  if (task.status.kind === 'known' && task.status.value === ManagementTaskStatus.SUCCEEDED) return 100
  return Math.max(0, Math.min(100, task.progress))
}

function countsText(task: ManagementTaskEntry): string {
  const parts: string[] = []
  if (task.successCount > 0) parts.push(`成功 ${task.successCount}`)
  if (task.failureCount > 0) parts.push(`失败 ${task.failureCount}`)
  if (task.cancelledCount > 0) parts.push(`取消 ${task.cancelledCount}`)
  return parts.length > 0 ? parts.join(' / ') : `${task.progress}%`
}

function canCancel(task: ManagementTaskEntry): boolean {
  if (task.status.kind === 'unknown') return false
  return (
    task.status.value === ManagementTaskStatus.QUEUED ||
    task.status.value === ManagementTaskStatus.RUNNING ||
    task.status.value === ManagementTaskStatus.CANCELLING
  )
}

function canRetry(task: ManagementTaskEntry): boolean {
  if (task.status.kind === 'unknown') return false
  return task.status.value === ManagementTaskStatus.FAILED
}

function setBusy(id: number, busy: boolean): void {
  if (busy) {
    busyIds.value = busyIds.value.includes(id) ? busyIds.value : [...busyIds.value, id]
  } else {
    busyIds.value = busyIds.value.filter((v) => v !== id)
  }
}

async function onTypeChange(event: Event): Promise<void> {
  const value = (event.target as HTMLSelectElement).value
  await store.fetchList({ type: value === '' ? undefined : (value as TaskType), page: 1 })
}

async function onStatusChange(event: Event): Promise<void> {
  const value = (event.target as HTMLSelectElement).value
  await store.fetchList({
    status: value === '' ? undefined : (value as StatusValue),
    page: 1,
  })
}

async function onRefresh(): Promise<void> {
  await store.fetchList()
}

async function onPage(delta: -1 | 1): Promise<void> {
  await store.fetchList({ page: store.page + delta })
}

async function toggleItems(task: ManagementTaskEntry): Promise<void> {
  if (expandedId.value === task.id) {
    expandedId.value = null
    return
  }
  expandedId.value = task.id
  if (!(store.itemsByTask[task.id] ?? []).length) {
    await store.fetchItems(task.id)
  }
}

async function onCancel(task: ManagementTaskEntry): Promise<void> {
  setBusy(task.id, true)
  try {
    await store.cancelTask(task.id)
    ElMessage.success(`任务 #${task.id} 已取消`)
  } catch (err: unknown) {
    ElMessage.error(err instanceof Error ? err.message : '取消失败')
  } finally {
    setBusy(task.id, false)
  }
}

async function onRetry(task: ManagementTaskEntry): Promise<void> {
  setBusy(task.id, true)
  try {
    await store.retryTask(task.id)
    ElMessage.success(`任务 #${task.id} 已重新排队`)
  } catch (err: unknown) {
    ElMessage.error(err instanceof Error ? err.message : '重试失败')
  } finally {
    setBusy(task.id, false)
  }
}

function syncPolling(): void {
  if (props.active) {
    store.stopPolling()
    void store.bootstrap()
  } else {
    store.stopPolling()
  }
}

watch(() => props.active, syncPolling)

onMounted(syncPolling)
onBeforeUnmount(() => store.stopPolling())
</script>

<style scoped>
.tasks-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  min-width: 0;
}

.tasks-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.tasks-filters {
  display: flex;
  gap: var(--space-4);
  align-items: flex-end;
  flex-wrap: wrap;
}

.filter-group {
  display: grid;
  gap: var(--space-1);
}

.filter-label {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-muted);
  letter-spacing: 0.04em;
}

.filter-select {
  min-height: var(--control-min-size);
  padding-inline: var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-family: var(--font-ui);
}

.filter-select:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}

.tasks-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.polling-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-muted);
}

.polling-dot[data-polling='true'] {
  background: var(--accent);
  animation: pulse var(--motion-standard) infinite alternate;
}

@keyframes pulse {
  from {
    opacity: 0.45;
  }
  to {
    opacity: 1;
  }
}

.ghost-btn {
  min-height: var(--control-min-size);
  padding-inline: var(--space-4);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  font-family: var(--font-ui);
  cursor: pointer;
  transition:
    background-color var(--transition-fast),
    color var(--transition-fast);
}

.ghost-btn:hover:not(:disabled) {
  background: var(--bg-surface);
  color: var(--text-primary);
}

.ghost-btn:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}

.ghost-btn.small {
  min-height: 32px;
  padding-inline: var(--space-3);
  font-size: var(--text-xs);
}

.ghost-btn.danger-hover:hover:not(:disabled) {
  border-color: var(--danger);
  color: var(--danger);
}

.link-btn {
  min-height: var(--control-min-size);
  padding-inline: var(--space-2);
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 600;
  font-family: var(--font-ui);
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.state.error {
  padding: var(--space-4);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: rgb(240 107 112 / 10%);
  color: var(--danger);
  font-size: var(--text-sm);
}

.state.empty {
  padding: var(--space-10) 0;
  color: var(--text-muted);
  text-align: center;
  font-size: var(--text-sm);
}

.table-scroll {
  overflow-x: auto;
  min-block-size: 0;
}

.tasks-table {
  width: 100%;
  min-width: 820px;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.tasks-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border-strong);
  background: var(--bg-secondary);
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-weight: 700;
  letter-spacing: 0.04em;
  text-align: left;
  white-space: nowrap;
}

.tasks-table td {
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  vertical-align: middle;
}

.task-row:hover td {
  background: var(--bg-surface);
}

.col-id {
  width: 56px;
  font-variant-numeric: tabular-nums;
}

.col-type {
  white-space: nowrap;
  font-weight: 600;
  color: var(--text-primary);
}

.col-status {
  white-space: nowrap;
}

.col-stage {
  white-space: nowrap;
}

.col-attempt {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.col-error {
  max-width: 240px;
}

.col-actions {
  white-space: nowrap;
  text-align: right;
}

.task-error {
  display: block;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-muted);
}

.status-badge {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding-inline: var(--space-2);
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: 600;
  white-space: nowrap;
}

.status-badge.small {
  min-height: 18px;
  font-size: 11px;
}

.status-badge.is-running {
  background: var(--accent-bg);
  color: var(--accent);
}

.status-badge.is-success {
  background: rgb(102 197 139 / 14%);
  color: var(--success);
}

.status-badge.is-warning {
  background: rgb(216 165 79 / 14%);
  color: var(--warning);
}

.status-badge.is-danger {
  background: rgb(240 107 112 / 14%);
  color: var(--danger);
}

.status-badge.is-neutral {
  background: rgb(140 140 136 / 14%);
  color: var(--text-muted);
}

.status-badge.is-unknown {
  background: rgb(112 166 216 / 14%);
  color: var(--info);
}

.progress-line {
  display: flex;
  align-items: center;
  width: 120px;
  height: 4px;
  border-radius: var(--radius-pill);
  background: var(--color-progress-track);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--accent);
}

.progress-fill.is-success {
  background: var(--success);
}

.progress-fill.is-danger {
  background: var(--danger);
}

.progress-fill.is-warning {
  background: var(--warning);
}

.progress-fill.is-neutral {
  background: var(--text-muted);
}

.progress-counts {
  display: block;
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.task-items-row td {
  padding: 0;
  border-bottom: none;
}

.task-items {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-4);
  background: var(--bg-surface);
  border-inline: 1px solid var(--border);
}

.task-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-xs);
  font-size: var(--text-xs);
}

.task-item-id {
  font-variant-numeric: tabular-nums;
  color: var(--text-muted);
  min-width: 44px;
}

.task-item-meta {
  color: var(--text-muted);
}

.task-item-error {
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-items-empty {
  margin: 0;
  padding: var(--space-2);
  color: var(--text-muted);
  font-size: var(--text-xs);
}

.tasks-pagination {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.pagination-info {
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
</style>
