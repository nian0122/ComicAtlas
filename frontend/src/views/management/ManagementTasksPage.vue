<template>
  <div class="management-tasks-page">
    <header class="page-header">
      <div>
        <h1>任务中心</h1>
        <p>自动刷新，统一查看存储、回收、上传、导出和元数据任务。</p>
      </div>
      <el-button :loading="loading" @click="loadTasks">立即刷新</el-button>
    </header>

    <section class="summary-grid" aria-label="当前查询统计">
      <article><span>匹配任务</span><strong>{{ total }}</strong><small>全部分页结果</small></article>
      <article><span>运行中</span><strong>{{ activeCount }}</strong><small>当前页</small></article>
      <article><span>成功</span><strong>{{ successCount }}</strong><small>当前页</small></article>
      <article><span>失败/部分失败</span><strong>{{ failureCount }}</strong><small>当前页</small></article>
    </section>

    <div class="filters">
      <el-select v-model="query.type" placeholder="任务类型" clearable @change="resetAndLoad">
        <el-option v-for="type in MANAGEMENT_TASK_TYPES" :key="type" :label="managementTaskTypeLabel(type)" :value="type" />
      </el-select>
      <el-select v-model="query.status" placeholder="任务状态" clearable @change="resetAndLoad">
        <el-option v-for="status in TASK_STATUSES" :key="status" :label="taskStatusLabel(status)" :value="status" />
      </el-select>
      <el-input v-model="targetIdInput" placeholder="目标 ID" clearable @keyup.enter="applyTarget" />
      <el-button @click="applyTarget">筛选</el-button>
      <el-switch v-model="autoRefresh" active-text="自动刷新" />
      <span class="updated-at">{{ updatedAt ? `更新于 ${updatedAt}` : '尚未更新' }}</span>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon />
    <div v-loading="loading" class="task-groups" aria-label="按任务类型分组">
      <section v-for="group in groupedTasks" :key="group.type" class="task-group">
        <div class="group-heading">
          <h2>{{ group.label }}</h2>
          <span>{{ group.tasks.length }} 项</span>
        </div>
        <div class="task-cards">
          <article v-for="task in group.tasks" :key="task.id" class="task-card" @click="openTask(task)">
            <div class="task-card-accent" :class="`status-${task.status.toLowerCase()}`" />
            <div class="task-card-body">
              <div class="task-card-header">
                <div>
                  <strong>{{ task.operation }}</strong>
                  <span class="task-meta">最近更新 {{ formatTaskTime(task.updatedAt) }}</span>
                </div>
                <el-tag :type="taskStatusTone(task.status)">{{ taskStatusLabel(task.status) }}</el-tag>
              </div>
              <div class="task-card-info">
                <span>目标：{{ taskDisplayName(task) }} · {{ task.isBatch ? '批量任务' : '单项任务' }}</span>
                <span v-if="task.totalCount">共 {{ task.totalCount }} 项</span>
              </div>
              <div class="task-card-progress">
                <div class="progress-label"><span>{{ task.stage || '处理中' }}</span><strong>{{ task.progress ?? 0 }}%</strong></div>
                <el-progress :percentage="task.progress ?? 0" :status="task.status === 'FAILED' ? 'exception' : undefined" :show-text="false" />
              </div>
              <p v-if="task.errorMessage" class="task-card-error">{{ task.errorMessage }}</p>
              <div class="task-card-footer">
                <span>成功 {{ task.successCount ?? 0 }} · 失败 {{ task.failureCount ?? 0 }} · 取消 {{ task.cancelledCount ?? 0 }}</span>
                <span class="task-card-actions">
                  <el-button v-if="canCancel(task.status)" link type="warning" @click.stop="cancelTask(task.id)">取消</el-button>
                  <el-button v-if="canRetry(task)" link type="primary" @click.stop="retryTask(task.id)">重试</el-button>
                  <span v-if="!canCancel(task.status) && !canRetry(task)" class="detail-hint">查看明细</span>
                </span>
              </div>
            </div>
          </article>
        </div>
      </section>
      <div v-if="groupedTasks.length === 0" class="empty-state">当前筛选条件下暂无任务</div>
    </div>
    <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="prev, pager, next" @current-change="loadTasks" />

    <el-drawer v-model="drawerVisible" title="任务明细" size="60%">
      <div v-if="selectedTask" class="task-detail">
        <header class="detail-hero">
          <div>
            <span class="detail-eyebrow">{{ managementTaskTypeLabel(selectedTask.taskType) }}</span>
            <h2>{{ taskDisplayName(selectedTask) }}</h2>
            <p>{{ selectedTask.operation }} · {{ selectedTask.isBatch ? '批量任务' : '单项任务' }}</p>
          </div>
          <el-tag size="large" :type="taskStatusTone(selectedTask.status)">{{ taskStatusLabel(selectedTask.status) }}</el-tag>
        </header>

        <section class="detail-progress-panel" aria-label="任务进度">
          <div class="detail-progress-heading">
            <span>{{ selectedTask.stage || '当前阶段' }}</span>
            <strong>{{ selectedTask.progress ?? 0 }}%</strong>
          </div>
          <el-progress :percentage="selectedTask.progress ?? 0" :status="selectedTask.status === 'FAILED' ? 'exception' : undefined" :show-text="false" />
          <div class="detail-stat-grid">
            <div><span>总量</span><strong>{{ selectedTask.totalCount ?? 0 }}</strong></div>
            <div><span>成功</span><strong>{{ selectedTask.successCount ?? 0 }}</strong></div>
            <div><span>失败</span><strong>{{ selectedTask.failureCount ?? 0 }}</strong></div>
            <div><span>取消</span><strong>{{ selectedTask.cancelledCount ?? 0 }}</strong></div>
          </div>
        </section>

        <el-alert v-if="selectedTask.errorMessage" :title="selectedTask.errorMessage" type="error" show-icon />

        <section class="detail-section">
          <div class="detail-section-heading"><h3>任务标识</h3><span>用于数据库查询与排查</span></div>
          <div class="detail-fields">
            <div><span>任务 ID</span><strong>{{ selectedTask.id }}</strong></div>
            <div><span>目标 ID</span><strong>{{ selectedTargetId ?? '—' }}</strong></div>
            <div><span>目标类型</span><strong>{{ selectedTask.targetType }}</strong></div>
            <div><span>漫画名称</span><strong>{{ taskDisplayName(selectedTask) }}</strong></div>
            <div><span>尝试次数</span><strong>{{ selectedTask.attempt ?? 0 }}</strong></div>
            <div><span>批次 ID</span><strong>{{ selectedTask.batchId || '—' }}</strong></div>
          </div>
        </section>

        <section class="detail-section">
          <div class="detail-section-heading"><h3>时间记录</h3><span>任务生命周期</span></div>
          <div class="detail-fields detail-fields--times">
            <div><span>创建时间</span><strong>{{ formatDetailTime(selectedTask.createdAt) }}</strong></div>
            <div><span>开始时间</span><strong>{{ formatDetailTime(selectedTask.startedAt) }}</strong></div>
            <div><span>最近更新</span><strong>{{ formatDetailTime(selectedTask.updatedAt) }}</strong></div>
            <div><span>完成时间</span><strong>{{ formatDetailTime(selectedTask.completedAt) }}</strong></div>
          </div>
        </section>

        <section class="detail-section">
          <div class="detail-section-heading"><h3>目标项明细</h3><span>{{ taskItems.length }} 项</span></div>
          <el-table v-if="taskItems.length" :data="taskItems" row-key="id" class="detail-items-table">
            <el-table-column prop="targetType" label="类型" min-width="100" />
            <el-table-column prop="targetId" label="目标 ID" min-width="100" />
            <el-table-column label="状态" min-width="100"><template #default="{ row }"><el-tag :type="taskStatusTone(row.status)">{{ taskStatusLabel(row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="进度" min-width="120"><template #default="{ row }">{{ row.progress ?? 0 }}%</template></el-table-column>
            <el-table-column prop="errorMessage" label="错误" min-width="180" />
          </el-table>
          <div v-else class="detail-empty">暂无目标项明细</div>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'
