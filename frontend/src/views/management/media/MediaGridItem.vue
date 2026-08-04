<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ManagementMediaItem } from '@/types/management/media'
import {
  dimensionLabel,
  formatDuration,
  hqStatusLabel,
  lifecycleLabel,
  lqStatusLabel,
  mediaTypeLabel,
  transcodeStatusLabel,
} from '@/types/management/media'
import MediaStatusTag from './MediaStatusTag.vue'

/**
 * 虚拟滚动网格项（T4 DESIGN 媒体缩略格原语）。
 * 固定尺寸（176×200）配合 RecycleScroller 的 item-size 防 CLS；
 * 状态同时有文字标签与语义颜色（双通道），不依赖颜色单独表达。
 */
const props = defineProps<{
  item: ManagementMediaItem
  selected: boolean
}>()

const emit = defineEmits<{
  select: [id: number]
  preview: [item: ManagementMediaItem]
  replace: [item: ManagementMediaItem]
  trash: [item: ManagementMediaItem]
  restore: [item: ManagementMediaItem]
  move: [id: number, direction: -1 | 1]
}>()

const imageFailed = ref(false)
const isTrashed = computed(() => props.item.lifecycle === 'TRASHED')
const isVideo = computed(() => props.item.mediaType === 'VIDEO')
const thumbUrl = computed(() =>
  imageFailed.value ? '' : props.item.lqUrl || props.item.hqUrl || '',
)

function onImageError(): void {
  imageFailed.value = true
}

function onMove(direction: -1 | 1): void {
  emit('move', props.item.id, direction)
}
</script>

<template>
  <div
    class="media-thumb"
    :data-testid="`media-item-${item.id}`"
    :data-media-type="item.mediaType"
    :data-lifecycle="item.lifecycle"
    :data-page-number="item.pageNumber"
    :class="{
      'media-thumb--selected': selected,
      'media-thumb--trashed': isTrashed,
      'media-thumb--broken': imageFailed,
    }"
    role="gridcell"
    tabindex="0"
    :aria-label="`${mediaTypeLabel(item.mediaType)}：${item.name}`"
    :aria-selected="selected"
    @click="emit('preview', item)"
    @keydown.enter.prevent.stop="emit('preview', item)"
    @keydown.space.prevent.stop="emit('preview', item)"
    @keydown.alt.arrow-left.prevent.stop="onMove(-1)"
    @keydown.alt.arrow-right.prevent.stop="onMove(1)"
  >
    <div class="thumb-check" @click.stop>
      <input
        type="checkbox"
        class="thumb-checkbox"
        :data-testid="`media-select-${item.id}`"
        :checked="selected"
        :aria-label="`选择 ${item.name}`"
        @change="emit('select', item.id)"
      />
    </div>

    <div class="thumb-media">
      <img
        v-if="!isVideo && thumbUrl !== ''"
        class="thumb-img"
        :src="thumbUrl"
        :alt="item.name"
        :width="item.width > 0 ? item.width : undefined"
        :height="item.height > 0 ? item.height : undefined"
        loading="lazy"
        @error="onImageError"
      />
      <div v-else class="thumb-fallback" :class="{ 'thumb-fallback--video': isVideo }">
        <span class="thumb-fallback-icon" aria-hidden="true">
          {{ isVideo ? 'vid' : imageFailed ? '!' : 'img' }}
        </span>
        <span v-if="isVideo" class="thumb-play" aria-hidden="true" />
        <span v-if="isVideo && item.duration" class="thumb-duration">
          {{ formatDuration(item.duration) }}
        </span>
      </div>
      <span class="thumb-type">{{ mediaTypeLabel(item.mediaType) }}</span>
      <span v-if="imageFailed && !isVideo" class="thumb-broken-text">无法加载</span>
    </div>

    <div class="thumb-name" :title="item.name">{{ item.name }}</div>
    <div class="thumb-meta">
      <span>第 {{ item.pageNumber }} 页</span>
      <span>{{ dimensionLabel(item) }}</span>
    </div>

    <div class="thumb-tags">
      <MediaStatusTag
        :label="hqStatusLabel(item.hqStatus)"
        :tone="item.hqStatus === 'READY' ? 'success' : item.hqStatus === 'DELETED' ? 'info' : item.hqStatus === 'MISSING' || item.hqStatus === 'FAILED' ? 'danger' : 'warning'"
      />
      <MediaStatusTag
        :label="lqStatusLabel(item.lqStatus)"
        :tone="item.lqStatus === 'READY' ? 'success' : item.lqStatus === 'FAILED' || item.lqStatus === 'MISSING' ? 'danger' : 'neutral'"
      />
      <MediaStatusTag
        v-if="isVideo"
        :label="transcodeStatusLabel(item.transcodeStatus)"
        :tone="item.transcodeStatus === 'READY' ? 'success' : item.transcodeStatus === 'FAILED' ? 'danger' : 'info'"
      />
      <MediaStatusTag
        v-if="isTrashed"
        :label="lifecycleLabel(item.lifecycle)"
        tone="warning"
      />
    </div>

    <div class="thumb-actions">
      <button
        class="thumb-action"
        :data-testid="`replace-trigger-${item.id}`"
        type="button"
        @click.stop="emit('replace', item)"
      >替换</button>
      <button
        v-if="!isTrashed"
        class="thumb-action thumb-action--danger"
        :data-testid="`trash-trigger-${item.id}`"
        type="button"
        @click.stop="emit('trash', item)"
      >回收</button>
      <button
        v-else
        class="thumb-action"
        :data-testid="`restore-trigger-${item.id}`"
        type="button"
        @click.stop="emit('restore', item)"
      >恢复</button>
    </div>
  </div>
