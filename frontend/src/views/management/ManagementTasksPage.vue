<template>
  <div class="management-tasks-page">
    <header class="page-header">
      <div>
        <h1>统一管理任务</h1>
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
    <div class="table-scroll"><el-table v-loading="loading" :data="tasks" row-key="id" @row-click="openTask">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="类型" min-width="150">
        <template #default="{ row }">{{ managementTaskTypeLabel(row.taskType) }}</template>
      </el-table-column>
      <el-table-column prop="operation" label="操作" min-width="150" />
      <el-table-column label="状态" width="140">
        <template #default="{ row }"><el-tag :type="taskStatusTone(row.status)">{{ taskStatusLabel(row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="进度" min-width="180">
        <template #default="{ row }"><el-progress :percentage="row.progress ?? 0" :status="row.status === 'FAILED' ? 'exception' : undefined" /></template>
      </el-table-column>
      <el-table-column label="结果" min-width="160">
        <template #default="{ row }">成功 {{ row.successCount ?? 0 }} / 失败 {{ row.failureCount ?? 0 }} / 取消 {{ row.cancelledCount ?? 0 }}</template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="最后变化" min-width="180" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button v-if="canCancel(row.status)" link type="warning" @click.stop="cancelTask(row.id)">取消</el-button>
          <el-button v-if="canRetry(row.status)" link type="primary" @click.stop="retryTask(row.id)">重试</el-button>
        </template>
      </el-table-column>
    </el-table></div>
    <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="prev, pager, next" @current-change="loadTasks" />

    <el-drawer v-model="drawerVisible" title="任务明细" size="60%">
      <el-descriptions v-if="selectedTask" :column="2" border>
        <el-descriptions-item label="任务 ID">{{ selectedTask.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ taskStatusLabel(selectedTask.status) }}</el-descriptions-item>
        <el-descriptions-item label="阶段">{{ selectedTask.stage || '—' }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ selectedTask.attempt ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="错误" :span="2">{{ selectedTask.errorMessage || '—' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="taskItems" row-key="id">
        <el-table-column prop="targetType" label="目标类型" />
        <el-table-column prop="targetId" label="目标 ID" />
        <el-table-column label="状态"><template #default="{ row }">{{ taskStatusLabel(row.status) }}</template></el-table-column>
        <el-table-column prop="progress" label="进度" />
        <el-table-column prop="errorMessage" label="错误" min-width="220" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { trackedTaskApi } from '@/services/management-capabilities'
import { MANAGEMENT_TASK_TYPES, managementTaskStatusLabel, managementTaskTypeLabel } from '@/utils/management-task'
import type { ManagementTaskItemVO, ManagementTaskStatus, ManagementTaskType, ManagementTaskVO } from '@/types'

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

function errorMessage(reason: unknown): string {
  if (axios.isAxiosError<{ message?: string }>(reason)) return reason.response?.data?.message ?? reason.message
  return reason instanceof Error ? reason.message : '未知错误'
}
function taskStatusLabel(status: ManagementTaskStatus): string { return managementTaskStatusLabel(status) }
function taskStatusTone(status: ManagementTaskStatus): 'success' | 'warning' | 'danger' | 'info' { if (status === 'SUCCEEDED') return 'success'; if (status === 'FAILED' || status === 'PARTIALLY_SUCCEEDED') return 'danger'; if (status === 'RUNNING' || status === 'CANCELLING') return 'warning'; return 'info' }
function canCancel(status: ManagementTaskStatus): boolean { return ['QUEUED', 'RUNNING'].includes(status) }
function canRetry(status: ManagementTaskStatus): boolean { return ['FAILED', 'CANCELLED', 'PARTIALLY_SUCCEEDED'].includes(status) }
async function loadTasks(silent = false): Promise<void> { if (!silent) loading.value = true; error.value = ''; try { const response = await trackedTaskApi.list(query); tasks.value = response.data.records; total.value = response.data.total; updatedAt.value = new Date().toLocaleTimeString() } catch (reason: unknown) { error.value = errorMessage(reason) } finally { if (!silent) loading.value = false } }
function resetAndLoad(): void { query.page = 1; void loadTasks() }
function applyTarget(): void { const parsed = Number(targetIdInput.value); query.targetId = Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined; resetAndLoad() }
async function openTask(task: ManagementTaskVO): Promise<void> { selectedTask.value = task; drawerVisible.value = true; try { taskItems.value = (await trackedTaskApi.getItems(task.id)).data } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function cancelTask(id: number): Promise<void> { try { await trackedTaskApi.cancel(id); ElMessage.success('已请求取消'); await loadTasks() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function retryTask(id: number): Promise<void> { try { await trackedTaskApi.retry(id); ElMessage.success('已重新入队'); await loadTasks() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }

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
.table-scroll { width: 100%; min-width: 0; overflow-x: auto; }
.table-scroll :deep(.el-table) { min-width: 1100px; }
@media (max-width: 900px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 480px) {
  .summary-grid { grid-template-columns: minmax(0, 1fr); }
  .filters :deep(.el-input), .filters :deep(.el-select) { width: 100%; }
}
</style>
