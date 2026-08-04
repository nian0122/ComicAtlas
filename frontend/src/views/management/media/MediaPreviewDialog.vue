<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ManagementMediaItem } from '@/types/management/media'
import {
  dimensionLabel,
  formatDuration,
  mediaTypeLabel,
} from '@/types/management/media'

/**
 * 媒体预览对话框：图片直接展示（显式尺寸容器防 CLS），视频展示原生播放器 + 编码信息。
 */
const props = defineProps<{
  item: ManagementMediaItem | null
}>()

const emit = defineEmits<{
  close: []
}>()

const imageFailed = ref(false)

const visible = computed(() => props.item !== null)

const title = computed(() =>
  props.item ? `${mediaTypeLabel(props.item.mediaType)}：${props.item.name}` : '',
)

const isVideo = computed(() => props.item?.mediaType === 'VIDEO')

const previewUrl = computed(() =>
  props.item ? props.item.hqUrl || props.item.lqUrl : '',
)

watch(
  () => props.item,
  () => {
    imageFailed.value = false
  },
)

function onImageError(): void {
  imageFailed.value = true
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="title"
    width="min(840px, 92vw)"
    destroy-on-close
    @closed="emit('close')"
    @update:model-value="(v: boolean) => { if (!v) emit('close') }"
  >
    <div v-if="item" class="preview-body" data-testid="preview-dialog">
      <div class="preview-stage">
        <template v-if="!isVideo">
          <img
            v-if="previewUrl !== '' && !imageFailed"
            class="preview-image"
            :src="previewUrl"
            :alt="item.name"
            :width="item.width > 0 ? item.width : undefined"
            :height="item.height > 0 ? item.height : undefined"
            @error="onImageError"
          />
          <div v-else class="preview-broken">
            <span class="preview-broken-icon" aria-hidden="true">!</span>
            <span>无法加载此图片</span>
          </div>
        </template>
        <video
          v-else
          class="preview-video"
          :src="previewUrl"
          :width="item.width > 0 ? item.width : undefined"
          :height="item.height > 0 ? item.height : undefined"
          controls
          playsinline
          preload="metadata"
        />
      </div>

      <dl class="preview-meta">
        <div class="preview-meta-row">
          <dt>类型</dt>
          <dd>{{ mediaTypeLabel(item.mediaType) }}</dd>
        </div>
        <div class="preview-meta-row">
          <dt>尺寸</dt>
          <dd>{{ dimensionLabel(item) }}</dd>
        </div>
        <template v-if="isVideo">
          <div v-if="item.duration" class="preview-meta-row">
            <dt>时长</dt>
            <dd>{{ formatDuration(item.duration) }}</dd>
          </div>
          <div v-if="item.container" class="preview-meta-row">
            <dt>容器</dt>
            <dd>{{ item.container }}</dd>
          </div>
          <div v-if="item.videoCodec" class="preview-meta-row">
            <dt>视频编码</dt>
            <dd>{{ item.videoCodec }}</dd>
          </div>
          <div v-if="item.audioCodec" class="preview-meta-row">
            <dt>音频编码</dt>
            <dd>{{ item.audioCodec }}</dd>
          </div>
        </template>
      </dl>
    </div>
  </el-dialog>
</template>

<style scoped>
.preview-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.preview-stage {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  aspect-ratio: 16 / 9;
  max-height: 60vh;
  border-radius: var(--radius-md);
  background: var(--bg-primary);
  overflow: hidden;
}

.preview-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.preview-video {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
}

.preview-broken {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-muted);
  font-size: var(--text-sm);
}

.preview-broken-icon {
  font-size: var(--text-section);
  font-weight: 900;
  color: var(--danger);
}

.preview-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--space-2) var(--space-4);
  margin: 0;
}

.preview-meta-row {
  display: flex;
  gap: var(--space-2);
  font-size: var(--text-xs);
}

.preview-meta-row dt {
  color: var(--text-muted);
  font-weight: 600;
}

.preview-meta-row dd {
  margin: 0;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
</style>
