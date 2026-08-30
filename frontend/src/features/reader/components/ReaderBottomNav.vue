<template>
  <!-- 移动端底部导航：阅读进度展示 + 独立页码跳转 + 章节导航 -->
  <nav class="reader-bottom-nav" aria-label="章节导航">
    <div class="nav-progress-wrap">
      <span class="nav-page-progress">第 {{ currentPage }} / {{ totalPages }} 页</span>
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

defineProps<Props>()

const emit = defineEmits<{
  (e: 'prevChapter'): void
  (e: 'catalog'): void
  (e: 'nextChapter'): void
  (e: 'jumpToPage', page: number): void
}>()

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
  /* 内容区与阅读端底栏一致，确保进度条和 48px 触控按钮不越出视口。 */
  height: calc(var(--mobile-tabbar-height) + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  /* 半透明深色背景 + 毛玻璃，与顶部工具栏一致 */
  background: var(--bg-primary);
  background: rgb(8 8 8 / 88%);
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

/* 只读进度条：连续阅读只更新展示，不触发页码定位。 */
.nav-progress-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 30px;
  padding: 0 12px;
}

.nav-page-progress {
  display: inline-flex;
  color: var(--text-secondary);
  font-size: 11px;
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
