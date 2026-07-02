<template>
  <div class="import-page">
    <header class="page-header">
      <h1 class="page-title">导入任务</h1>
    </header>

    <div class="import-form">
      <el-select v-model="sourceType" class="type-select">
        <el-option label="ZIP 文件" value="ZIP" />
        <el-option label="目录注册" value="REGISTER" />
      </el-select>
      <el-input
        v-model="sourcePath"
        :placeholder="sourceType === 'ZIP' ? 'ZIP 文件路径...' : '漫画目录路径...'"
        class="url-input"
        @keyup.enter="doImport"
      />
      <el-button type="primary" :loading="importing" @click="doImport">
        开始导入
      </el-button>
    </div>

    <el-table
      :data="store.tasks"
      v-loading="store.loading"
      class="task-table"
      empty-text="暂无导入任务"
    >
      <el-table-column label="URL" min-width="240">
        <template #default="{ row }">
          <el-tooltip :content="row.sourceRef" placement="top" :show-after="500">
            <span class="url-cell">{{ truncateUrl(row.sourceRef) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag
            :type="STATUS_COLOR_MAP[row.status] || 'info'"
            size="small"
          >
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="进度" width="180">
        <template #default="{ row }">
          <el-progress
            :percentage="row.progress"
            :stroke-width="8"
            :status="row.status === 'FAILED' ? 'exception' : undefined"
          />
        </template>
      </el-table-column>

      <el-table-column label="下载方式" width="100">
        <template #default="{ row }">
          <span class="cell-text">{{ row.downloadMethod || '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="速度" width="100">
        <template #default="{ row }">
          <span class="cell-text">{{ formatSpeed(row.downloadSpeed) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">
          <span class="cell-text">{{ formatTime(row.createdAt) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <div class="action-btns">
            <el-button
              link
              type="primary"
              size="small"
              @click="showDetail(row)"
            >
              详情
            </el-button>
            <el-button
              v-if="row.status === 'PENDING' || row.status === 'DOWNLOADING'"
              link
              type="warning"
              size="small"
              @click="cancelTask(row.id)"
            >
              取消
            </el-button>
            <el-button
              v-if="row.status === 'FAILED'"
              link
              type="success"
              size="small"
              @click="retryTask(row.id)"
            >
              重试
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <!-- Detail Drawer -->
    <el-drawer
      v-model="drawerVisible"
      title="任务详情"
      direction="rtl"
      size="400px"
    >
      <template v-if="detailTask">
        <div class="detail-section">
          <h3 class="detail-label">URL</h3>
          <p class="detail-value break-all">{{ detailTask.sourceRef }}</p>
        </div>
        <div class="detail-section">
          <h3 class="detail-label">状态</h3>
          <el-tag :type="STATUS_COLOR_MAP[detailTask.status] || 'info'">
            {{ statusLabel(detailTask.status) }}
          </el-tag>
        </div>
        <div class="detail-section">
          <h3 class="detail-label">进度</h3>
          <el-progress :percentage="detailTask.progress" :stroke-width="8" />
        </div>
        <div class="detail-section">
          <h3 class="detail-label">下载进度</h3>
          <p class="detail-value">
            {{ detailTask.downloadedPages }} / {{ detailTask.totalPages }} 页
          </p>
        </div>
        <div class="detail-section">
          <h3 class="detail-label">下载方式</h3>
          <p class="detail-value">{{ detailTask.downloadMethod || '-' }}</p>
        </div>
        <div class="detail-section">
          <h3 class="detail-label">速度</h3>
          <p class="detail-value">{{ formatSpeed(detailTask.downloadSpeed) }}</p>
        </div>
        <div class="detail-section">
          <h3 class="detail-label">预计剩余时间</h3>
          <p class="detail-value">{{ formatEta(detailTask.etaSeconds) }}</p>
        </div>
        <div class="detail-section">
          <h3 class="detail-label">重试次数</h3>
          <p class="detail-value">{{ detailTask.retryCount }}</p>
        </div>
        <div class="detail-section" v-if="detailTask.errorMessage">
          <h3 class="detail-label">错误信息</h3>
          <p class="detail-value detail-error">{{ detailTask.errorMessage }}</p>
        </div>
        <div class="detail-section">
          <h3 class="detail-label">创建时间</h3>
          <p class="detail-value">{{ formatTime(detailTask.createdAt) }}</p>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { useImportStore } from '@/stores/import-store'
import { STATUS_COLOR_MAP } from '@/types'
import type { ImportTaskVO } from '@/types'

const store = useImportStore()

const sourceType = ref('REGISTER')
const sourcePath = ref('')
const importing = ref(false)
const drawerVisible = ref(false)
const detailTask = ref<ImportTaskVO | null>(null)

let pollTimer: ReturnType<typeof setInterval> | null = null

const STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中',
  DOWNLOADING: '下载中',
  EXTRACTING: '解压中',
  PARSING: '解析中',
  IMPORTING: '导入中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
}

function statusLabel(status: string): string {
  return STATUS_LABELS[status] || status
}

function truncateUrl(url: string): string {
  return url.length > 60 ? url.slice(0, 60) + '...' : url
}

function formatSpeed(speed: number): string {
  if (!speed || speed <= 0) return '-'
  if (speed > 1024 * 1024) return (speed / (1024 * 1024)).toFixed(1) + ' MB/s'
  if (speed > 1024) return (speed / 1024).toFixed(1) + ' KB/s'
  return speed.toFixed(0) + ' B/s'
}

function formatEta(seconds: number): string {
  if (!seconds || seconds <= 0) return '-'
  if (seconds < 60) return `${seconds}秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}分${seconds % 60}秒`
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return `${h}时${m}分`
}

function formatTime(ts: string): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

async function doImport() {
  const path = sourcePath.value.trim()
  if (!path) {
    ElMessage.warning('请输入路径')
    return
  }
  importing.value = true
  try {
    await store.create(sourceType.value, path)
    sourcePath.value = ''
    ElMessage.success('导入任务已创建')
  } catch {
    ElMessage.error('创建导入任务失败')
  } finally {
    importing.value = false
  }
}

async function cancelTask(id: number) {
  try {
    await store.cancel(id)
    ElMessage.success('已取消')
  } catch {
    ElMessage.error('取消失败')
  }
}

async function retryTask(id: number) {
  try {
    await store.retry(id)
    ElMessage.success('已重试')
  } catch {
    ElMessage.error('重试失败')
  }
}

function showDetail(task: ImportTaskVO) {
  detailTask.value = task
  drawerVisible.value = true
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    const activeTasks = store.tasks.filter(
      t => t.status !== 'SUCCESS' && t.status !== 'FAILED' && t.status !== 'CANCELLED'
    )
    if (activeTasks.length === 0) {
      stopPolling()
      return
    }
    await store.fetchList()
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(async () => {
  await store.fetchList()
  startPolling()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.import-page {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 20px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0;
}

.import-form {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}

.type-select {
  width: 140px;
  flex-shrink: 0;
}

.url-input {
  flex: 1;
  min-width: 300px;
}

.task-table {
  border-radius: 8px;
  overflow: hidden;
}

.url-cell {
  font-size: 13px;
  color: var(--text-h);
}

.cell-text {
  font-size: 13px;
  color: var(--text);
}

.action-btns {
  display: flex;
  gap: 4px;
}

.detail-section {
  margin-bottom: 20px;
}

.detail-label {
  font-size: 13px;
  color: var(--text);
  margin: 0 0 4px;
  font-weight: 400;
}

.detail-value {
  font-size: 14px;
  color: var(--text-h);
  margin: 0;
}

.detail-error {
  color: #f56c6c;
}

.break-all {
  word-break: break-all;
}
</style>
