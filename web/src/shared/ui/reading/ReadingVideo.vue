<script setup lang="ts">
import { ref, watch } from 'vue'
import { AppButton } from '@/shared/ui/button'
const props = defineProps<{ src: string; preload: 'metadata' | 'none' }>()
const version = ref(0),
  error = ref('')
watch(
  () => props.src,
  () => {
    error.value = ''
    version.value++
  },
)
function fail(event: Event) {
  const code = (event.target as HTMLVideoElement).error?.code
  error.value =
    code === 3 || code === 4
      ? '当前浏览器无法播放此视频，可在管理端转为浏览器支持的格式后重试。'
      : '视频加载失败，请检查连接后重试。'
}
function retry() {
  error.value = ''
  version.value++
}
</script>
<template>
  <div class="reading-video">
    <video v-if="!error" :key="version" :src="src" controls :preload="preload" playsinline @error="fail" />
    <div v-else class="reading-video-error" role="alert">
      <p>{{ error }}</p>
      <AppButton size="sm" @click="retry">重新加载视频</AppButton>
    </div>
  </div>
</template>
<style scoped>
.reading-video {
  width: 100%;
  background: var(--atlas-bg);
}
.reading-video video {
  display: block;
  width: 100%;
  max-height: 100dvh;
}
.reading-video-error {
  min-height: 220px;
  padding: 28px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 15px;
  background: var(--atlas-surface);
  color: var(--atlas-muted);
  font-size: 13px;
  line-height: 1.8;
}
.reading-video-error p {
  max-width: 420px;
  margin: 0;
}
</style>
