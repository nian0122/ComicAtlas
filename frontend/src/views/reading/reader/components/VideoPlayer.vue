<template>
  <div class="video-player" :style="containerStyle">
    <video
      v-if="!error"
      class="video-element"
      :src="hqUrl"
      :width="width"
      :height="height"
      controls
      preload="metadata"
      @error="onError"
    />
    <div v-else class="video-error">
      <el-icon :size="32"><VideoPlay /></el-icon>
      <span class="video-error-title">浏览器无法播放此格式</span>
      <div v-if="hasCodecInfo" class="video-error-info">
        <span v-if="container">容器: {{ container }}</span>
        <span v-if="videoCodec">视频编码: {{ videoCodec }}</span>
        <span v-if="audioCodec">音频编码: {{ audioCodec }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { VideoPlay } from '@element-plus/icons-vue'

interface Props {
  hqUrl: string
  mediaType: string
  width?: number
  height?: number
  duration?: number
  container?: string
  videoCodec?: string
  audioCodec?: string
}

const props = defineProps<Props>()
const error = ref(false)

const aspectRatio = computed(() => {
  if (props.width && props.height && props.height > 0) {
    return props.width / props.height
  }
  return 16 / 9
})

const containerStyle = computed(() => ({
  aspectRatio: `${aspectRatio.value}`,
  width: '100%',
  maxWidth: '100%',
}))

const hasCodecInfo = computed(() => !!(props.container || props.videoCodec || props.audioCodec))

function onError(): void {
  error.value = true
}
</script>

<style scoped>
.video-player {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  background: var(--surface);
  overflow: hidden;
}

.video-element {
  max-width: 100%;
  max-height: 100%;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.video-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  width: 100%;
  height: 100%;
  color: var(--text);
  background: var(--surface);
  padding: var(--space-md);
  text-align: center;
}

.video-error-title {
  font-size: 14px;
  font-weight: 500;
}

.video-error-info {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs) var(--space-md);
  font-size: 12px;
  color: var(--text-secondary);
}
</style>