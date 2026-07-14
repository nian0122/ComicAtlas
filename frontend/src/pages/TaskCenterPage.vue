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
        <button class="primary-btn" @click="router.push('/import')">+ 新建导入</button>
      </div>
    </header>

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
        <span class="section-count">{{ store.completedTasks.length }}</span>
      </h2>
      <div v-if="store.completedTasks.length > 0" class="task-cards">
        <TaskCard
          v-for="task in store.completedTasks.slice(0, 10)"
          :key="task.id"
          :task="task"
          variant="done"
          @cancel="onCancel"
          @retry="onRetry"
          @read="onRead"
        />
      </div>
      <div v-else class="state empty">
        <el-icon :size="48"><CircleCheckFilled /></el-icon>
        <span>暂无已完成任务</span>
        <button class="primary-btn" @click="router.push('/import')">开始第一次导入</button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { WarningFilled, CircleCheckFilled } from '@element-plus/icons-vue'
import { useImportStore } from '@/stores/import-store'
import type { ImportTaskVO } from '@/types'
import TaskCard from '@/components/task/TaskCard.vue'

const router = useRouter()
const store = useImportStore()

function formatRelative(ts: number): string {
  const diff = Date.now() - ts
  if (diff < 5000) return '刚刚'
  if (diff < 60000) return `${Math.floor(diff / 1000)} 秒前`
  return new Date(ts).toLocaleTimeString('zh-CN')
}

async function refresh() {
  await store.fetchList()
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
  router.push(`/comics/${task.comicId}`)
}

onMounted(async () => {
  await store.fetchList()
  if (store.hasActive) store.startPolling()
})

onBeforeUnmount(() => {
  // 离开页面不停止轮询：TopNav 全局依赖此 store 维持红点徽章
  // 轮询会在没有进行中任务时自动停止
})
</script>

<style scoped>
.task-center-page {
  max-width: 960px;
  margin: 0 auto;
  padding: var(--space-xl) var(--space-lg) var(--space-3xl);
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
  display: flex;
  flex-direction: column;
  gap: var(--space-base);
}

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
</style>
