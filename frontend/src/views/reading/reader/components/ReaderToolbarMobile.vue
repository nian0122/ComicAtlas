<template>
  <!-- 移动端顶部工具栏：返回 / 漫画名 / 更多（打开设置抽屉） -->
  <header class="reader-toolbar-mobile">
    <!-- 返回按钮 -->
    <button
      class="toolbar-btn"
      type="button"
      aria-label="返回"
      @click="emit('back')"
    >
      <svg
        width="24"
        height="24"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M15 18l-6-6 6-6" />
      </svg>
    </button>

    <!-- 漫画标题（超长省略） -->
    <span class="toolbar-title">{{ title }}</span>

    <!-- 更多入口 ⋯（打开设置抽屉） -->
    <button
      class="toolbar-btn"
      type="button"
      aria-label="阅读设置"
      @click="emit('openSettings')"
    >
      <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
        <circle cx="5" cy="12" r="2" />
        <circle cx="12" cy="12" r="2" />
        <circle cx="19" cy="12" r="2" />
      </svg>
    </button>
  </header>
</template>

<script setup lang="ts">
// 哑组件：props 进、emits 出，不接触任何 store / composable。
// 显示与隐藏由父级（ReaderPage）通过 v-if 控制。
interface Props {
  /** 漫画名（移动端不展示长章节标题） */
  title: string
}

defineProps<Props>()

const emit = defineEmits<{
  (e: 'back'): void
  (e: 'openSettings'): void
}>()
</script>

<style scoped>
.reader-toolbar-mobile {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  /* 内容区高度 48px + 刘海安全区 */
  height: calc(48px + env(safe-area-inset-top));
  padding: env(safe-area-inset-top) var(--space-xs) 0;
  /* 半透明深色背景 + 毛玻璃 */
  background: var(--bg-primary);
  background: color-mix(in srgb, var(--bg-primary) 80%, transparent);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  animation: toolbar-fade-in 200ms ease both;
}

@keyframes toolbar-fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* 触控目标 ≥ 48px */
.toolbar-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.toolbar-btn:active {
  background: var(--bg-surface);
}

.toolbar-title {
  flex: 1;
  min-width: 0;
  max-width: 60vw;
  margin: 0 auto;
  text-align: center;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (prefers-reduced-motion: reduce) {
  .reader-toolbar-mobile {
    animation: none;
  }
}
</style>
