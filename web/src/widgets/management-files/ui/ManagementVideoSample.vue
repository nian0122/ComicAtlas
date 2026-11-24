<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

defineProps<{ source?: string }>()
const tile = ref<HTMLElement>()
const player = ref<HTMLVideoElement>()
const visible = ref(false)
const ready = ref(false)
const failed = ref(false)
const positions = [0.15, 0.5, 0.8] as const
let segment = 0
let hovering = false
let observer: IntersectionObserver
let delay: number | undefined

function pause() {
  window.clearTimeout(delay)
  hovering = false
  player.value?.pause()
}

function release() {
  pause()
  const video = player.value
  if (video) {
    video.removeAttribute('src')
    video.load()
  }
  visible.value = ready.value = failed.value = false
}

function seekStart() {
  const video = player.value
  if (!video || !Number.isFinite(video.duration) || video.duration <= 0) {
    failed.value = true
    return
  }
  video.currentTime = video.duration * positions[segment]!
}

function play() {
  const video = player.value
  if (video && hovering && ready.value && !video.seeking)
    void video.play().catch(() => {
      /* 浏览器拒绝自动播放时保留静态画面。 */
    })
}

function frameReady() {
  ready.value = true
  play()
}

function enter(event: PointerEvent) {
  if (event.pointerType !== 'mouse' || matchMedia('(prefers-reduced-motion: reduce)').matches) return
  delay = window.setTimeout(() => {
    hovering = true
    segment = 0
    if (ready.value) {
      seekStart()
      play()
    }
  }, 400)
}

function advance() {
  const video = player.value
  if (!video || !hovering || video.seeking || (video.paused && !video.ended)) return
  const end = video.duration * positions[segment]! + Math.min(1.5, video.duration * 0.14)
  if (video.currentTime < end) return
  segment = (segment + 1) % positions.length
  seekStart()
}

onMounted(() => {
  observer = new IntersectionObserver(([entry]) => {
    if (entry?.isIntersecting) {
      if (!visible.value) segment = 0
      visible.value = true
    } else release()
  })
  observer.observe(tile.value!)
  tile.value!.parentElement?.addEventListener('click', pause)
})
onBeforeUnmount(() => {
  observer.disconnect()
  tile.value?.parentElement?.removeEventListener('click', pause)
  release()
})
</script>

<template>
  <span ref="tile" class="mw-video-sample" @pointerenter="enter" @pointerleave="pause">
    <small v-if="!ready || failed">{{ failed || !source ? '无法预览' : '正在读取画面…' }}</small>
    <video
      v-if="visible && source && !failed"
      ref="player"
      :src="source"
      :class="{ ready }"
      muted
      playsinline
      preload="metadata"
      aria-hidden="true"
      @loadedmetadata="seekStart"
      @seeked="frameReady"
      @timeupdate="advance"
      @ended="advance"
      @error="failed = true"
    />
  </span>
</template>

<style scoped>
.mw-video-sample {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: var(--atlas-surface);
  color: var(--atlas-muted);
}
video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  pointer-events: none;
}
video.ready {
  opacity: 1;
}
</style>
