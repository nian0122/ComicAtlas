<template>
  <div class="video-player" :style="containerStyle">
    <video
      v-if="!error"
      ref="videoRef"
      class="video-element"
      :src="hqUrl"
      :width="width"
      :height="height"
      controls
      playsinline
      preload="metadata"
      data-reader-interactive
      @loadedmetadata="onMetadata"
      @play="onPlay"
      @pause="onPause"
      @ended="onPause"
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
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { VideoPlay } from '@element-plus/icons-vue'
import {
  activateVideo,
  releaseVideo,
} from '@/views/reading/reader/videoPlaybackCoordinator'

interface Props {
  readonly hqUrl: string
  readonly mediaType: string
  readonly width?: number
  readonly height?: number
  readonly duration?: number
  readonly container?: string
  readonly videoCodec?: string
  readonly audioCodec?: string
}

const props = defineProps<Props>()
const error = ref(false)
const videoRef = ref<HTMLVideoElement | null>(null)
/** 视频原生尺寸（loadedmetadata 后可用），优先于 props 中的宽高 */
const nativeWidth = ref(0)
const nativeHeight = ref(0)
let visibilityObserver: IntersectionObserver | null = null

const aspectRatio = computed(() => {
  // 优先 props（后端 ffprobe 提取的准确尺寸）
  if (props.width && props.height && props.height > 0) {
    return props.width / props.height
  }
  // 降级：视频元数据加载后的原生尺寸
  if (nativeWidth.value && nativeHeight.value) {
    return nativeWidth.value / nativeHeight.value
  }
  // 兜底
  return 16 / 9
})

const containerStyle = computed(() => ({
  aspectRatio: `${aspectRatio.value}`,
  width: '100%',
}))

const hasCodecInfo = computed(() => !!(props.container || props.videoCodec || props.audioCodec))

function onError(): void {
  error.value = true
}

function onMetadata(event: Event): void {
  const video = event.currentTarget
  if (!(video instanceof HTMLVideoElement)) return
  nativeWidth.value = video.videoWidth
  nativeHeight.value = video.videoHeight
}

function onPlay(event: Event): void {
  if (!(event.currentTarget instanceof HTMLVideoElement)) return
  activateVideo(event.currentTarget)
}

function onPause(event: Event): void {
  if (event.currentTarget instanceof HTMLVideoElement) releaseVideo(event.currentTarget)
}

onMounted(() => {
  const video = videoRef.value
  if (video === null) return

  visibilityObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.target === video && !entry.isIntersecting) {
          video.pause()
        }
      }
    },
    { threshold: 0.01 },
  )
  visibilityObserver.observe(video)
})

onBeforeUnmount(() => {
  visibilityObserver?.disconnect()
  visibilityObserver = null

  const video = videoRef.value
  video?.pause()
  if (video !== null) releaseVideo(video)
})
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
