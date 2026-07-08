<template>
  <div
    class="reader-image-item"
    :class="{
      'fit-width': settings.fitMode === 'WIDTH',
      'fit-height': settings.fitMode === 'HEIGHT',
      'fit-original': settings.fitMode === 'ORIGINAL',
    }"
  >
    <ProgressiveImage
      :lq="page.lqUrl"
      :hq="page.hqUrl"
      :mode="settings.qualityMode"
      :aspect-ratio="aspectRatio"
      :enable-progressive="settings.enableProgressiveImage"
      :class="imageClasses"
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

const imageClasses = computed(() => ({
  'fit-width-image': settings.fitMode === 'WIDTH',
  'fit-height-image': settings.fitMode === 'HEIGHT',
  'fit-original-image': settings.fitMode === 'ORIGINAL',
  'fit-auto-image': settings.fitMode === 'AUTO',
}))
</script>

<style scoped>
.reader-image-item {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-sm) 0;
}

.reader-image-item :deep(.progressive-image) {
  width: auto;
  height: auto;
  max-width: 100%;
  max-height: 100%;
}

.reader-image-item.fit-width :deep(.progressive-image) {
  width: 100%;
  height: auto;
  max-height: none;
}

.reader-image-item.fit-height :deep(.progressive-image) {
  width: auto;
  height: 100%;
  max-width: none;
}

.reader-image-item.fit-original :deep(.progressive-image) {
  max-width: none;
  max-height: none;
}

.reader-image-item :deep(.progressive-layer) {
  transform-origin: center center;
}
</style>