import { managementComicApi } from '@/features/management/api'
import { ElMessage } from 'element-plus'
import { managementTaskApi } from '@/features/task/api'
import { MANAGEMENT_TASK_TYPES, managementTaskStatusLabel, managementTaskTypeLabel } from '@/features/task/labels'
import type { ManagementTaskItemVO, ManagementTaskStatus, ManagementTaskType, ManagementTaskVO } from '@/features/task/types'

const TASK_STATUSES = ['QUEUED', 'RUNNING', 'CANCELLING', 'CANCELLED', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED'] as const

const route = useRoute()
const routeTargetId = Number(route.query['targetId'])
const query = reactive<{ page: number; size: number; type?: ManagementTaskType; status?: ManagementTaskStatus; targetId?: number }>({ page: 1, size: 20, ...(Number.isSafeInteger(routeTargetId) && routeTargetId > 0 ? { targetId: routeTargetId } : {}) })
const tasks = ref<readonly ManagementTaskVO[]>([])
const taskItems = ref<readonly ManagementTaskItemVO[]>([])
const selectedTask = ref<ManagementTaskVO | null>(null)
const targetIdInput = ref(query.targetId ? String(query.targetId) : '')
const loading = ref(false)
const error = ref('')
const total = ref(0)
const drawerVisible = ref(false)
const autoRefresh = ref(true)
const updatedAt = ref('')
let timer: ReturnType<typeof setInterval> | undefined

const activeCount = computed(() => tasks.value.filter((task) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(task.status)).length)
const successCount = computed(() => tasks.value.filter((task) => task.status === 'SUCCEEDED').length)
const failureCount = computed(() => tasks.value.filter((task) => ['FAILED', 'PARTIALLY_SUCCEEDED'].includes(task.status)).length)
const groupedTasks = computed(() => MANAGEMENT_TASK_TYPES
  .map((type) => ({ type, label: managementTaskTypeLabel(type), tasks: tasks.value.filter((task) => task.taskType === type) }))
  .filter((group) => group.tasks.length > 0))
const taskNames = ref(new Map<number, string>())
const selectedTargetId = computed(() => selectedTask.value?.targetId ?? taskItems.value[0]?.targetId ?? null)

function errorMessage(reason: unknown): string {
  if (axios.isAxiosError<{ message?: string }>(reason)) return reason.response?.data?.message ?? reason.message
  return reason instanceof Error ? reason.message : '未知错误'
}
function taskStatusLabel(status: ManagementTaskStatus): string { return managementTaskStatusLabel(status) }
function taskStatusTone(status: ManagementTaskStatus): 'success' | 'warning' | 'danger' | 'info' { if (status === 'SUCCEEDED') return 'success'; if (status === 'FAILED' || status === 'PARTIALLY_SUCCEEDED') return 'danger'; if (status === 'RUNNING' || status === 'CANCELLING') return 'warning'; return 'info' }
function formatTaskTime(value: string): string { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
function formatDetailTime(value: string | null): string { return value ? new Date(value).toLocaleString('zh-CN') : '—' }
function taskDisplayName(task: ManagementTaskVO): string {
  if (task.targetName) return task.targetName
  return taskNames.value.get(task.id) || targetSummary(task)
}
function targetSummary(task: ManagementTaskVO): string {
  if (task.targetType === 'COMIC') return '漫画'
  if (task.targetType === 'CHAPTER') return '章节'
  if (task.targetType === 'MEDIA') return '媒体'
  return task.targetType
}
function canCancel(status: ManagementTaskStatus): boolean { return ['QUEUED', 'RUNNING'].includes(status) }
function canRetry(task: ManagementTaskVO): boolean {
  // RECOVERY/SCAN 走各自专用重试入口（恢复页/扫描页），任务中心不提供，
  // 避免统一入口重置 QUEUED 后无人重新入队导致任务永久卡死
  if (task.taskType === 'RECOVERY' || task.taskType === 'DIRECTORY_SCAN') return false
  return ['FAILED', 'CANCELLED', 'PARTIALLY_SUCCEEDED'].includes(task.status)
}
async function loadTasks(silent = false): Promise<void> { if (!silent) loading.value = true; error.value = ''; try { const response = await managementTaskApi.list(query); tasks.value = response.data.records; total.value = response.data.total; updatedAt.value = new Date().toLocaleTimeString(); void loadImportNames(response.data.records) } catch (reason: unknown) { error.value = errorMessage(reason) } finally { if (!silent) loading.value = false } }
async function loadImportNames(records: readonly ManagementTaskVO[]): Promise<void> {
  const candidates = records.filter((task) => task.targetType === 'COMIC' && !task.targetName)
  const results = await Promise.allSettled(candidates.map(async (task) => {
    const items = await managementTaskApi.getItems(task.id)
    const target = items.data.find((item) => item.targetType === 'COMIC')
    if (!target) return null
    const comic = await managementComicApi.detail(target.targetId)
    return { taskId: task.id, title: comic.data.title }
  }))
  const names = new Map(taskNames.value)
  for (const result of results) {
    if (result.status === 'fulfilled' && result.value) names.set(result.value.taskId, result.value.title)
  }
  taskNames.value = names
}
function resetAndLoad(): void { query.page = 1; void loadTasks() }
function applyTarget(): void { const parsed = Number(targetIdInput.value); query.targetId = Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined; resetAndLoad() }
async function openTask(task: ManagementTaskVO): Promise<void> { selectedTask.value = task; drawerVisible.value = true; try { taskItems.value = (await managementTaskApi.getItems(task.id)).data } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function cancelTask(id: number): Promise<void> { try { await managementTaskApi.cancel(id); ElMessage.success('已请求取消'); await loadTasks() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function retryTask(id: number): Promise<void> { try { await managementTaskApi.retry(id); ElMessage.success('已重新入队'); await loadTasks() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }

onMounted(() => { void loadTasks(); timer = setInterval(() => { if (autoRefresh.value && !loading.value) void loadTasks(true) }, 2500) })
onBeforeUnmount(() => { if (timer !== undefined) clearInterval(timer) })
</script>

<style scoped>
.management-tasks-page { display: grid; gap: var(--space-6); }
.page-header, .filters { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; }
.page-header h1 { margin: 0; color: var(--text-primary); font-size: var(--text-page); }
.page-header p, .updated-at { color: var(--text-muted); }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-4); }
.summary-grid article { display: grid; gap: var(--space-2); padding: var(--space-5); background: var(--bg-surface); border: 1px solid var(--border); }
.summary-grid span, .summary-grid small { color: var(--text-muted); }
.summary-grid strong { color: var(--text-primary); font-size: 2rem; }
.filters :deep(.el-input), .filters :deep(.el-select) { width: 180px; }
.task-groups { display: grid; gap: var(--space-6); min-height: 180px; }
.task-group { display: grid; gap: var(--space-3); }
.group-heading { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-3); border-bottom: 1px solid var(--border); padding-bottom: var(--space-2); }
.group-heading h2 { margin: 0; color: var(--text-primary); font-size: var(--text-section); }
.group-heading span, .task-meta, .detail-hint { color: var(--text-muted); font-size: var(--text-caption); }
.task-cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-3); }
.task-card { display: flex; min-width: 0; overflow: hidden; cursor: pointer; background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-md); transition: border-color 150ms ease, transform 150ms ease; }
.task-card:hover { border-color: var(--border-strong); transform: translateY(-1px); }
.task-card-accent { width: 4px; flex: 0 0 4px; background: var(--accent); }
.task-card-accent.status-succeeded { background: var(--success); }
.task-card-accent.status-failed, .task-card-accent.status-partially_succeeded { background: var(--danger); }
.task-card-accent.status-queued, .task-card-accent.status-running, .task-card-accent.status-cancelling { background: var(--warning); }
.task-card-body { display: grid; gap: var(--space-4); min-width: 0; width: 100%; padding: var(--space-4) var(--space-5); }
.task-card-header, .progress-label, .task-card-footer { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
.task-card-header strong { display: block; overflow: hidden; color: var(--text-primary); text-overflow: ellipsis; white-space: nowrap; }
.task-meta { display: block; margin-top: var(--space-1); }
.task-card-info { display: flex; flex-wrap: wrap; gap: var(--space-2) var(--space-4); color: var(--text-secondary); font-size: var(--text-caption); }
.task-card-progress { display: grid; gap: var(--space-2); }
.progress-label { color: var(--text-secondary); font-size: var(--text-caption); }
.progress-label strong { color: var(--text-primary); }
.task-card-footer { color: var(--text-muted); font-size: var(--text-caption); }
.task-card-error { margin: calc(var(--space-2) * -1) 0 0; color: var(--danger); font-size: var(--text-caption); line-height: 1.5; }
.task-card-actions { display: inline-flex; align-items: center; min-height: var(--control-height); }
.empty-state { padding: var(--space-8); color: var(--text-muted); text-align: center; border: 1px dashed var(--border); border-radius: var(--radius-md); }
.task-detail { display: grid; gap: var(--space-6); padding-bottom: var(--space-6); }
.detail-hero { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-4); padding-bottom: var(--space-5); border-bottom: 1px solid var(--border); }
.detail-eyebrow { color: var(--accent); font-size: var(--text-caption); font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; }
.detail-hero h2 { margin: var(--space-2) 0 var(--space-1); color: var(--text-primary); font-size: var(--text-section); }
.detail-hero p { margin: 0; color: var(--text-muted); }
.detail-progress-panel { display: grid; gap: var(--space-4); padding: var(--space-5); background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-md); }
.detail-progress-heading { display: flex; justify-content: space-between; gap: var(--space-3); color: var(--text-secondary); }
.detail-progress-heading strong { color: var(--text-primary); font-size: var(--text-section); }
.detail-stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-3); }
.detail-stat-grid div { display: grid; gap: var(--space-1); }
.detail-stat-grid span, .detail-section-heading span, .detail-fields span { color: var(--text-muted); font-size: var(--text-caption); }
.detail-stat-grid strong { color: var(--text-primary); font-size: 1.25rem; }
.detail-section { display: grid; gap: var(--space-3); }
.detail-section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-3); }
.detail-section-heading h3 { margin: 0; color: var(--text-primary); font-size: var(--text-subtitle); }
.detail-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1px; overflow: hidden; background: var(--border); border: 1px solid var(--border); border-radius: var(--radius-md); }
.detail-fields div { display: grid; gap: var(--space-1); min-width: 0; padding: var(--space-3) var(--space-4); background: var(--bg-surface); }
.detail-fields strong { overflow: hidden; color: var(--text-primary); font-size: var(--text-caption); text-overflow: ellipsis; white-space: nowrap; }
.detail-empty { padding: var(--space-5); color: var(--text-muted); text-align: center; border: 1px dashed var(--border); border-radius: var(--radius-md); }
.detail-items-table { width: 100%; }
@media (max-width: 900px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 480px) {
  .summary-grid { grid-template-columns: minmax(0, 1fr); }
  .filters :deep(.el-input), .filters :deep(.el-select) { width: 100%; }
  .task-cards { grid-template-columns: minmax(0, 1fr); }
  .detail-stat-grid, .detail-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
