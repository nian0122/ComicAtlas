<template>
  <!-- 移动端底部导航：进度条 + 上一话 / 目录 / 下一话 -->
  <nav class="reader-bottom-nav" aria-label="章节导航">
    <!-- 阅读进度条（非精确页码，仅示意位置） -->
    <div
      class="nav-progress"
      role="progressbar"
      aria-label="阅读进度"
      :aria-valuenow="currentPage"
      :aria-valuemin="0"
      :aria-valuemax="totalPages"
    >
      <div class="nav-progress-fill" :style="{ width: progressPercent + '%' }" />
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
import { computed } from 'vue'

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
}>()

/** 阅读进度百分比（0-100，防御除零与越界） */
const progressPercent = computed(() => {
  if (props.totalPages <= 0) return 0
  return Math.min(100, Math.max(0, (props.currentPage / props.totalPages) * 100))
})
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

/* 顶部细进度条 */
.nav-progress {
  height: 3px;
  background: var(--bg-surface);
}

.nav-progress-fill {
  height: 100%;
  background: var(--accent);
  transition: width 200ms ease;
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

  .nav-progress-fill {
    transition: none;
  }
}
</style>
