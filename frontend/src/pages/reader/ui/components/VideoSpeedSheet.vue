<template>
  <div class="video-speed-sheet" @click.self="emit('close')">
    <section class="video-speed-panel" role="dialog" aria-modal="true" aria-label="播放速度">
      <div class="video-speed-handle" aria-hidden="true" />
      <h2>播放速度</h2>
      <div class="video-speed-options" role="listbox" aria-label="选择播放速度">
        <AppButton
          v-for="speed in rates"
          :key="speed"
          class="video-speed-option"
          type="button"
          role="option"
          :aria-selected="currentRate === speed"
          @click.stop="emit('select', speed)"
        >
          {{ formatRate(speed) }}×
        </AppButton>
      </div>
      <AppButton class="video-speed-close" type="button" @click="emit('close')">完成</AppButton>
    </section>
  </div>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'

defineProps<{
  currentRate: number
  rates: readonly number[]
}>()

const emit = defineEmits<{
  close: []
  select: [rate: number]
}>()

function formatRate(rate: number): string {
  return Number.isInteger(rate) ? String(rate) : rate.toFixed(2).replace(/0$/u, '')
}
</script>

<style scoped>
.video-speed-sheet {
  position: absolute;
  inset: 0;
  z-index: 4;
  display: flex;
  align-items: flex-end;
  background: rgb(0 0 0 / 35%);
}

.video-speed-panel {
  width: 100%;
  padding: 14px 18px calc(env(safe-area-inset-bottom) + 16px);
  border-radius: 20px 20px 0 0;
  background: rgb(22 22 24 / 98%);
  box-shadow: 0 10px 30px rgb(0 0 0 / 35%);
  backdrop-filter: blur(12px);
}

.video-speed-handle {
  width: 36px;
  height: 4px;
  margin: 0 auto 12px;
  border-radius: 999px;
  background: rgb(255 255 255 / 32%);
}

h2 {
  margin: 0 0 14px;
  color: #fff;
  font-size: 16px;
  text-align: center;
}

.video-speed-options {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}

.video-speed-option {
  min-height: 42px;
  padding: 0 6px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #fffc;
  font-size: 12px;
}

.video-speed-option[aria-selected='true'] {
  background: #fff;
  color: #111;
}

.video-speed-close {
  width: 100%;
  min-height: 42px;
  margin-top: 12px;
  border: 0;
  border-radius: 10px;
  background: rgb(255 255 255 / 10%);
  color: #fff;
}
</style>
