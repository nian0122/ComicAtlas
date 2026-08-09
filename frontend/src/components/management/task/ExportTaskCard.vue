<template>
  <article class="export-card" :class="`variant-${variant}`">
    <!-- 左侧状态条 -->
    <div class="status-bar" />

    <div class="card-body">
      <!-- 头部 -->
      <div class="card-header">
        <div class="header-info">
          <h3 class="task-name">导出任务 #{{ task.id }}</h3>
          <div class="task-meta-row">
            <span class="meta-chip">漫画 #{{ task.comicId }}</span>
            <span class="meta-time">{{ formatTime(task.createdAt) }}</span>
          </div>
        </div>
        <span class="status-badge" :class="`status-${task.status.toLowerCase()}`">
          {{ statusLabel }}
        </span>
      </div>

      <!-- 主体内容 - 根据状态切换 -->
      <div class="card-content">
        <!-- SUCCESS: 分卷产物列表 -->
        <template v-if="task.status === 'SUCCESS'">
          <div v-if="artifactsLoading" class="status-block running">
            <el-icon class="is-loading" :size="16"><Loading /></el-icon>
            <span>加载卷列表...</span>
          </div>
          <template v-else>
            <div v-if="artifacts.length > 1" class="artifact-summary">
              <span class="artifact-summary-label">共 {{ artifacts.length }} 卷</span>
              <span class="artifact-summary-size">总大小 {{ formatSize(totalSize) }}</span>
            </div>
            <div v-if="artifacts.length > 0" class="artifact-list">
              <div v-for="a in artifacts" :key="a.index" class="artifact-row">
                <span class="artifact-index">#{{ a.index + 1 }}</span>
                <span class="artifact-name" :title="a.fileName">{{ a.fileName }}</span>
                <span class="artifact-size">{{ formatSize(a.size) }}</span>
                <span class="artifact-path" :title="a.physicalPath">{{ a.physicalPath }}</span>
                <button class="artifact-copy-btn" @click="onCopyVolumePath(a)">复制卷路径</button>
              </div>
            </div>
            <div v-else class="output-row">
              <span class="output-label">输出路径</span>
              <span class="output-value" :title="task.outputPath">{{ task.outputPath || '-' }}</span>
            </div>
          </template>
        </template>

        <!-- RUNNING: spinning -->
        <template v-else-if="task.status === 'RUNNING'">
          <div class="status-block running">
            <el-icon class="is-loading" :size="16"><Loading /></el-icon>
            <span>导出中...</span>
          </div>
        </template>

        <!-- PENDING: gray icon -->
        <template v-else-if="task.status === 'PENDING'">
          <div class="status-block pending">
            <el-icon :size="16"><Clock /></el-icon>
            <span>等待中</span>
          </div>
        </template>

        <!-- FAILED: 错误信息 -->
        <template v-else-if="task.status === 'FAILED'">
          <div class="error-block">
            <p class="error-message">{{ task.errorMsg || '未知错误' }}</p>
          </div>
        </template>
      </div>

      <!-- 操作区 -->
      <div class="card-actions">
        <template v-if="task.status === 'SUCCESS'">
          <button class="action-btn primary" @click="onCopyMainZip">复制主 ZIP 路径</button>
          <button class="action-btn ghost" @click="onOpenDir">打开目录</button>
        </template>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, Clock } from '@element-plus/icons-vue'
import type { ExportArtifactVO, ExportTaskVO } from '@/types'
import { exportApi } from '@/services/api'

const props = defineProps<{
  task: ExportTaskVO
  variant: 'active' | 'failed' | 'done'
}>()

const artifacts = ref<ExportArtifactVO[]>([])
const artifactsLoading = ref(false)

const STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中',
  RUNNING: '导出中',
  SUCCESS: '已完成',
  FAILED: '失败',
}

const statusLabel = computed(() => STATUS_LABELS[props.task.status] || props.task.status)

const totalSize = computed(() => artifacts.value.reduce((sum, a) => sum + a.size, 0))

const mainZipPath = computed(() => {
  const main = artifacts.value.find(a => a.lastSegment) ?? artifacts.value[0]
  return main?.physicalPath ?? props.task.outputPath ?? ''
})

