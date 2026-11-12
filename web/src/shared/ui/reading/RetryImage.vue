<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { AppButton } from '@/shared/ui/button'
import ReadingIcon from './ReadingIcon.vue'
const props = withDefaults(
  defineProps<{
    src?: string | null
    alt: string
    width?: number | null
    height?: number | null
    eager?: boolean
    priority?: 'high' | 'low'
    retries?: number
  }>(),
  { src: '', eager: false, priority: undefined, retries: 2, width: null, height: null },
)
const emit = defineEmits<{ loaded: []; failed: [] }>()
const attempts = ref(0),
  failed = ref(false),
  loaded = ref(false),
  version = ref(0),
  natural = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
const ratio = computed(() => (props.width && props.height ? `${props.width}/${props.height}` : natural.value || '2/3'))
watch(
  () => props.src,
  () => {
    clearTimeout(timer)
    attempts.value = 0
    failed.value = false
    loaded.value = false
    version.value++
    natural.value = ''
  },
)
function error() {
  if (attempts.value >= props.retries || !props.src) {
    failed.value = true
    emit('failed')
    return
  }
  attempts.value++
  timer = setTimeout(() => {
    version.value++
  }, attempts.value * 700)
}
function retry() {
  clearTimeout(timer)
  attempts.value = 0
  failed.value = false
  version.value++
}
watch(
  () => props.retries,
  (retries, previous) => {
    if (failed.value && previous === 0 && retries > 0) retry()
  },
)
async function success(event: Event) {
  const image = event.target as HTMLImageElement
  if (props.priority) {
    const current = version.value
    try {
      await image.decode()
    } catch {
      // 浏览器仍可显示已加载的图片，节点更换时由下方检查丢弃结果。
    }
    if (!image.isConnected || current !== version.value) return
  }
  natural.value = `${image.naturalWidth}/${image.naturalHeight}`
  loaded.value = true
  emit('loaded')
}
onBeforeUnmount(() => clearTimeout(timer))
</script>
<template>
  <div class="ca-image" :class="{ 'is-loaded': loaded }" :style="{ aspectRatio: ratio }">
    <img
      v-if="src && !failed"
      :key="`${src}:${version}`"
      :src="src"
      :alt="alt"
      :loading="eager ? 'eager' : 'lazy'"
      :fetchpriority="priority"
      decoding="async"
      draggable="false"
      @load="success"
      @error="error"
    />
    <div v-if="!loaded && !failed && src" class="ca-image-loading" aria-label="图片加载中" />
    <div v-if="failed || !src" class="ca-image-failure" role="status">
      <ReadingIcon name="missing" /><span>{{ src ? '图片暂时无法加载' : '暂无图片' }}</span
      ><AppButton v-if="src" size="sm" @click.stop="retry">重新加载</AppButton>
    </div>
  </div>
</template>
