<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMediaUploadQueueStore } from '@/stores/management/mediaUploadQueue'
import {
  formatBytes,
  mediaTypeLabel,
  sanitizeErrorMessage,
  uploadStatusLabel,
} from '@/types/management/media'
import MediaStatusTag from './MediaStatusTag.vue'

/**
 * 上传面板（Task 19）：拖入/文件选择 → 队列（分块进度、暂停/继续/取消/逐文件重试）。
 * 复用 T17 上传 Store 传输层；文件名与错误文案始终脱敏，不展示客户端绝对路径。
 */
const queue = useMediaUploadQueueStore()

const inputRef = ref<HTMLInputElement | null>(null)

const dragging = ref(false)

const percentByIndex = (index: number): number => {
  const entry = queue.entries.find((e) => e.index === index)
  if (!entry) return 0
  if (entry.size <= 0) return 0
  return Math.min(100, Math.round((entry.receivedBytes / entry.size) * 100))
}

const statusTone = (status: string): 'success' | 'warning' | 'danger' | 'info' | 'neutral' => {
  switch (status) {
    case 'completed':
      return 'success'
    case 'failed':
      return 'danger'
    case 'uploading':
      return 'warning'
    case 'paused':
      return 'info'
    default:
      return 'neutral'
  }
}

function openFilePicker(): void {
  inputRef.value?.click()
}

function onInputChange(event: Event): void {
  const input = event.currentTarget as HTMLInputElement
  if (input.files && input.files.length > 0) {
    void queue.addFiles(Array.from(input.files))
  }
  input.value = ''
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  const files = event.dataTransfer?.files
  if (files && files.length > 0) {
    void queue.addFiles(Array.from(files))
  }
}

const replaceTargetText = computed(() =>
  queue.replaceMediaId !== null ? `正在替换媒体 #${queue.replaceMediaId}（保留原 ID）` : '',
)
</script>

<template>
  <div class="upload-panel">
    <div
      class="dropzone"
      :class="{ 'dropzone--dragging': dragging }"
      :data-testid="'upload-dropzone'"
      role="button"
      tabindex="0"
      aria-label="拖入文件或点击选择要上传的图片/视频"
      @click="openFilePicker"
      @keydown.enter.prevent="openFilePicker"
      @keydown.space.prevent="openFilePicker"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <span class="dropzone-title">拖入文件或点击选择</span>
      <span class="dropzone-sub">支持图片与视频混排，分块上传可暂停续传</span>
      <input
        ref="inputRef"
        class="upload-input"
        :data-testid="'upload-input'"
        type="file"
        multiple
        accept="image/*,video/*,audio/*"
        @change="onInputChange"
      />
    </div>

    <div v-if="replaceTargetText" class="replace-hint" data-testid="replace-hint">
      <span>{{ replaceTargetText }}</span>
      <button
        class="replace-hint-cancel"
        type="button"
        data-testid="replace-cancel"
        @click="queue.setReplaceTarget(null)"
      >取消替换</button>
    </div>

    <div v-if="queue.busy || queue.entries.length > 0" class="queue-block">
      <div class="queue-header">
        <span class="queue-title">上传队列（{{ queue.entries.length }}）</span>
        <span class="queue-overall" data-testid="upload-overall">
          {{ queue.overallPercent }}%
        </span>
        <div
          class="queue-progress"
          role="progressbar"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="queue.overallPercent"
          :aria-label="`队列总进度 ${queue.overallPercent}%`"
        >
          <div class="queue-progress-fill" :style="{ width: `${queue.overallPercent}%` }" />
        </div>
        <button
          v-if="queue.busy"
          class="queue-cancel-all"
          type="button"
          data-testid="upload-cancel-all"
          @click="queue.cancelAll()"
        >取消全部</button>
      </div>

      <div v-if="queue.queueError" class="queue-error" data-testid="upload-error">
        {{ queue.queueError }}
      </div>

      <ul class="queue-list" role="list">
        <li
          v-for="entry in queue.entries"
          :key="entry.index"
          class="queue-item"
          role="listitem"
          :data-testid="`upload-item-${entry.index}`"
        >
          <span class="queue-item-name" :title="entry.name">{{ entry.name }}</span>
          <span class="queue-item-source">{{ mediaTypeLabel(entry.mediaType) }}</span>
          <div
            class="queue-item-track"
            role="progressbar"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="percentByIndex(entry.index)"
            :aria-label="`${entry.name} 进度 ${percentByIndex(entry.index)}%`"
            :data-testid="`upload-progress-${entry.index}`"
          >
            <div class="queue-item-fill" :style="{ width: `${percentByIndex(entry.index)}%` }" />
          </div>
          <span class="queue-item-pct">{{ percentByIndex(entry.index) }}%</span>
          <MediaStatusTag
            :data-testid="`upload-status-${entry.index}`"
            :label="uploadStatusLabel(entry.status)"
            :tone="statusTone(entry.status)"
          />
          <span v-if="entry.error" class="queue-item-error" :title="entry.error">
            {{ sanitizeErrorMessage(entry.error) }}
          </span>
          <span class="queue-item-size">{{ formatBytes(entry.size) }}</span>
          <div class="queue-item-actions">
            <button
              v-if="entry.status === 'uploading'"
              class="queue-action"
              type="button"
              :data-testid="`upload-pause-${entry.index}`"
              @click="queue.pauseEntry(entry.index)"
            >暂停</button>
            <button
              v-if="entry.status === 'paused'"
              class="queue-action"
              type="button"
              :data-testid="`upload-resume-${entry.index}`"
              @click="queue.resumeEntry(entry.index)"
            >继续</button>
            <button
              v-if="entry.status === 'failed'"
              class="queue-action"
              type="button"
              :data-testid="`upload-retry-${entry.index}`"
              @click="queue.retryEntry(entry.index)"
            >重试</button>
            <button
              v-if="entry.status === 'uploading' || entry.status === 'queued' || entry.status === 'paused'"
              class="queue-action queue-action--danger"
              type="button"
              :data-testid="`upload-cancel-${entry.index}`"
              @click="queue.cancelAll()"
            >取消</button>
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.upload-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  min-height: 96px;
  padding: var(--space-4);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  cursor: pointer;
  transition:
    border-color var(--transition-fast),
    background-color var(--transition-fast);
}

