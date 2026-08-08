<template>
  <div class="task-center-page">
    <header class="page-header">
      <div class="header-left">
        <h1 class="page-title">任务中心</h1>
        <p v-if="store.lastUpdated" class="page-subtitle">
          最后更新 {{ formatRelative(store.lastUpdated) }}
          <span v-if="store.polling" class="polling-dot" />
        </p>
      </div>
      <div class="header-actions">
        <button class="ghost-btn" @click="refresh">刷新</button>
        <button class="primary-btn" @click="router.push('/manage/import')">+ 新建导入</button>
        <button class="primary-btn recovery-btn" @click="onStartRecovery">从存储恢复数据库记录</button>
      </div>
    </header>

    <section class="task-summary" aria-label="任务统计">
      <article><span>进行中任务</span><strong>{{ activeCount }}</strong><small>实时队列</small></article>
      <article><span>等待处理</span><strong>{{ store.activeTasks.filter((task) => task.status === 'PENDING').length }}</strong><small>等待 Worker</small></article>
      <article class="task-summary--danger"><span>失败任务</span><strong>{{ failedCount }}</strong><small>需要关注</small></article>
      <article class="task-summary--success"><span>已完成</span><strong>{{ completedCount.toLocaleString() }}</strong><small>历史导入任务</small></article>
    </section>

    <div v-if="batchId" class="batch-filter-banner">
      <span class="batch-label">批次导入 {{ batchId.slice(0, 8) }}{{ batchId.length > 8 ? '...' : '' }}</span>
      <router-link to="/manage/import/tasks" class="batch-clear-link">返回全部任务</router-link>
    </div>

    <div v-if="store.error" class="state error">
      <el-icon :size="32"><WarningFilled /></el-icon>
      <span>{{ store.error }}</span>
      <button class="ghost-btn" @click="refresh">重试</button>
    </div>

    <!-- 进行中 -->
    <section v-if="store.activeTasks.length > 0" class="task-section">
      <h2 class="section-title">
        进行中
        <span class="section-count">{{ store.activeTasks.length }}</span>
      </h2>
      <div class="task-cards">
        <TaskCard
          v-for="task in store.activeTasks"
          :key="task.id"
          :task="task"
          variant="active"
          @cancel="onCancel"
          @retry="onRetry"
          @read="onRead"
        />
      </div>
    </section>

    <!-- 失败 -->
    <section v-if="store.failedTasks.length > 0" class="task-section">
      <h2 class="section-title">
        失败
        <span class="section-count">{{ store.failedTasks.length }}</span>
      </h2>
      <div class="task-cards">
        <TaskCard
          v-for="task in store.failedTasks"
          :key="task.id"
          :task="task"
          variant="failed"
          @cancel="onCancel"
          @retry="onRetry"
          @read="onRead"
        />
      </div>
    </section>

    <!-- 已完成 -->
    <section class="task-section">
      <h2 class="section-title">
        已完成
        <span class="section-count">{{ store.completedTotal }}</span>
      </h2>
      <div v-if="store.completedTasks.length > 0" class="task-cards">
        <TaskCard
          v-for="task in store.completedTasks"
          :key="task.id"
          :task="task"
          variant="done"
          @cancel="onCancel"
          @retry="onRetry"
          @read="onRead"
        />
        <el-pagination
          class="pagination-bar"
          layout="prev, pager, next"
          :total="store.completedTotal"
          :page-size="store.completedPageSize"
          :current-page="store.completedPage"
          @current-change="onCompletedPageChange"
        />
      </div>
      <div v-else class="state empty">
        <el-icon :size="48"><CircleCheckFilled /></el-icon>
        <span>暂无已完成任务</span>
        <button class="primary-btn" @click="router.push('/manage/import')">开始第一次导入</button>
      </div>
    </section>

    <!-- 导出任务分隔 -->
    <hr class="section-divider" />

    <!-- 存储恢复进行中 -->
    <section v-if="recoveryStore.activeTasks.length > 0" class="task-section">
      <h2 class="section-title">
        存储恢复进行中
        <span class="section-count">{{ recoveryStore.activeTasks.length }}</span>
      </h2>
      <div class="task-cards">
        <RecoveryTaskCard
          v-for="task in recoveryStore.activeTasks"
          :key="task.id"
          :task="task"
          variant="active"
          @retry="onRecoveryRetry"
        />
      </div>
    </section>

    <!-- 存储恢复失败 -->
    <section v-if="recoveryStore.failedTasks.length > 0" class="task-section">
      <h2 class="section-title">
        存储恢复失败
        <span class="section-count">{{ recoveryStore.failedTasks.length }}</span>
      </h2>
      <div class="task-cards">
        <RecoveryTaskCard
          v-for="task in recoveryStore.failedTasks"
          :key="task.id"
          :task="task"
          variant="failed"
          @retry="onRecoveryRetry"
        />
      </div>
    </section>

    <!-- 存储恢复已完成 -->
    <section v-if="recoveryStore.completedTasks.length > 0" class="task-section">
      <h2 class="section-title">
        存储恢复已完成
        <span class="section-count">{{ recoveryStore.completedTasks.length }}</span>
      </h2>
      <div class="task-cards">
        <RecoveryTaskCard
          v-for="task in recoveryStore.completedTasks"
          :key="task.id"
          :task="task"
          variant="done"
          @retry="onRecoveryRetry"
        />
      </div>
    </section>

    <hr class="section-divider" />

    <!-- 导出进行中 -->
    <section v-if="activeExportTasks.length > 0" class="task-section">
      <h2 class="section-title">
        导出进行中
        <span class="section-count">{{ activeExportTasks.length }}</span>
      </h2>
      <div class="task-cards">
        <ExportTaskCard
          v-for="task in activeExportTasks"
          :key="task.taskId"
          :task="task"
          variant="active"
        />
      </div>
    </section>

    <!-- 导出失败 -->
    <section v-if="failedExportTasks.length > 0" class="task-section">
      <h2 class="section-title">
        导出失败
        <span class="section-count">{{ failedExportTasks.length }}</span>
      </h2>
      <div class="task-cards">
        <ExportTaskCard
          v-for="task in failedExportTasks"
          :key="task.taskId"
          :task="task"
          variant="failed"
        />
      </div>
    </section>

    <!-- 导出已完成 -->
    <section class="task-section">
      <h2 class="section-title">
        导出已完成
        <span class="section-count">{{ completedExportTasks.length }}</span>
      </h2>
      <div v-if="completedExportTasks.length > 0" class="task-cards">
        <ExportTaskCard
          v-for="task in completedExportTasks.slice(0, 10)"
          :key="task.taskId"
          :task="task"
          variant="done"
        />
      </div>
      <div v-else class="state empty">
        <span>暂无导出任务</span>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, computed, watch, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled, CircleCheckFilled } from '@element-plus/icons-vue'
