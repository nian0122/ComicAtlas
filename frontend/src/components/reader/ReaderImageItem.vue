<template>
  <div class="reader-image-item">
    <ProgressiveImage
      :lq="page.lqUrl"
      :hq="page.hqUrl"
      :mode="settings.qualityMode"
      :aspect-ratio="aspectRatio"
      :enable-progressive="settings.enableProgressiveImage"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useReaderSettingsStore } from '@/stores/reader-settings-store'
import ProgressiveImage from './ProgressiveImage.vue'
import type { PageInfo } from '@/types'

interface Props {
  item: PageInfo
  index: number
  active: boolean
}

const props = defineProps<Props>()
const settings = useReaderSettingsStore()

const page = computed(() => props.item)
const aspectRatio = computed(() => {
  if (page.value.width && page.value.height && page.value.height > 0) {
    return page.value.width / page.value.height
  }
  return 3 / 4
})
</script>

<style scoped>
.reader-image-item {
  width: 100%;
  display: flex;
  justify-content: center;
  padding: var(--space-sm) 0;
}
</style>
