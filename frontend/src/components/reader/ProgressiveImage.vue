<template>
  <div
    class="progressive-image"
    :style="{ aspectRatio: `${aspectRatio}` }"
  >
    <div v-if="!imgLoaded && !imgError" class="progressive-skeleton" />
    <img
      v-if="hq"
      :src="hq"
      class="progressive-layer"
      :class="{ 'img-show': imgLoaded }"
      alt=""
      @load="imgLoaded = true"
      @error="imgError = true"
    />
    <div v-if="imgError" class="progressive-error">
      <el-icon :size="24"><PictureFilled /></el-icon>
      <span>加载失败</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { PictureFilled } from '@element-plus/icons-vue'

interface Props {
  lq?: string | null
  hq?: string | null
  mode?: string
  aspectRatio?: number
  enableProgressive?: boolean
}

defineProps<Props>()

const imgLoaded = ref(false)
const imgError = ref(false)
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
  opacity: 0;
  transition: opacity 250ms ease;
}

.progressive-layer.img-show {
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
  z-index: 1;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
</style>