</template>

<style scoped>
.media-thumb {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 176px;
  height: 200px;
  padding: 8px;
  box-sizing: border-box;
  border: 2px solid transparent;
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  cursor: pointer;
  outline: none;
  overflow: hidden;
  transition:
    border-color var(--transition-fast),
    background-color var(--transition-fast);
}

.media-thumb:hover {
  border-color: var(--border-strong);
}

.media-thumb:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: -2px;
}

.media-thumb--selected {
  border-color: var(--accent);
  background: var(--accent-bg);
}

.media-thumb--trashed {
  opacity: 0.72;
}

.media-thumb--broken {
  opacity: 0.9;
}

.thumb-check {
  position: absolute;
  top: 6px;
  left: 6px;
  z-index: 3;
}

.thumb-checkbox {
  width: 18px;
  height: 18px;
  accent-color: var(--accent);
  cursor: pointer;
}

.thumb-media {
  position: relative;
  aspect-ratio: 16 / 9;
  width: 100%;
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  overflow: hidden;
  flex-shrink: 0;
  min-height: 0;
}

.thumb-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.thumb-fallback--video {
  background: linear-gradient(180deg, var(--bg-primary), var(--bg-secondary));
}

.thumb-fallback-icon {
  font-size: 10px;
  font-weight: 800;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.thumb-play {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: rgb(0 0 0 / 60%);
  display: flex;
  align-items: center;
  justify-content: center;
}

.thumb-play::after {
  content: "";
  display: block;
  width: 0;
  height: 0;
  border-style: solid;
  border-width: 5px 0 5px 8px;
  border-color: transparent transparent transparent var(--color-on-brand);
  margin-left: 2px;
}

.thumb-duration {
  position: absolute;
  right: 4px;
  bottom: 4px;
  padding: 1px 4px;
  border-radius: var(--radius-xs);
  background: rgb(0 0 0 / 60%);
  color: var(--text-1);
  font-size: 9px;
  font-variant-numeric: tabular-nums;
}

.thumb-type {
  position: absolute;
  top: 4px;
  right: 4px;
  padding: 1px 6px;
  border-radius: var(--radius-xs);
  background: rgb(0 0 0 / 55%);
  color: var(--text-2);
  font-size: 9px;
  font-weight: 700;
}

.thumb-broken-text {
  position: absolute;
  left: 4px;
  bottom: 4px;
  padding: 1px 4px;
  border-radius: var(--radius-xs);
  background: rgb(0 0 0 / 55%);
  color: var(--danger);
  font-size: 9px;
  font-weight: 600;
}

.thumb-name {
  margin-top: 2px;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.thumb-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 10px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
}

.thumb-tags {
  display: flex;
  gap: 3px;
  min-width: 0;
  overflow: hidden;
  flex-wrap: nowrap;
}

.thumb-actions {
  display: flex;
  gap: 4px;
  margin-top: auto;
  min-height: 22px;
  align-items: center;
}

.thumb-action {
  flex: 1;
  min-width: 0;
  padding: 2px 6px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-xs);
  background: var(--bg-primary);
  color: var(--text-secondary);
  font-size: 10px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition:
    background-color var(--transition-fast),
    color var(--transition-fast);
}

.thumb-action:hover {
  background: var(--surface-highlight);
  color: var(--text-primary);
}

.thumb-action:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 1px;
}

.thumb-action--danger {
  color: var(--danger);
  border-color: var(--danger);
}

.thumb-action--danger:hover {
  background: rgb(240 107 112 / 10%);
  color: var(--danger);
}

@media (prefers-reduced-motion: reduce) {
  .media-thumb {
    transition: none;
  }
}
</style>
