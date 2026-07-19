<template>
  <div
    class="progressive-image"
    :style="containerStyle"
  >
    <!-- Skeleton placeholder -->
    <div
      v-if="showSkeleton"
      class="progressive-skeleton"
    />

    <!-- Single image layer -->
    <img
      v-if="showImage"
      :src="currentSrc"
      :alt="alt"
      class="progressive-layer"
      :class="imageClasses"
      decoding="async"
      @load="onImageLoad"
      @error="onImageError"
    />

    <!-- Retry / error state -->
    <div
      v-if="error"
      class="progressive-error"
      @click="retry"
    >
      <el-icon :size="24"><PictureFilled /></el-icon>
      <span>加载失败，点击重试</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { PictureFilled } from '@element-plus/icons-vue'
import type { QualityMode } from '@/stores/reader-settings-store'
import { DEFAULT_ASPECT_RATIO } from '@/types'

interface Props {
  lq: string | null
  hq: string | null
  alt?: string
  mode: QualityMode
  aspectRatio?: number
  enableProgressive?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  alt: '',
  aspectRatio: DEFAULT_ASPECT_RATIO,
  enableProgressive: true,
})

const currentSrc = ref<string | undefined>(undefined)
const isHqLoaded = ref(false)
const isHqLoading = ref(false)
const hqError = ref(false)
const lqError = ref(false)
let hqLoader: HTMLImageElement | null = null

const error = computed(() => {
  if (props.mode === 'HQ_ONLY') return hqError.value
  if (props.mode === 'LQ_ONLY') return lqError.value
  return lqError.value && hqError.value
})

const showSkeleton = computed(() => {
  if (props.mode === 'HQ_ONLY') return !currentSrc.value && !hqError.value
  if (props.mode === 'LQ_ONLY') return !currentSrc.value && !lqError.value
  return !currentSrc.value && !lqError.value && !hqError.value
})

const showImage = computed(() => currentSrc.value !== undefined && !error.value)

const imageClasses = computed(() => ({
  'fade-in': props.mode === 'AUTO' && isHqLoaded.value && currentSrc.value === props.hq,
}))

const containerStyle = computed(() => ({
  aspectRatio: `${props.aspectRatio}`,
  width: '100%',
}))

function cancelHqLoader(): void {
  if (hqLoader) {
    hqLoader.onload = null
    hqLoader.onerror = null
    hqLoader.src = ''
    hqLoader = null
  }
}

function loadHq(): void {
  if (!props.hq || isHqLoaded.value || hqError.value) return
  if (!props.enableProgressive && props.mode === 'AUTO') return

  cancelHqLoader()
  isHqLoading.value = true

  const loader = new Image()
  loader.decoding = 'async'
  hqLoader = loader

  loader.onload = () => {
    /*
     * 身份校验：虚拟滚动组件复用时 props 可能已切换，
     * 旧 loader 的回调触发时 hqLoader 已指向新实例。
     * 不匹配则丢弃，防止错误取消当前加载。
     */
    if (hqLoader !== loader) return
    isHqLoaded.value = true
    isHqLoading.value = false
    if ((props.mode === 'HQ_ONLY' || props.mode === 'AUTO') && props.hq) {
      currentSrc.value = props.hq
    }
    cancelHqLoader()
  }

  loader.onerror = () => {
    if (hqLoader !== loader) return
    hqError.value = true
    isHqLoading.value = false
    cancelHqLoader()
  }

  loader.src = props.hq
}

function onImageLoad(): void {
  // LQ 加载成功后，在 AUTO 模式下触发 HQ 加载
  if (currentSrc.value === props.lq && props.mode === 'AUTO') {
    loadHq()
  }
}

function onImageError(): void {
  if (currentSrc.value === props.lq) {
    lqError.value = true
    if (props.mode === 'AUTO') {
      loadHq()
    }
  } else if (currentSrc.value === props.hq) {
    hqError.value = true
  }
}

function reset(): void {
  cancelHqLoader()
  isHqLoaded.value = false
  isHqLoading.value = false
  hqError.value = false
  lqError.value = false
  currentSrc.value = undefined
}

function retry(): void {
  reset()
  applyInitialSrc()
}

function applyInitialSrc(): void {
  if (props.mode === 'HQ_ONLY') {
    currentSrc.value = props.hq || undefined
    loadHq()
  } else if (props.mode === 'LQ_ONLY') {
    currentSrc.value = props.lq || undefined
  } else {
    // AUTO
    if (props.lq) {
      currentSrc.value = props.lq
    } else if (props.hq) {
      currentSrc.value = props.hq
      loadHq()
    }
  }
}

onMounted(() => {
  applyInitialSrc()
})

onBeforeUnmount(() => {
  cancelHqLoader()
})

watch(() => [props.lq, props.hq, props.mode], () => {
  reset()
  applyInitialSrc()
}, { flush: 'post' })
</script>

<style scoped>
.progressive-image {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  background: var(--surface);
  overflow: hidden;
}

.progressive-skeleton {
  position: absolute;
  inset: 0;
  background: var(--surface-elevated);
  animation: pulse 1.5s ease-in-out infinite;
}

.progressive-layer {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
  opacity: 1;
  transition: opacity 250ms ease;
}

.progressive-layer.fade-in {
  animation: fade-in 250ms ease;
}

.progressive-error {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  color: var(--text);
  background: var(--surface);
  cursor: pointer;
  z-index: 3;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

@keyframes fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
</style>
