<template>
  <div class="management-list-page">
    <header class="page-header">
      <div>
        <p class="page-eyebrow">OPERATIONS / TASKS</p>
        <h1 class="page-title">管理任务</h1>
        <p class="page-subtitle">查看回收、LQ、转码、元数据刷新等统一管理任务。</p>
      </div>
      <button class="ghost-btn" :disabled="loading" @click="loadFirstPage">刷新</button>
    </header>
    <div v-if="error" class="state error">{{ error }}</div>
    <div v-else-if="loading && tasks.length === 0" class="state loading">加载中...</div>
    <div v-else-if="tasks.length === 0" class="state empty">暂无管理任务</div>
    <section v-else class="task-list">
      <article v-for="task in tasks" :key="task.id" class="task-row">
        <div class="task-copy">
          <div class="task-title-line">
            <strong>#{{ task.id }} · {{ task.operation || task.taskType }}</strong>
            <span class="status-badge">{{ statusLabel(task.status) }}</span>
          </div>
          <div class="task-facts">
            <span>类型：{{ taskTypeLabel(task.taskType) }}</span>
            <span>目标：{{ task.targetType }}{{ task.isBatch ? ' · 批量' : '' }}</span>
            <span v-if="task.stage">阶段：{{ task.stage }}</span>
            <span>进度：{{ task.progress ?? 0 }}%</span>
            <span v-if="task.totalCount != null">数量：{{ task.successCount ?? 0 }} 成功 / {{ task.failureCount ?? 0 }} 失败 / {{ task.cancelledCount ?? 0 }} 取消 / 共 {{ task.totalCount }}</span>
            <span>尝试：第 {{ task.attempt ?? 0 }} 次 · 版本 {{ task.version ?? 0 }}</span>
          </div>
          <div class="task-times">
            <span>创建：{{ formatDate(task.createdAt) }}</span>
            <span v-if="task.startedAt">开始：{{ formatDate(task.startedAt) }}</span>
            <span v-if="task.completedAt">完成：{{ formatDate(task.completedAt) }}</span>
            <span>更新：{{ formatDate(task.updatedAt) }}</span>
          </div>
          <small v-if="task.batchId">批次：{{ task.batchId }}</small>
          <small v-if="task.errorMessage">{{ task.errorMessage }}</small>
        </div>
        <div class="task-actions">
          <button v-if="canCancel(task.status)" class="ghost-btn" :disabled="busyId === task.id" @click="cancel(task.id)">取消</button>
          <button v-if="canRetry(task.status)" class="ghost-btn" :disabled="busyId === task.id" @click="retry(task.id)">重试</button>
        </div>
      </article>
      <div ref="sentinel" class="infinite-sentinel" aria-live="polite">
        <span v-if="infiniteLoading">正在加载更多...</span>
        <span v-else-if="!infiniteHasMore">已加载全部任务</span>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { managementTaskApi } from '@/services/api'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import type { ManagementTaskStatus, ManagementTaskVO } from '@/types'

const tasks = ref<ManagementTaskVO[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)

const { sentinel, loading: infiniteLoading, hasMore: infiniteHasMore, reset: resetInfinite } = useInfiniteScroll({
  loadMore: async () => {
    if (loading.value || tasks.value.length >= total.value) return false
    page.value += 1
    await loadPage(true)
    return tasks.value.length < total.value
  },
})
void sentinel

function canCancel(status: ManagementTaskStatus): boolean {
  return status === 'QUEUED' || status === 'RUNNING'
}

function canRetry(status: ManagementTaskStatus): boolean {
  return status === 'FAILED' || status === 'CANCELLED' || status === 'PARTIALLY_SUCCEEDED'
}

function statusLabel(status: ManagementTaskStatus): string {
  const labels: Record<ManagementTaskStatus, string> = {
    QUEUED: '排队中',
    RUNNING: '执行中',
    CANCELLING: '取消中',
    CANCELLED: '已取消',
    SUCCEEDED: '已成功',
    PARTIALLY_SUCCEEDED: '部分成功',
    FAILED: '失败',
  }
  return labels[status]
}

function taskTypeLabel(taskType: ManagementTaskVO['taskType']): string {
  const labels: Record<string, string> = {
    LQ_GENERATE: '生成 LQ',
    HQ_DELETE: '删除 HQ',
    VIDEO_TRANSCODE: '视频转码',
    METADATA_REFRESH: '刷新元数据',
    COMIC_DELETE: '回收漫画',
    COMIC_RESTORE: '恢复漫画',
    COMIC_PURGE: '永久清理漫画',
  }
  return labels[taskType] ?? taskType
}

function formatDate(value: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

async function loadPage(append = false) {
  loading.value = true
  error.value = ''
  try {
    const response = await managementTaskApi.list({ page: page.value, size: 24 })
    const records = response.data.records ?? []
    tasks.value = append ? [...tasks.value, ...records] : records
    total.value = response.data.total ?? 0
  } catch (cause: unknown) {
    error.value = cause instanceof Error ? cause.message : '加载管理任务失败'
    if (!append) tasks.value = []
  } finally {
    loading.value = false
  }
}

async function loadFirstPage() {
  page.value = 1
  resetInfinite()
  await loadPage()
}

async function cancel(id: number) {
  await execute(id, () => managementTaskApi.cancel(id), '任务已取消')
}

async function retry(id: number) {
  await execute(id, () => managementTaskApi.retry(id), '任务已重试')
}

async function execute(id: number, action: () => Promise<unknown>, message: string) {
  busyId.value = id
  try {
    await action()
    ElMessage.success(message)
    await loadFirstPage()
  } catch (cause: unknown) {
    ElMessage.error(cause instanceof Error ? cause.message : '操作失败')
  } finally {
    busyId.value = null
  }
}

onMounted(loadFirstPage)
</script>

<style scoped>
.management-list-page { max-width: 1100px; }
.page-header { display: flex; justify-content: space-between; gap: 24px; margin-bottom: 24px; }
.page-eyebrow { color: var(--accent); font-size: 11px; letter-spacing: .14em; }
.page-title { margin: 6px 0; color: var(--text-primary); }
.page-subtitle { margin: 0; color: var(--text-secondary); }
.task-list { display: grid; gap: 8px; }
.task-row { display: flex; align-items: center; gap: 16px; padding: 16px; border: 1px solid var(--border); background: var(--bg-surface); }
.task-copy { display: grid; flex: 1; gap: 6px; min-width: 0; }
.task-copy strong { color: var(--text-primary); }
.task-title-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.status-badge { padding: 3px 8px; border: 1px solid var(--border); color: var(--text-secondary); font-size: 12px; }
.task-facts, .task-times { display: flex; flex-wrap: wrap; gap: 6px 16px; color: var(--text-secondary); font-size: 12px; }
.task-copy small { color: var(--text-secondary); overflow-wrap: anywhere; }
.task-actions { display: flex; gap: 8px; }
.infinite-sentinel { padding: 18px; color: var(--text-muted); text-align: center; }
</style>
