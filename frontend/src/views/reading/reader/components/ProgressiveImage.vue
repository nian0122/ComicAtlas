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
  /** LQ 生成状态：READY 可用，其他视为不可用（降级 HQ） */
  lqStatus: string
  /** 双击强制切换：true → 显示 HQ，false → 恢复画质模式决定 */
  forceHq: boolean
}

const props = withDefaults(defineProps<Props>(), {
  alt: '',
  aspectRatio: DEFAULT_ASPECT_RATIO,
  lqStatus: 'NOT_GENERATED',
  forceHq: false,
})

// ── 响应式状态 ──
const currentSrc = ref<string | undefined>(undefined)
const isHqLoaded = ref(false)
const isHqLoading = ref(false)
const hqError = ref(false)
const lqError = ref(false)
let hqLoader: HTMLImageElement | null = null

// ── 派生：实际生效模式（forceHq 覆盖）──
const effectiveMode = computed<QualityMode>(() => {
  if (props.forceHq) return 'HQ_ONLY'
  return props.mode
})

const lqAvailable = computed(() => props.lqStatus === 'READY')

// ── UI 状态 ──
const error = computed(() => {
  const m = effectiveMode.value
  if (m === 'HQ_ONLY') return hqError.value
  if (m === 'LQ_ONLY') return lqError.value
  return lqError.value && hqError.value
})

const showSkeleton = computed(() => {
  if (currentSrc.value) return false
  const m = effectiveMode.value
  if (m === 'HQ_ONLY') return !hqError.value
  if (m === 'LQ_ONLY') return !lqError.value
  return !lqError.value && !hqError.value
})

const showImage = computed(() => currentSrc.value !== undefined && !error.value)

const imageClasses = computed(() => ({
  'fade-in': effectiveMode.value === 'AUTO' && isHqLoaded.value && currentSrc.value === props.hq,
}))

const containerStyle = computed(() => ({
  aspectRatio: `${props.aspectRatio}`,
  width: '100%',
}))

// ── 图片加载 ──

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

  cancelHqLoader()
  isHqLoading.value = true

  const loader = new Image()
  loader.decoding = 'async'
  hqLoader = loader

  loader.onload = () => {
    if (hqLoader !== loader) return
    isHqLoaded.value = true
    isHqLoading.value = false
    if (props.hq) {
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
  // LQ 加载完成后，智能模式自动触发 HQ 渐进加载
  if (currentSrc.value === props.lq && effectiveMode.value === 'AUTO') {
    loadHq()
  }
}

function onImageError(): void {
  if (currentSrc.value === props.lq) {
    lqError.value = true
    // LQ 失败，智能模式降级尝试 HQ
    if (effectiveMode.value === 'AUTO') {
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
  const m = effectiveMode.value

  if (m === 'HQ_ONLY') {
    // 原图：优先 HQ，HQ 失败降级 LQ
    currentSrc.value = props.hq ?? props.lq ?? undefined
  } else if (m === 'LQ_ONLY') {
    // 省流：优先 LQ，LQ 不可用降级 HQ
    if (lqAvailable.value && props.lq) {
      currentSrc.value = props.lq
    } else if (props.hq) {
      currentSrc.value = props.hq
    }
  } else {
    // 智能：LQ 可用先显示 LQ 再渐进 HQ；LQ 不可用直接 HQ
    if (lqAvailable.value && props.lq) {
      currentSrc.value = props.lq
    } else if (props.hq) {
      currentSrc.value = props.hq
    }
  }
}

// ── 生命周期 ──

onMounted(() => {
  applyInitialSrc()
})

onBeforeUnmount(() => {
  cancelHqLoader()
})

watch(() => [props.lq, props.hq, props.mode, props.lqStatus, props.forceHq], () => {
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
