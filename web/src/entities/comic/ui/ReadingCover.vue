<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { RouterLink } from 'vue-router'
import { ReadingIcon } from '@/shared/ui/reading'
const props = withDefaults(
  defineProps<{
    id: string
    title: string
    cover?: string | null
    progress?: number
    to: string
    details?: boolean
    hoverTitle?: boolean
    caption?: string | null
    meta?: string
  }>(),
  { details: true, hoverTitle: false, cover: null, progress: undefined, caption: null, meta: '' },
)
const failed = ref(false),
  coverVersion = ref(0)
let coverAttempts = 0,
  coverTimer: ReturnType<typeof setTimeout> | undefined
watch(
  () => props.cover,
  () => {
    clearTimeout(coverTimer)
    coverAttempts = 0
    failed.value = false
  },
)
function coverError() {
  if (coverAttempts >= 2) {
    failed.value = true
    return
  }
  coverAttempts++
  coverTimer = setTimeout(() => {
    coverVersion.value++
  }, coverAttempts * 700)
}
onBeforeUnmount(() => clearTimeout(coverTimer))
</script>
<template>
  <article class="ca-cover" :class="{ 'is-title-hover': hoverTitle }">
    <RouterLink
      :to="to"
      class="ca-cover-main"
      :aria-label="`${to.startsWith('/reader/') ? '继续阅读' : '查看'}《${title}》`"
    >
      <img v-if="cover && !failed" :key="coverVersion" :src="cover" :alt="title" loading="lazy" @error="coverError" />
      <span v-else class="ca-cover-placeholder"
        ><ReadingIcon name="missing" /><span>{{ title }}</span></span
      >
      <span v-if="progress" class="ca-cover-progress"
        ><span :style="{ width: `${Math.max(0, Math.min(100, progress))}%` }"
      /></span>
    </RouterLink>
    <RouterLink v-if="details" class="ca-cover-details" :to="`/comic/${id}`" :aria-label="`《${title}》作品详情`"
      ><ReadingIcon name="info" :size="16"
    /></RouterLink>
    <div v-if="caption || meta" class="ca-cover-caption">
      <span class="ca-cover-title">{{ caption || title }}</span>
      <span v-if="meta" class="ca-cover-meta">{{ meta }}</span>
    </div>
  </article>
</template>
