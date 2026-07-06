<template>
  <div class="task-center-page">
    <header class="page-header">
      <h1 class="page-title">任务中心</h1>
      <div class="header-actions">
        <el-button type="primary" @click="router.push('/import')">新建导入</el-button>
        <el-button @click="refresh">刷新</el-button>
      </div>
    </header>

    <!-- 进行中 -->
    <section class="task-section" v-if="activeTasks.length > 0">
      <h2 class="section-title">进行中 ({{ activeTasks.length }})</h2>
      <div class="task-cards">
        <el-card v-for="task in activeTasks" :key="task.id" class="task-card" shadow="hover">
          <div class="task-header">
            <span class="task-comic">{{ task.comicId ? '漫画#' + task.comicId : '新任务' }}</span>
            <el-tag :type="STATUS_COLOR_MAP[task.status] || 'info'" size="small">
              {{ statusLabel(task.status) }}
            </el-tag>
          </div>
          <el-progress :percentage="task.progress" :stroke-width="8" class="task-progress" />
          <div class="task-meta">
            <span>{{ formatTime(task.createdAt) }}</span>
            <el-button link type="danger" size="small" @click="cancelTask(task.id)">取消</el-button>
          </div>
        </el-card>
      </div>
    </section>

    <!-- 失败 -->
    <section class="task-section" v-if="failedTasks.length > 0">
      <h2 class="section-title">失败 ({{ failedTasks.length }})</h2>
      <div class="task-cards">
        <el-card v-for="task in failedTasks" :key="task.id" class="task-card task-failed" shadow="hover">
          <div class="task-header">
            <span class="task-comic">{{ task.comicId ? '漫画#' + task.comicId : '' }}</span>
            <el-tag type="danger" size="small">失败</el-tag>
          </div>
          <p class="task-error">{{ task.errorMessage || '未知错误' }}</p>
          <div class="task-meta">
            <span>{{ task.retryCount }} 次重试</span>
            <el-button link type="primary" size="small" @click="retryTask(task.id)">重试</el-button>
          </div>
        </el-card>
      </div>
    </section>

    <!-- 已完成 -->
    <section class="task-section">
      <h2 class="section-title">已完成 ({{ completedTasks.length }})</h2>
      <div class="task-cards" v-if="completedTasks.length > 0">
        <el-card v-for="task in completedTasks" :key="task.id" class="task-card task-done" shadow="hover">
          <div class="task-header">
            <span class="task-comic">{{ task.comicId ? '漫画#' + task.comicId : '' }}</span>
            <el-tag type="success" size="small">完成</el-tag>
          </div>
          <div class="task-meta">
            <span>{{ formatTime(task.createdAt) }}</span>
            <span v-if="task.durationMs">{{ (task.durationMs / 1000).toFixed(1) }}s</span>
          </div>
        </el-card>
      </div>
      <el-empty v-else description="暂无已完成任务" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { importApi } from '@/services/api'
import { STATUS_COLOR_MAP } from '@/types'
import type { ImportTaskVO } from '@/types'

const router = useRouter()

const tasks = ref<ImportTaskVO[]>([])
let pollTimer: ReturnType<typeof setInterval> | null = null

const STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中', PARSING: '解析中', IMPORTING: '导入中',
  SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消',
  DOWNLOADING: '下载中', EXTRACTING: '解压中',
}

const activeTasks = computed(() => tasks.value.filter(t =>
  !['SUCCESS', 'FAILED', 'CANCELLED'].includes(t.status)))
const failedTasks = computed(() => tasks.value.filter(t => t.status === 'FAILED'))
const completedTasks = computed(() => tasks.value.filter(t => t.status === 'SUCCESS'))

function statusLabel(s: string) { return STATUS_LABELS[s] || s }

function formatTime(ts: string) {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN')
}

async function refresh() {
  try {
    const res = await importApi.list({ page: 1, size: 50 })
    tasks.value = res.data.records || []
  } catch { /* ignore */ }
}

async function cancelTask(id: number) {
  try { await importApi.cancel(id); ElMessage.success('已取消'); refresh() }
  catch { ElMessage.error('取消失败') }
}

async function retryTask(id: number) {
  try { await importApi.retry(id); ElMessage.success('已重试'); refresh() }
  catch { ElMessage.error('重试失败') }
}

onMounted(async () => {
  await refresh()
  pollTimer = setInterval(refresh, 3000)
})

onBeforeUnmount(() => { if (pollTimer) clearInterval(pollTimer) })
</script>

<style scoped>
.task-center-page { padding: 24px; max-width: 900px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-title { font-size: 28px; font-weight: 700; color: var(--text-h); margin: 0; }
.section-title { font-size: 16px; font-weight: 600; color: var(--text-h); margin: 0 0 12px; }
.task-section { margin-bottom: 32px; }
.task-cards { display: flex; flex-direction: column; gap: 10px; }
.task-card { border-left: 4px solid var(--accent); }
.task-failed { border-left-color: #f56c6c; }
.task-done { border-left-color: #67c23a; }
.task-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.task-comic { font-weight: 600; color: var(--text-h); }
.task-progress { margin-bottom: 10px; }
.task-error { font-size: 13px; color: #f56c6c; margin: 0 0 8px; }
.task-meta { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--text); }
</style>
