<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { ChapterResponse } from '@/entities/chapter'
import { ManagementIcon, ManagementImage } from '@/shared/ui/management'
const props = defineProps<{ items: ChapterResponse['items']; active: string; title: string }>()
const emit = defineEmits<{ close: []; change: [id: string] }>()
const dialog = ref<HTMLDialogElement>(),
  failed = ref(false)
const index = computed(() => props.items.findIndex((item) => item.id === props.active))
const filmstrip = computed(() => props.items.slice(Math.max(0, index.value - 10), index.value + 11))
const current = computed(() => props.items[index.value])
watch(
  () => props.active,
  async (value) => {
    failed.value = false
    await nextTick()
    if (value && current.value && !dialog.value?.open) dialog.value?.showModal()
    if (!value) dialog.value?.close()
  },
  { immediate: true },
)
function step(direction: number) {
  const item = props.items[index.value + direction]
  if (item) emit('change', item.id)
}
function keyboard(event: KeyboardEvent) {
  if ((event.target as HTMLElement).tagName === 'VIDEO') return
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault()
    step(event.key === 'ArrowLeft' ? -1 : 1)
  }
}
</script>
<template>
  <dialog
    ref="dialog"
    class="mw-scope mw-media-dialog"
    aria-label="媒体预览"
    @cancel.prevent="emit('close')"
    @keydown="keyboard"
  >
    <template v-if="current">
      <header>
        <div>
          <strong>{{ current.fileName }}</strong
          ><small>{{ title }} · {{ index + 1 }} / {{ items.length }}</small>
        </div>
        <button class="mw-button" aria-label="关闭媒体预览" @click="emit('close')">
          <ManagementIcon name="close" />
        </button>
      </header>
      <div class="mw-media-stage">
        <button class="mw-icon-button" aria-label="上一项媒体" :disabled="index <= 0" @click="step(-1)">
          <ManagementIcon name="back" />
        </button>
        <div v-if="failed" class="mw-empty" role="status">
          <ManagementIcon name="warning" :size="32" />
          <p>当前媒体无法加载</p>
          <button class="mw-button" @click="failed = false">重试</button>
        </div>
        <video
          v-else-if="current.mediaType === 'video'"
          :key="current.id"
          :src="current.autoContentUrl"
          controls
          playsinline
          preload="metadata"
          @error="failed = true"
        />
        <img v-else :key="current.id" :src="current.autoContentUrl" :alt="current.fileName" @error="failed = true" />
        <button class="mw-icon-button" aria-label="下一项媒体" :disabled="index >= items.length - 1" @click="step(1)">
          <ManagementIcon name="right" />
        </button>
      </div>
      <nav class="mw-filmstrip" aria-label="预览缩略片带">
        <button
          v-for="item in filmstrip"
          :key="item.id"
          :aria-label="`预览${item.fileName}`"
          :aria-current="item.id === active ? 'true' : undefined"
          @click="emit('change', item.id)"
        >
          <ManagementImage :src="item.previewUrl" :alt="item.fileName" /><ManagementIcon
            v-if="item.mediaType === 'video'"
            name="video"
          />
        </button>
      </nav>
    </template>
  </dialog>
</template>