.dropzone:hover,
.dropzone:focus-visible {
  border-color: var(--accent);
  outline: none;
}

.dropzone--dragging {
  border-color: var(--accent);
  background: var(--accent-bg);
}

.dropzone-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
}

.dropzone-sub {
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.upload-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  overflow: hidden;
  pointer-events: none;
}

.queue-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.queue-header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.queue-title {
  font-size: var(--text-sm);
  font-weight: 700;
  color: var(--text-primary);
}

.queue-overall {
  font-size: var(--text-xs);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}

.queue-progress {
  flex: 1;
  height: 6px;
  min-width: 60px;
  border-radius: var(--radius-pill);
  background: var(--bg-primary);
  overflow: hidden;
}

.queue-progress-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--accent);
  transition: width 300ms var(--transition-fast);
}

.queue-cancel-all {
  padding: 4px 10px;
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--danger);
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
}

.queue-cancel-all:hover {
  background: rgb(240 107 112 / 10%);
}

.replace-hint {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-sm);
  background: var(--accent-bg);
  font-size: var(--text-xs);
  color: var(--text-primary);
}

.replace-hint-cancel {
  margin-left: auto;
  padding: 0;
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: var(--text-xs);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.queue-error {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: rgb(240 107 112 / 10%);
  border: 1px solid var(--danger);
  color: var(--danger);
  font-size: var(--text-xs);
  overflow-wrap: anywhere;
}

.queue-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.queue-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-height: var(--control-min-size);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  flex-wrap: wrap;
}

.queue-item-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 240px;
}

.queue-item-source {
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  font-size: 10px;
  font-weight: 700;
  color: var(--accent);
  background: var(--accent-bg);
  border: 1px solid var(--accent-border);
}

.queue-item-track {
  flex: 1;
  height: 4px;
  min-width: 60px;
  border-radius: var(--radius-pill);
  background: var(--bg-primary);
  overflow: hidden;
}

.queue-item-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--accent);
  transition: width 300ms var(--transition-fast);
}

.queue-item-pct {
  font-size: var(--text-xs);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}

.queue-item-error {
  font-size: var(--text-xs);
  color: var(--danger);
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.queue-item-size {
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.queue-item-actions {
  display: flex;
  gap: var(--space-2);
  margin-left: auto;
}

.queue-action {
  padding: 4px 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-secondary);
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
}

.queue-action:hover {
  background: var(--surface-highlight);
  color: var(--text-primary);
}

.queue-action--danger {
  color: var(--danger);
  border-color: var(--danger);
}

.queue-action--danger:hover {
  background: rgb(240 107 112 / 10%);
  color: var(--danger);
}

@media (prefers-reduced-motion: reduce) {
  .queue-progress-fill,
  .queue-item-fill {
    transition: none;
  }
}
</style>
