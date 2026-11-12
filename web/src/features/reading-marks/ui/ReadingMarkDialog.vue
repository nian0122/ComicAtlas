<script setup lang="ts">
import { reactive, watch } from 'vue'
import { AppButton } from '@/shared/ui/button'
import { ReadingDialog, ReadingIcon } from '@/shared/ui/reading'
import { nextMark, type ReadingMarkTarget } from '../model/marks'
import { useReadingMarks } from '../model/use-reading-marks'

const props = defineProps<{ open: boolean; targets: ReadingMarkTarget[] }>()
const emit = defineEmits<{ close: [] }>()
const marks = reactive(useReadingMarks())

function signature() {
  return props.targets.map((target) => `${target.kind}:${target.id}`).join('|')
}

function reload() {
  if (props.targets.length) void marks.load(props.targets)
}

function toggle(target: ReadingMarkTarget, value: 'like' | 'dislike') {
  if (!marks.ready || marks.pending) return
  void marks.set(target, nextMark(marks.value(target), value))
}

watch(
  () => props.open,
  (open) => {
    if (open) reload()
    else marks.reset()
  },
  { immediate: true },
)
watch(signature, () => {
  if (props.open) reload()
})
</script>
<template>
  <ReadingDialog :open="open" title="标记" class="cinema-tool-sheet reading-mark-sheet" @close="emit('close')">
    <div v-if="marks.loading" class="reading-mark-status" role="status">正在读取标记…</div>
    <div v-else-if="marks.error" class="reading-mark-status is-error" role="alert">
      <span>{{ marks.error }}</span>
      <AppButton variant="text" @click="reload">重试</AppButton>
    </div>
    <template v-else>
      <div v-for="target in targets" :key="`${target.kind}:${target.id}`" class="reading-mark-row">
        <div class="reading-mark-copy">
          <strong>{{ target.label }}</strong>
          <span :title="target.title">{{ target.title }}</span>
        </div>
        <div class="reading-mark-actions" role="group" :aria-label="`${target.label}标记`">
          <button
            type="button"
            :class="{ 'is-on': marks.value(target) === 'like' }"
            :aria-label="marks.value(target) === 'like' ? `取消${target.label}喜欢` : `喜欢${target.label}`"
            :aria-pressed="marks.value(target) === 'like'"
            :disabled="!marks.ready || !!marks.pending"
            @click="toggle(target, 'like')"
          >
            <ReadingIcon name="heart" :size="18" /><span>喜欢</span>
          </button>
          <button
            type="button"
            :class="{ 'is-on': marks.value(target) === 'dislike' }"
            :aria-label="marks.value(target) === 'dislike' ? `取消${target.label}不喜欢` : `不喜欢${target.label}`"
            :aria-pressed="marks.value(target) === 'dislike'"
            :disabled="!marks.ready || !!marks.pending"
            @click="toggle(target, 'dislike')"
          >
            <ReadingIcon name="dislike" :size="18" /><span>不喜欢</span>
          </button>
        </div>
      </div>
      <p class="reading-mark-hint">作品、章节和当前媒体的标记彼此独立；再次点击已选标记即可清除。</p>
    </template>
  </ReadingDialog>
</template>
<style scoped>
.reading-mark-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px;
  color: #cfcbc4;
  font-size: 12px;
}
.reading-mark-status.is-error {
  color: #ffb4a8;
}
.reading-mark-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid #ffffff0f;
}
.reading-mark-copy {
  display: grid;
  gap: 5px;
  min-width: 0;
}
.reading-mark-copy strong {
  color: #f2f0ec;
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 0.08em;
}
.reading-mark-copy span {
  overflow: hidden;
  color: #8e8b86;
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.reading-mark-actions {
  display: flex;
  flex: none;
  gap: 6px;
}
.reading-mark-actions button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 40px;
  padding: 0 9px;
  color: #cfcbc4;
  background: transparent;
  border: 1px solid #ffffff1f;
  border-radius: 0;
  font: inherit;
  font-size: 11px;
  letter-spacing: 0.06em;
  cursor: pointer;
}
.reading-mark-actions button:hover:not(:disabled) {
  color: #f2f0ec;
  background: #ffffff0f;
}
.reading-mark-actions button.is-on {
  color: #f2f0ec;
  background: #ffffff17;
  border-color: #ffffff5c;
}
.reading-mark-actions button.is-on :deep(svg) {
  fill: currentColor;
}
.reading-mark-actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.reading-mark-actions button:focus-visible {
  outline: 2px solid #f2f0ec;
  outline-offset: 2px;
}
.reading-mark-hint {
  margin: 0;
  padding: 14px 20px 20px;
  color: #8e8b86;
  font-size: 11px;
  line-height: 1.7;
}
@media (max-width: 480px) {
  .reading-mark-row {
    align-items: stretch;
    flex-direction: column;
    gap: 12px;
  }
  .reading-mark-actions button {
    flex: 1;
    justify-content: center;
    min-height: 44px;
  }
}
</style>
