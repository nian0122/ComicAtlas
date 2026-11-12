<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import ManagementIcon from './ManagementIcon.vue'
const props = withDefaults(defineProps<{ src: string | null; alt?: string }>(), { alt: '' })
const emit = defineEmits<{ failed: []; ready: [] }>()
const failed = ref(false),
  unavailable = ref(false),
  version = ref(0)
let attempts = 0,
  timer: ReturnType<typeof setTimeout> | undefined
watch(
  () => props.src,
  () => {
    clearTimeout(timer)
    attempts = 0
    failed.value = false
    unavailable.value = false
    version.value++
  },
)
function retry() {
  unavailable.value = true
  if (attempts >= 2) {
    failed.value = true
    emit('failed')
    return
  }
  attempts++
  timer = setTimeout(() => {
    version.value++
    unavailable.value = false
  }, attempts * 700)
}
onBeforeUnmount(() => clearTimeout(timer))
</script>
<template>
  <img
    v-if="src && !unavailable"
    :key="version"
    :src="src"
    :alt="alt"
    loading="lazy"
    @error="retry"
    @load="emit('ready')"
  /><span
    v-else
    class="mw-image-empty"
    role="img"
    :aria-label="`${alt ? alt + '：' : ''}${src ? (failed ? '预览加载失败' : '正在重试预览') : '无预览'}`"
    ><ManagementIcon
      :name="src ? (failed ? 'warning' : 'loading') : 'image'"
      :class="{ 'mw-spin': src && !failed }"
      :size="24"
  /></span>
</template>
