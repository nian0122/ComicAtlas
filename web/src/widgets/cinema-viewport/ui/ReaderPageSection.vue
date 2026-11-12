<script setup lang="ts">
import { computed } from 'vue'
import type { MediaItemInfo } from '@/entities/media'
import { ReadingVideo, RetryImage } from '@/shared/ui/reading'
import { readerPageAspectRatio } from '../model/reader-page-size'
const props = defineProps<{
  page: MediaItemInfo
  pageNumber: number
  src?: string
  overlaySrc: string | null
  visible: boolean
  gap: boolean
}>()
const emit = defineEmits<{
  upgrade: [event: MouseEvent]
  highQualityLoaded: []
  highQualityFailed: []
  imageSettled: [loaded: boolean]
}>()
const aspectRatio = computed(() => readerPageAspectRatio(props.page))
</script>
<template>
  <section
    class="cinema-reader-image"
    :class="{ 'has-gap': gap }"
    :data-reader-page="pageNumber"
    @dblclick="emit('upgrade', $event)"
  >
    <div v-if="page.mediaType === 'video'" class="cinema-reader-video-frame" :style="{ aspectRatio }">
      <ReadingVideo :src="page.autoContentUrl" :preload="visible ? 'metadata' : 'none'" />
    </div>
    <div v-else class="cinema-reader-stack" :style="{ aspectRatio }">
      <RetryImage
        v-if="src !== undefined"
        :src="src"
        :alt="`第 ${pageNumber} 页`"
        eager
        :priority="visible ? 'high' : 'low'"
        :retries="visible ? 2 : 0"
        @loaded="emit('imageSettled', true)"
        @failed="emit('imageSettled', false)"
      />
      <div v-else class="ca-image-loading" aria-hidden="true" />
      <RetryImage
        v-if="overlaySrc"
        class="cinema-reader-hq"
        :src="overlaySrc"
        alt=""
        eager
        :retries="0"
        @loaded="emit('highQualityLoaded')"
        @failed="emit('highQualityFailed')"
      />
    </div>
  </section>
</template>
<style scoped>
.cinema-reader-image {
  width: 100%;
  touch-action: manipulation;
}
.cinema-reader-image.has-gap {
  padding-top: 12px;
}
.cinema-reader-stack {
  position: relative;
}
.cinema-reader-stack > :deep(.ca-image) {
  height: 100%;
}
.cinema-reader-video-frame {
  width: 100%;
  background: var(--atlas-bg);
}
.cinema-reader-video-frame :deep(.reading-video),
.cinema-reader-video-frame :deep(video) {
  height: 100%;
}
.cinema-reader-video-frame :deep(video) {
  object-fit: contain;
}
.cinema-reader-hq {
  position: absolute;
  inset: 0;
  background: transparent;
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--atlas-control-transition);
}
.cinema-reader-hq.is-loaded {
  opacity: 1;
}
.cinema-reader-hq :deep(.ca-image-loading),
.cinema-reader-hq :deep(.ca-image-failure) {
  display: none;
}
</style>
