<template>
  <div class="video-controls" :class="{ 'is-seeking': isSeeking }">
    <div class="video-time" aria-live="polite">{{ formatTime(displayTime) }} / {{ formatTime(duration) }}</div>
    <div
      ref="barRef"
      class="video-progress"
      :class="{ 'is-long-press': isLongPress }"
      role="slider"
      tabindex="0"
      :aria-valuenow="Math.round(displayTime)"
      aria-valuemin="0"
      :aria-valuemax="duration"
      aria-label="播放进度，可拖动调整"
      @pointerdown.stop.prevent="emit('pointerDown', $event)"
      @pointermove.stop.prevent="emit('pointerMove', $event)"
      @pointerup.stop.prevent="emit('pointerUp', $event)"
      @pointercancel.stop.prevent="emit('pointerUp', $event)"
      @touchstart.stop.prevent="emit('touchStart', $event)"
      @touchmove.stop.prevent="emit('touchMove', $event)"
      @touchend.stop.prevent="emit('touchEnd', $event)"
      @keydown="emit('keydown', $event)"
    >
      <span class="video-progress-fill" :style="{ width: `${progress * 100}%` }" />
      <i class="video-progress-thumb" :style="{ left: `${progress * 100}%` }" aria-hidden="true" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  displayTime: number
  duration: number
  progress: number
  isSeeking: boolean
  isLongPress: boolean
}>()

const emit = defineEmits<{
  pointerDown: [event: PointerEvent]
  pointerMove: [event: PointerEvent]
  pointerUp: [event: PointerEvent]
  touchStart: [event: TouchEvent]
  touchMove: [event: TouchEvent]
  touchEnd: [event: TouchEvent]
  keydown: [event: KeyboardEvent]
}>()

const barRef = ref<HTMLElement | null>(null)

defineExpose({
  getElement: (): HTMLElement | null => barRef.value,
})

function formatTime(value: number): string {
  if (!Number.isFinite(value) || value < 0) return '00:00'
  const totalSeconds = Math.floor(value)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`
}
</script>

<style scoped>
.video-controls {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 2;
  display: grid;
  gap: 0;
  padding: 0 18px calc(env(safe-area-inset-bottom) + 3px);
  background: linear-gradient(transparent, rgb(0 0 0 / 42%));
  transition: transform 160ms ease;
}

.video-controls.is-seeking {
  transform: translateY(-4px);
}

.video-time {
  color: #fffd;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  text-align: right;
  text-shadow: 0 1px 8px #000;
  opacity: 0;
  transition: opacity 160ms ease;
}

.video-controls.is-seeking .video-time {
  opacity: 1;
}

.video-progress {
  position: relative;
  width: 100%;
  height: 18px;
  cursor: pointer;
  touch-action: none;
}

.video-progress::before {
  position: absolute;
  right: 0;
  bottom: 3px;
  left: 0;
  height: 2px;
  border-radius: 999px;
  background: #fff5;
  content: '';
}

.video-progress-fill {
  position: absolute;
  bottom: 3px;
  left: 0;
  display: block;
  height: 2px;
  border-radius: 999px;
  background: #fff;
  transition:
    width 0.15s linear,
    height 160ms ease;
}

.video-progress-thumb {
  position: absolute;
  bottom: -1px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  transform: translateX(-50%) scale(0.75);
  background: #fff;
  box-shadow: 0 1px 8px #0008;
  opacity: 0;
  transition:
    transform 160ms ease,
    opacity 160ms ease,
    width 160ms ease,
    height 160ms ease;
}

.video-progress:hover::before,
.video-progress:focus-visible::before,
.video-progress.is-long-press::before,
.video-controls.is-seeking .video-progress::before {
  bottom: 2px;
  height: 4px;
  background: rgb(255 255 255 / 72%);
}

.video-progress:hover .video-progress-fill,
.video-progress:focus-visible .video-progress-fill,
.video-progress.is-long-press .video-progress-fill,
.video-controls.is-seeking .video-progress-fill {
  bottom: 2px;
  height: 4px;
}

.video-progress:hover .video-progress-thumb,
.video-progress:focus-visible .video-progress-thumb,
.video-progress.is-long-press .video-progress-thumb,
.video-controls.is-seeking .video-progress-thumb {
  bottom: 0;
  width: 12px;
  height: 12px;
  transform: translateX(-50%) scale(1);
  opacity: 1;
}

.video-controls.is-seeking .video-progress-fill {
  transition: height 160ms ease;
}

@media (prefers-reduced-motion: reduce) {
  .video-progress-fill {
    transition: none;
  }
}
</style>