import { useImportStore } from '@/stores/management/import'
import { useRecoveryStore } from '@/stores/management/recovery'
import type { ImportTaskVO, ExportTaskVO } from '@/types'
import { exportApi } from '@/services/api'
import TaskCard from '@/components/management/task/TaskCard.vue'
import ExportTaskCard from '@/components/management/task/ExportTaskCard.vue'
import RecoveryTaskCard from '@/components/management/task/RecoveryTaskCard.vue'

const router = useRouter()
const route = useRoute()
const store = useImportStore()
const recoveryStore = useRecoveryStore()

const batchId = computed(() => (route.query.batchId as string) || '')
const activeCount = computed(() => store.activeTasks.length)
const failedCount = computed(() => store.failedTasks.length)
const completedCount = computed(() => store.completedTotal)

function formatRelative(ts: number): string {
  const diff = Date.now() - ts
  if (diff < 5000) return '刚刚'
  if (diff < 60000) return `${Math.floor(diff / 1000)} 秒前`
  return new Date(ts).toLocaleTimeString('zh-CN')
}

async function refresh() {
  await store.fetchList(batchId.value ? { batchId: batchId.value } : undefined)
  if (store.hasActive) store.startPolling()
}

async function onCancel(id: number) {
  try {
    await store.cancel(id)
    ElMessage.success('已取消')
  } catch {
    ElMessage.error('取消失败')
  }
}

async function onRetry(id: number) {
  try {
    await store.retry(id)
    ElMessage.success('已重新加入队列')
  } catch {
    ElMessage.error('重试失败')
  }
}

function onRead(task: ImportTaskVO) {
  if (!task.comicId) return
  router.push(`/comic/${task.comicId}`)
}

onMounted(async () => {
  await store.fetchList(batchId.value ? { batchId: batchId.value } : undefined)
  await store.fetchCompletedTasks(1)
  if (store.hasActive) store.startPolling()

  await recoveryStore.fetchTasks()
  if (recoveryStore.hasActive) recoveryStore.startPolling()
})

watch(batchId, async () => {
  await store.fetchList(batchId.value ? { batchId: batchId.value } : undefined)
  await store.fetchCompletedTasks(1)
  if (store.hasActive) store.startPolling()
})

function onCompletedPageChange(page: number) {
  store.fetchCompletedTasks(page)
}

// ========== Recovery tasks ==========

async function onStartRecovery() {
  try {
    await ElMessageBox.confirm(
      '此操作将扫描 HQ 存储目录，恢复已从数据库删除但文件仍存在的漫画。已有记录的漫画将被跳过，无 metadata 的漫画将创建占位记录。此过程可能需要几分钟。是否继续？',
      '从存储恢复数据库记录',
      {
        confirmButtonText: '开始恢复',
        cancelButtonText: '取消',
        type: 'info',
      }
    )
  } catch {
    return
  }

  try {
    await recoveryStore.createTask()
    ElMessage.success('恢复任务已创建，正在扫描存储目录')
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '创建恢复任务失败'
    ElMessage.error(msg)
  }
}

async function onRecoveryRetry(id: number) {
  try {
    await recoveryStore.retryTask(id)
    ElMessage.success('恢复任务已重新执行')
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '重试失败'
    ElMessage.error(msg)
  }
}

// ========== Export tasks ==========

const exportTasks = ref<ExportTaskVO[]>([])
const exportPollTimer = ref<ReturnType<typeof setInterval> | null>(null)

