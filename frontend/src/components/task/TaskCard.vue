<template>
  <article class="task-card" :class="`variant-${variant}`">
    <!-- 左侧状态条 -->
    <div class="status-bar" />

    <div class="card-body">
      <!-- 头部 -->
      <div class="card-header">
        <div class="header-info">
          <h3 class="task-name">{{ taskName }}</h3>
          <div class="task-meta-row">
            <span class="meta-chip source-chip">{{ sourceLabel }}</span>
            <span v-if="task.comicId" class="meta-chip">漫画 #{{ task.comicId }}</span>
            <span class="meta-time">{{ formatTime(task.createdAt) }}</span>
          </div>
        </div>
        <span class="status-badge" :class="`status-${task.status.toLowerCase()}`">
          {{ statusLabel }}
        </span>
      </div>

      <!-- 主体内容 - 根据 variant 切换 -->
      <div class="card-content">
        <!-- 进行中：进度条 + 速度/ETA -->
        <template v-if="variant === 'active'">
          <div class="progress-block">
            <div class="progress-meta">
              <span>{{ task.status === 'PENDING' ? '排队中' : `进度 ${task.progress}%` }}</span>
              <span v-if="task.etaSeconds" class="meta-right">剩余 {{ formatEta(task.etaSeconds) }}</span>
            </div>
            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: `${task.progress}%` }" />
            </div>
            <div v-if="task.downloadSpeed || task.downloadedPages" class="progress-detail">
              <span v-if="task.downloadSpeed">{{ formatSpeed(task.downloadSpeed) }}</span>
              <span v-if="task.downloadedPages">{{ task.downloadedPages }} / {{ task.totalPages || '?' }} 页</span>
            </div>
          </div>
        </template>

        <!-- 失败：错误信息 -->
        <template v-else-if="variant === 'failed'">
          <div class="error-block">
            <p class="error-message">{{ task.errorMessage || '未知错误' }}</p>
            <p class="error-meta">已重试 {{ task.retryCount || 0 }} 次</p>
          </div>
        </template>

        <!-- 完成：耗时 + Read Now CTA -->
        <template v-else>
          <div class="done-block">
            <div class="done-stats">
              <span v-if="task.durationMs" class="stat-item">
                耗时 {{ formatDuration(task.durationMs) }}
              </span>
              <span v-if="task.totalPages" class="stat-item">
                {{ task.totalPages }} 页
              </span>
            </div>
          </div>
        </template>
      </div>

      <!-- 操作区 -->
      <div class="card-actions">
        <button
          v-if="variant === 'failed'"
          class="action-btn primary"
          @click="emit('retry', task.id)"
        >
          重试
        </button>
        <button
          v-if="variant === 'active' && canCancel"
          class="action-btn ghost"
          @click="emit('cancel', task.id)"
        >
          取消
        </button>
        <button
          v-if="variant === 'done' && task.comicId"
          class="action-btn primary cta"
          @click="emit('read', task)"
        >
          立即阅读 ▶
        </button>
        <button
          v-if="variant === 'done' && task.comicId"
          class="action-btn ghost"
          @click="goLibrary"
        >
          漫画库
        </button>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ImportTaskVO } from '@/types'

const props = defineProps<{
  task: ImportTaskVO
  variant: 'active' | 'failed' | 'done'
}>()

const emit = defineEmits<{
  cancel: [id: number]
  retry: [id: number]
  read: [task: ImportTaskVO]
}>()

const STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中',
  PARSING: '解析中',
  IMPORTING: '导入中',
  DOWNLOADING: '下载中',
  EXTRACTING: '解压中',
  SUCCESS: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

const SOURCE_LABELS: Record<string, string> = {
  ZIP: 'ZIP',
  DIRECTORY: '目录',
  REGISTER: '注册',
  EHENTAI: 'E-Hentai',
}

const statusLabel = computed(() => STATUS_LABELS[props.task.status] || props.task.status)
const sourceLabel = computed(() => SOURCE_LABELS[props.task.sourceType] || props.task.sourceType || '未知')

const taskName = computed(() => {
  const path = props.task.sourcePath || props.task.sourceRef || ''
  if (!path) return `任务 #${props.task.id}`
  const parts = path.replace(/\\/g, '/').split('/')
  const last = parts[parts.length - 1]
  // 去掉 .zip 扩展名
  return last?.replace(/\.zip$/i, '') || path
})

const canCancel = computed(() =>
  props.task.status === 'PENDING' ||
  props.task.status === 'DOWNLOADING' ||
  props.task.status === 'PARSING'
)

function formatTime(ts: string): string {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatSpeed(speed: number): string {
  if (!speed || speed <= 0) return ''
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

function formatDuration(ms: number): string {
  if (!ms) return '-'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}秒`
  const m = Math.floor(ms / 60000)
  const s = Math.floor((ms % 60000) / 1000)
  return `${m}分${s}秒`
}

function goLibrary() {
  // 用 window.location 跳转避免依赖 useRouter（保持组件无路由耦合）
  if (props.task.comicId) {
    window.location.href = `/comics/${props.task.comicId}`
  }
}
</script>

<style scoped>
.task-card {
  display: flex;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: border-color 150ms ease, transform 150ms ease;
}

.task-card:hover {
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
  color: var(--text-h);
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
  color: var(--text);
  background: var(--bg);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
}

.source-chip {
  color: var(--accent);
  border-color: var(--accent-border);
  background: var(--accent-bg);
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
  background: var(--bg);
  border: 1px solid var(--border);
}

.status-badge.status-pending,
.status-badge.status-cancelled { color: var(--text-muted); }

.status-badge.status-parsing,
.status-badge.status-importing,
.status-badge.status-downloading,
.status-badge.status-extracting {
  color: var(--warning);
  border-color: var(--warning);
  background: rgba(230, 162, 60, 0.1);
}

.status-badge.status-success {
  color: var(--success);
  border-color: var(--success);
  background: rgba(67, 160, 71, 0.1);
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

.progress-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.progress-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text);
}

.meta-right {
  color: var(--text-muted);
}

.progress-bar {
  height: 4px;
  background: var(--bg);
  border-radius: var(--radius-pill);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--accent);
  border-radius: var(--radius-pill);
  transition: width 400ms ease;
}

.progress-detail {
  display: flex;
  gap: var(--space-base);
  font-size: 11px;
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
  margin: 0 0 4px;
  word-break: break-word;
}

.error-meta {
  font-size: 11px;
  color: var(--text-muted);
  margin: 0;
}

.done-block {
  display: flex;
  align-items: center;
}

.done-stats {
  display: flex;
  gap: var(--space-base);
  font-size: 12px;
  color: var(--text);
}

.stat-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
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
  color: #fff;
  border-color: var(--accent);
}

.action-btn.primary:hover {
  background: var(--accent-hover);
}

.action-btn.primary.cta {
  padding: 8px 18px;
  font-size: 13px;
}

.action-btn.ghost {
  background: transparent;
  color: var(--text-h);
  border-color: var(--border-strong);
}

.action-btn.ghost:hover {
  background: var(--bg);
  border-color: var(--text-muted);
}
</style>