function formatTime(ts: string): string {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatSize(bytes: number): string {
  if (!bytes || bytes <= 0) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

async function loadArtifacts() {
  artifactsLoading.value = true
  try {
    const res = await exportApi.getArtifacts(props.task.id)
    artifacts.value = Array.isArray(res.data) ? res.data : []
  } catch {
    artifacts.value = []
  } finally {
    artifactsLoading.value = false
  }
}

watch(
  () => props.task.status,
  (status) => {
    if (status === 'SUCCESS') {
      loadArtifacts()
    }
  },
  { immediate: true }
)

async function copyText(text: string, successMsg: string) {
  if (!text) {
    ElMessage.warning('无可用路径')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(successMsg)
  } catch {
    ElMessage.error('复制失败')
  }
}

function onCopyMainZip() {
  copyText(mainZipPath.value, '主 ZIP 路径已复制')
}

function onCopyVolumePath(artifact: ExportArtifactVO) {
  copyText(artifact.physicalPath, '卷路径已复制')
}

async function onOpenDir() {
  try {
    await exportApi.openDir(props.task.id)
  } catch {
    ElMessage.error('打开目录失败')
  }
}
</script>

<style scoped>
.export-card {
  display: flex;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: border-color 150ms ease, transform 150ms ease;
}

.export-card:hover {
  border-color: var(--border-strong);
}

.status-bar {
  width: 4px;
  flex-shrink: 0;
  background: var(--accent);
}

.variant-active .status-bar { background: var(--accent); }
.variant-failed .status-bar { background: var(--danger); }
.variant-done .status-bar { background: var(--success); }

.card-body {
  flex: 1;
  padding: var(--space-base) var(--space-lg);
  min-width: 0;
}

/* Header */
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-base);
  margin-bottom: var(--space-base);
}

.header-info {
  flex: 1;
  min-width: 0;
}

.task-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-chip {
  font-size: 11px;
  color: var(--text-secondary);
  background: var(--bg-primary);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
}

.meta-time {
  font-size: 11px;
  color: var(--text-muted);
}

.status-badge {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  background: var(--bg-primary);
  border: 1px solid var(--border);
}

.status-badge.status-pending { color: var(--text-muted); }

.status-badge.status-running {
  color: var(--warning);
  border-color: var(--warning);
  background: rgba(232, 124, 3, 0.1);
}

.status-badge.status-success {
  color: var(--success);
  border-color: var(--success);
  background: rgba(70, 211, 105, 0.1);
}

.status-badge.status-failed {
  color: var(--danger);
  border-color: var(--danger);
  background: rgba(229, 9, 20, 0.1);
}

/* Content */
.card-content {
  margin-bottom: var(--space-base);
}

.output-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.output-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: 12px;
}

.output-label {
  color: var(--text-muted);
  flex-shrink: 0;
  min-width: 56px;
}

.output-value {
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Artifact list */
.artifact-summary {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  font-size: 12px;
  font-weight: 600;
  margin-bottom: var(--space-sm);
  padding: 6px 10px;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.artifact-summary-label {
  color: var(--text-primary);
}

.artifact-summary-size {
  color: var(--accent);
}

.artifact-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.artifact-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 4px 6px;
  border-radius: var(--radius-sm);
}

.artifact-row:hover {
  background: var(--bg-primary);
}

.artifact-index {
  color: var(--text-muted);
  flex-shrink: 0;
  width: 24px;
}

.artifact-name {
  color: var(--text-primary);
  font-weight: 500;
  flex-shrink: 0;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-size {
  color: var(--text-secondary);
  flex-shrink: 0;
}

.artifact-path {
  flex: 1;
  min-width: 0;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-copy-btn {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--accent);
  background: transparent;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  padding: 2px 8px;
  cursor: pointer;
}

.artifact-copy-btn:hover {
  background: var(--bg-primary);
}

.status-block {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.status-block.running {
  color: var(--accent);
}

.status-block.pending {
  color: var(--text-muted);
}

.error-block {
  background: rgba(229, 9, 20, 0.08);
  border-left: 3px solid var(--danger);
  padding: 10px 12px;
  border-radius: var(--radius-sm);
}

.error-message {
  font-size: 13px;
  color: var(--danger);
  margin: 0;
  word-break: break-word;
}

/* Actions */
.card-actions {
  display: flex;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.action-btn {
  padding: 6px 14px;
  font-size: 12px;
  font-weight: 600;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 150ms ease;
  border: 1px solid transparent;
}

.action-btn.primary {
  background: var(--accent);
  color: var(--text-primary);
  border-color: var(--accent);
}

.action-btn.primary:hover {
  background: var(--accent-hover);
}

.action-btn.ghost {
  background: transparent;
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.action-btn.ghost:hover {
  background: var(--bg-primary);
  border-color: var(--text-muted);
}
</style>
