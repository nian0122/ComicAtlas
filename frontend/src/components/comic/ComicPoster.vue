<template>
  <div
    class="comic-poster"
    :class="[`size--${props.size}`, { 'is-hoverable': props.showHover }]"
    @click="emit('click', props.id)"
  >
    <div class="poster-frame">
      <el-image
        :src="props.coverUrl"
        fit="cover"
        lazy
        class="poster-image"
      >
        <template #error>
          <div class="poster-placeholder">
            <el-icon :size="sizeIcon"><PictureFilled /></el-icon>
          </div>
        </template>
      </el-image>

      <div
        v-if="props.status && props.status !== 'ready'"
        class="poster-status-badge"
        :class="`status--${props.status}`"
      >
        {{ statusLabel }}
      </div>

      <div
        v-if="props.showProgress && props.progress && props.progress > 0"
        class="poster-progress"
      >
        <div
          class="poster-progress__fill"
          :style="{ width: `${Math.min(100, Math.max(0, props.progress))}%` }"
        />
      </div>

      <div
        v-if="props.showHover"
        class="poster-overlay"
        :class="{ 'has-buttons': props.showButtons }"
      >
        <div class="poster-overlay__actions">
          <button
            v-if="props.showButtons"
            class="poster-btn poster-btn--primary"
            @click.stop="emit('continue', props.id)"
          >
            继续阅读
          </button>
          <button
            v-if="props.showButtons"
            class="poster-btn"
            @click.stop="emit('detail', props.id)"
          >
            详情
          </button>
        </div>
      </div>
    </div>

    <div class="poster-info">
      <p class="poster-title" :title="props.title">{{ props.title }}</p>
      <p v-if="props.showSubtitle && props.subtitle" class="poster-subtitle">
        {{ props.subtitle }}
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { PictureFilled } from '@element-plus/icons-vue'
import type { PosterProps } from './poster-status'

const props = withDefaults(defineProps<PosterProps>(), {
  status: 'ready',
  showProgress: true,
  showSubtitle: true,
  showHover: true,
  showButtons: true,
})

const emit = defineEmits<{
  click: [id: string | number]
  continue: [id: string | number]
  detail: [id: string | number]
}>()

const statusLabel = computed(() => {
  switch (props.status) {
    case 'importing':
      return '导入中'
    case 'pending':
      return '等待中'
    case 'failed':
      return '失败'
    default:
      return ''
  }
})

const sizeIcon = computed(() => {
  switch (props.size) {
    case 'sm':
      return 28
    case 'lg':
      return 48
    default:
      return 36
  }
})
</script>

<style scoped>
.comic-poster {
  display: flex;
  flex-direction: column;
  cursor: pointer;
  color: var(--text-primary);
  position: relative;
  z-index: 1;
  transition: transform var(--transition-normal);
}

.size--sm {
  width: var(--poster-width-sm);
}

.size--md {
  width: var(--poster-width-md);
}

.size--lg {
  width: var(--poster-width-lg);
}

.poster-frame {
  position: relative;
  aspect-ratio: 2 / 3;
  overflow: hidden;
  border-radius: var(--card-radius);
  background: var(--bg-surface);
  box-shadow: var(--card-shadow);
  transition:
    box-shadow var(--transition-normal),
    filter var(--transition-normal);
}

.is-hoverable:hover {
  transform: scale(var(--card-hover-scale));
  z-index: 2;
}

.is-hoverable:hover .poster-frame {
  box-shadow: var(--card-shadow-hover);
  filter: brightness(1.08);
}

.poster-image {
  width: 100%;
  height: 100%;
  display: block;
}

.poster-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  background: var(--bg-surface);
}

.poster-status-badge {
  position: absolute;
  top: var(--space-sm);
  right: var(--space-sm);
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-sm);
  font-size: 11px;
  font-weight: 700;
  color: var(--text-primary);
  background: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.02em;
  z-index: 3;
}

.status--importing {
  background: var(--warning);
}

.status--pending {
  background: var(--text-muted);
}

.status--failed {
  background: var(--danger);
}

.poster-progress {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: rgba(255, 255, 255, 0.2);
  z-index: 3;
}

.poster-progress__fill {
  height: 100%;
  background: var(--accent);
  transition: width var(--transition-normal);
}

.poster-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.55);
  opacity: 0;
  transition: opacity var(--transition-normal);
  border-radius: var(--card-radius);
  z-index: 4;
}

.is-hoverable .poster-frame:hover .poster-overlay.has-buttons {
  opacity: 1;
}

.poster-overlay__actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  align-items: center;
  padding: var(--space-base);
}

.poster-btn {
  min-width: 96px;
  padding: var(--space-sm) var(--space-base);
  border: none;
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.2);
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  backdrop-filter: blur(4px);
  transition:
    background-color var(--transition-fast),
    transform var(--transition-fast);
}

.poster-btn:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: translateY(-1px);
}

.poster-btn--primary {
  background: var(--accent);
}

.poster-btn--primary:hover {
  background: var(--accent-hover);
}

.poster-info {
  margin-top: var(--space-sm);
}

.poster-title {
  margin: 0 0 var(--space-xs);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.poster-subtitle {
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