const TERMINAL_EXPORT_STATUSES = new Set(['SUCCESS', 'FAILED'])

const activeExportTasks = computed(() =>
  exportTasks.value.filter(t => !TERMINAL_EXPORT_STATUSES.has(t.status))
)
const failedExportTasks = computed(() =>
  exportTasks.value.filter(t => t.status === 'FAILED')
)
const completedExportTasks = computed(() =>
  exportTasks.value.filter(t => t.status === 'SUCCESS')
)

function hasActiveExports(): boolean {
  return exportTasks.value.some(t => !TERMINAL_EXPORT_STATUSES.has(t.status))
}

async function fetchExportTasks() {
  // 收集已知的 comicId
  const comicIds = [...new Set(exportTasks.value.map(t => t.comicId))]
  if (comicIds.length === 0) return

  try {
    const results = await Promise.allSettled(
      comicIds.map(id => exportApi.listExports(id))
    )
    const allTasks: ExportTaskVO[] = []
    for (const r of results) {
      if (r.status === 'fulfilled') {
        const data = (r.value as { data?: ExportTaskVO[] })?.data
        if (Array.isArray(data)) {
          allTasks.push(...data)
        }
      }
    }
    // 去重：同一 taskId 取最新
    const map = new Map<number, ExportTaskVO>()
    for (const t of allTasks) {
      map.set(t.taskId, t)
    }
    exportTasks.value = [...map.values()]
  } catch {
    // 静默处理轮询错误
  }
}

function startExportPolling() {
  if (exportPollTimer.value) return
  exportPollTimer.value = setInterval(() => {
    fetchExportTasks().then(() => {
      if (!hasActiveExports()) {
        stopExportPolling()
      }
    })
  }, 5000)
}

function stopExportPolling() {
  if (exportPollTimer.value) {
    clearInterval(exportPollTimer.value)
    exportPollTimer.value = null
  }
}

// 初始拉取一次，有进行中任务则启动轮询
fetchExportTasks().then(() => {
  if (hasActiveExports()) {
    startExportPolling()
  }
})

onBeforeUnmount(() => {
  // 离开页面不停止轮询：TopNav 全局依赖此 store 维持红点徽章
  // 轮询会在没有进行中任务时自动停止
  stopExportPolling()
  recoveryStore.stopPolling()
})
</script>

<style scoped>
.task-center-page {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 0 0 var(--space-3xl);
  background: var(--bg-primary);
  min-height: 100%;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--space-2xl);
  gap: var(--space-base);
  flex-wrap: wrap;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.page-subtitle {
  font-size: 12px;
  color: var(--text-muted);
  margin: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.polling-dot {
  width: 6px;
  height: 6px;
  background: var(--accent);
  border-radius: 50%;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.7); }
}

.header-actions {
  display: flex;
  gap: var(--space-sm);
}

/* Section divider */
.section-divider {
  border: none;
  border-top: 1px solid var(--border);
  margin: var(--space-xl) 0;
}

/* Batch filter */
.batch-filter-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-base);
  margin-bottom: var(--space-2xl);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.batch-label {
  font-size: 13px;
  color: var(--text-secondary);
}

.batch-clear-link {
  font-size: 13px;
  color: var(--accent);
  text-decoration: none;
  font-weight: 500;
}

.batch-clear-link:hover {
  text-decoration: underline;
}

.task-section {
  margin-bottom: var(--space-2xl);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-base);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.section-count {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  background: var(--bg-surface);
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--border);
}

.task-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-base);
}

.task-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-8);
}

.task-summary article {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-height: 128px;
  padding: var(--space-5);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
}

.task-summary span,
.task-summary small { color: var(--text-muted); font-size: var(--text-sm); }
.task-summary strong { color: var(--text-primary); font-size: 2.5rem; font-variant-numeric: tabular-nums; }
.task-summary--danger strong { color: var(--danger); }
.task-summary--success strong { color: var(--success); }

/* States */
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-base);
  padding: var(--space-3xl) 0;
  color: var(--text-muted);
  text-align: center;
}

.state.error {
  color: var(--danger);
  background: var(--bg-surface);
  border-radius: var(--radius-md);
  padding: var(--space-xl);
  margin-bottom: var(--space-xl);
}

.state.empty {
  padding: var(--space-3xl) 0;
}

.state.empty span {
  font-size: 14px;
}

/* Buttons */
.primary-btn {
  padding: 8px 16px;
  background: var(--accent);
  color: var(--text-primary);
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 150ms ease;
}

.primary-btn:hover {
  background: var(--accent-hover);
}

.recovery-btn {
  background: var(--success);
  border-color: var(--success);
}

.recovery-btn:hover {
  background: color-mix(in srgb, var(--success) 85%, white);
}

.ghost-btn {
  padding: 8px 16px;
  background: transparent;
  color: var(--text-primary);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 150ms ease;
}

.ghost-btn:hover {
  background: var(--bg-surface);
  border-color: var(--text-muted);
}

.pagination-bar {
  margin-top: var(--space-base);
  display: flex;
  justify-content: center;
}
</style>
