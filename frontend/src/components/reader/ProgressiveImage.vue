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

    <!-- LQ layer -->
    <img
      v-if="lq && showLq"
      :src="lq"
      class="progressive-layer lq-layer"
      :class="{ 'fade-out': hqVisible }"
      alt=""
      @load="onLqLoad"
      @error="onLqError"
    />

    <!-- HQ layer -->
    <img
      v-if="hq && showHq"
      :src="hq"
      class="progressive-layer hq-layer"
      :class="{ 'fade-in': hqVisible }"
      alt=""
      @load="onHqLoad"
      @error="onHqError"
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
import { ref, computed, watch, onMounted } from 'vue'
import { PictureFilled } from '@element-plus/icons-vue'
import type { QualityMode } from '@/stores/reader-settings-store'

interface Props {
  lq: string | null
  hq: string | null
  mode: QualityMode
  aspectRatio?: number
  enableProgressive?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  lq: null,
  hq: null,
  aspectRatio: 3 / 4,
  enableProgressive: true,
})

const hqLoaded = ref(false)
const hqError = ref(false)
const lqError = ref(false)
const error = computed(() => {
  if (props.mode === 'HQ_ONLY') return hqError.value
  if (props.mode === 'LQ_ONLY') return lqError.value
  return lqError.value && hqError.value
})

const showSkeleton = computed(() => {
  if (props.mode === 'HQ_ONLY') return !hqLoaded.value && !hqError.value
  if (props.mode === 'LQ_ONLY') return !lqLoaded.value && !lqError.value
  return !lqLoaded.value && !hqLoaded.value && !lqError.value && !hqError.value
})

const lqLoaded = ref(false)
const showLq = computed(() => {
  if (props.mode === 'HQ_ONLY') return false
  if (props.mode === 'LQ_ONLY') return true
  // AUTO: keep LQ visible until HQ is ready or LQ failed
  return !lqError.value
})

const showHq = computed(() => {
  if (props.mode === 'HQ_ONLY') return true
  if (props.mode === 'LQ_ONLY') return false
  return true
})

const hqVisible = computed(() => {
  if (props.mode === 'HQ_ONLY') return hqLoaded.value
  if (props.mode === 'LQ_ONLY') return false
  return hqLoaded.value && (lqLoaded.value || !props.lq || lqError.value)
})

const containerStyle = computed(() => ({
  aspectRatio: `${props.aspectRatio}`,
  width: '100%',
}))

let hqLoader: HTMLImageElement | null = null

function loadHq() {
  if (!props.hq || hqLoaded.value || hqError.value) return
  if (!props.enableProgressive && props.mode === 'AUTO') return

  hqLoader = new Image()
  hqLoader.onload = () => {
    hqLoaded.value = true
    hqLoader = null
  }
  hqLoader.onerror = () => {
    hqError.value = true
    hqLoader = null
  }
  hqLoader.src = props.hq
}

function onLqLoad() {
  lqLoaded.value = true
  if (props.mode === 'AUTO') {
    loadHq()
  }
}

function onLqError() {
  lqError.value = true
  if (props.mode === 'AUTO') {
    loadHq()
  }
}

function onHqLoad() {
  hqLoaded.value = true
}

function onHqError() {
  hqError.value = true
}

function reset() {
  hqLoaded.value = false
  hqError.value = false
  lqLoaded.value = false
  lqError.value = false
  if (hqLoader) {
    hqLoader.onload = null
    hqLoader.onerror = null
    hqLoader = null
  }
}

function retry() {
  reset()
  if (props.mode === 'HQ_ONLY') {
    loadHq()
  } else if (props.mode === 'LQ_ONLY') {
    lqLoaded.value = false
  } else {
    lqLoaded.value = false
    loadHq()
  }
}

onMounted(() => {
  if (props.mode === 'HQ_ONLY') {
    loadHq()
  }
})

watch(() => [props.lq, props.hq, props.mode], () => {
  reset()
  if (props.mode === 'HQ_ONLY') {
    loadHq()
  }
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
  transition: opacity 250ms ease;
}

.lq-layer {
  opacity: 1;
  z-index: 1;
}

.lq-layer.fade-out {
  opacity: 0;
}

.hq-layer {
  opacity: 0;
  z-index: 2;
}

.hq-layer.fade-in {
  opacity: 1;
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
</style>
