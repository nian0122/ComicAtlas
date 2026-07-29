<template>
  <article class="recovery-card" :class="`variant-${variant}`">
    <div class="status-bar" />

    <div class="card-body">
      <div class="card-header">
        <div class="header-info">
          <h3 class="task-name">恢复任务 #{{ task.id }}</h3>
          <div class="task-meta-row">
            <span class="meta-time">{{ formatTime(task.createdAt) }}</span>
          </div>
        </div>
        <span class="status-badge" :class="`status-${task.status.toLowerCase()}`">
          {{ statusLabel }}
        </span>
      </div>

      <div class="card-content">
        <template v-if="task.status === 'RUNNING'">
          <div class="status-block running">
            <span class="spinner" />
            <span>扫描恢复中...</span>
          </div>
        </template>

        <template v-else-if="task.status === 'PENDING'">
          <div class="status-block pending">
            <span class="pending-dot" />
            <span>等待中</span>
          </div>
        </template>

        <template v-else-if="task.status === 'FAILED'">
          <div class="error-block">
            <p class="error-message">{{ task.errorMessage || '未知错误' }}</p>
            <p class="error-meta">已重试 {{ task.retryCount || 0 }} 次</p>
            <div v-if="task.errorDetails" class="error-details">
              <pre class="error-details-text">{{ task.errorDetails }}</pre>
            </div>
          </div>
        </template>

        <div class="stats-row">
          <span class="stat-item">
            <span class="stat-label">总共</span>
            <span class="stat-value">{{ task.totalComics ?? '-' }}</span>
          </span>
          <span class="stat-item stat-recovered">
            <span class="stat-label">恢复</span>
            <span class="stat-value">{{ task.recoveredComics ?? '-' }}</span>
          </span>
          <span class="stat-item stat-skipped">
            <span class="stat-label">跳过</span>
            <span class="stat-value">{{ task.skippedComics ?? '-' }}</span>
          </span>
          <span class="stat-item stat-placeholder">
            <span class="stat-label">占位</span>
            <span class="stat-value">{{ task.placeholderComics ?? '-' }}</span>
          </span>
          <span class="stat-item stat-error">
            <span class="stat-label">错误</span>
            <span class="stat-value">{{ task.errorComics ?? '-' }}</span>
          </span>
        </div>

        <div v-if="task.status === 'SUCCESS' || task.status === 'FAILED'" class="duration-row">
          <span class="duration-text">{{ durationText }}</span>
        </div>
      </div>

      <div class="card-actions">
        <button
          v-if="task.status === 'FAILED'"
          class="action-btn primary"
          @click="emit('retry', task.id)"
        >
          重新执行
        </button>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RecoveryTaskVO } from '@/types'

const props = defineProps<{
  task: RecoveryTaskVO
  variant: 'active' | 'failed' | 'done'
}>()

const emit = defineEmits<{
  retry: [id: number]
}>()

const STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中',
  RUNNING: '扫描中',
  SUCCESS: '已完成',
  FAILED: '失败',
}

const statusLabel = computed(() => STATUS_LABELS[props.task.status] || props.task.status)

const durationText = computed(() => {
  if (!props.task.startedAt) return ''
  const end = props.task.endedAt ? new Date(props.task.endedAt) : new Date()
  const start = new Date(props.task.startedAt)
  const ms = end.getTime() - start.getTime()
  if (ms < 0) return ''
  return `耗时 ${formatDuration(ms)}`
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

function formatDuration(ms: number): string {
  if (!ms) return '-'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}秒`
  const m = Math.floor(ms / 60000)
  const s = Math.floor((ms % 60000) / 1000)
  return `${m}分${s}秒`
}
</script>

<style scoped>
.recovery-card {
  display: flex;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: border-color 150ms ease, transform 150ms ease;
}

.recovery-card:hover {
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

.card-content {
  margin-bottom: var(--space-base);
}

.status-block {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  margin-bottom: var(--space-sm);
}

.status-block.running {
  color: var(--accent);
}

.status-block.pending {
  color: var(--text-muted);
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 800ms linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.pending-dot {
  width: 8px;
  height: 8px;
  background: var(--text-muted);
  border-radius: 50%;
  animation: pulse-dot 1.5s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}

.error-block {
  background: rgba(229, 9, 20, 0.08);
  border-left: 3px solid var(--danger);
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-sm);
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
  margin: 0 0 4px;
}

.error-details {
  margin-top: var(--space-sm);
  max-height: 160px;
  overflow-y: auto;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.error-details-text {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--text-secondary);
  padding: var(--space-sm);
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}

.stats-row {
  display: flex;
  gap: var(--space-base);
  flex-wrap: wrap;
  margin-bottom: var(--space-sm);
}

.stat-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}

.stat-label {
  color: var(--text-muted);
}

.stat-value {
  color: var(--text-primary);
  font-weight: 600;
}

.stat-recovered .stat-value { color: var(--success); }
.stat-skipped .stat-value { color: var(--text-muted); }
.stat-placeholder .stat-value { color: var(--info); }
.stat-error .stat-value { color: var(--danger); }

.duration-row {
  margin-bottom: var(--space-sm);
}

.duration-text {
  font-size: 12px;
  color: var(--text-secondary);
}

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
  min-height: 44px;
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
