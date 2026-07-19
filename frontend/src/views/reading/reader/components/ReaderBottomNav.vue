<template>
  <!-- 移动端底部导航：可拖进度条(跳页) + 上一话 / 目录 / 下一话 -->
  <nav class="reader-bottom-nav" aria-label="章节导航">
    <div class="nav-progress-wrap">
      <span v-if="dragging" class="nav-progress-tip">{{ dragPage }} / {{ totalPages }}</span>
      <input
        class="nav-progress-slider"
        type="range"
        :min="1"
        :max="Math.max(1, totalPages)"
        step="1"
        :value="displayPage"
        :style="sliderStyle"
        aria-label="阅读进度"
        @input="onSliderInput"
        @change="onSliderChange"
      />
    </div>

    <div class="nav-buttons">
      <!-- 上一话（无上一话时禁用，保持布局稳定） -->
      <button
        class="nav-btn"
        type="button"
        :disabled="!hasPrev"
        @click="emit('prevChapter')"
      >
        ← 上一话
      </button>

      <!-- 目录 -->
      <button class="nav-btn" type="button" @click="emit('catalog')">
        目录
      </button>

      <!-- 下一话（无下一话时禁用） -->
      <button
        class="nav-btn"
        type="button"
        :disabled="!hasNext"
        @click="emit('nextChapter')"
      >
        下一话 →
      </button>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

// 哑组件：只负责导航展示，不接触 store / composable。
// 显示与隐藏由父级（ReaderPage）通过 v-if 控制。
interface Props {
  /** 当前页码（1 起） */
  currentPage: number
  /** 总页数 */
  totalPages: number
  /** 是否存在上一话 */
  hasPrev: boolean
  /** 是否存在下一话 */
  hasNext: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'prevChapter'): void
  (e: 'catalog'): void
  (e: 'nextChapter'): void
  (e: 'jumpToPage', page: number): void
}>()

const dragging = ref(false)
const dragPage = ref(1)

const displayPage = computed(() => (dragging.value ? dragPage.value : props.currentPage))

const fillPercent = computed(() => {
  if (props.totalPages <= 0) return 0
  return Math.min(100, Math.max(0, (displayPage.value / props.totalPages) * 100))
})

const sliderStyle = computed(() => ({
  background: `linear-gradient(to right, var(--accent) ${fillPercent.value}%, var(--bg-surface) ${fillPercent.value}%)`,
}))

function onSliderInput(e: Event) {
  dragging.value = true
  dragPage.value = Number((e.target as HTMLInputElement).value)
}

function onSliderChange(e: Event) {
  dragging.value = false
  emit('jumpToPage', Number((e.target as HTMLInputElement).value))
}
</script>

<style scoped>
.reader-bottom-nav {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 30;
  display: flex;
  flex-direction: column;
  /* 内容区高度 56px + 底部安全区（手势条） */
  height: calc(56px + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  /* 半透明深色背景 + 毛玻璃，与顶部工具栏一致 */
  background: var(--bg-primary);
  background: color-mix(in srgb, var(--bg-primary) 80%, transparent);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  animation: nav-fade-in 200ms ease both;
}

@keyframes nav-fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* 可拖进度条:轨道 3px 视觉 + 上下 padding 扩大触控热区,thumb 14px */
.nav-progress-wrap {
  position: relative;
  padding: 10px 12px 2px;
}

.nav-progress-slider {
  -webkit-appearance: none;
  appearance: none;
  display: block;
  width: 100%;
  height: 3px;
  border-radius: 2px;
  border: none;
  outline: none;
  cursor: pointer;
}

.nav-progress-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--accent);
  border: none;
}

.nav-progress-slider::-moz-range-thumb {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--accent);
  border: none;
}

.nav-progress-tip {
  position: absolute;
  top: -26px;
  left: 50%;
  transform: translateX(-50%);
  padding: 2px 10px;
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  color: var(--text-primary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.nav-buttons {
  flex: 1;
  display: flex;
  align-items: stretch;
}

/* 大触控目标：按钮撑满剩余高度（≥ 48px） */
.nav-btn {
  flex: 1;
  min-height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 var(--space-sm);
  background: transparent;
  border: none;
  color: var(--text-primary);
  font-size: 14px;
  white-space: nowrap;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.nav-btn:active:not(:disabled) {
  background: var(--bg-surface);
}

.nav-btn:disabled {
  opacity: 0.35;
  cursor: default;
}

@media (prefers-reduced-motion: reduce) {
  .reader-bottom-nav {
    animation: none;
  }
}
</style>
