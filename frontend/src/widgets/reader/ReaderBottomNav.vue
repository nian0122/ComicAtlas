<template>
  <!-- 移动端底部导航：阅读进度展示 + 独立页码跳转 + 章节导航 -->
  <nav class="reader-bottom-nav" aria-label="章节导航">
    <div class="nav-progress-wrap">
      <AppButton
        type="button"
        class="nav-page-progress"
        aria-haspopup="dialog"
        :aria-expanded="jumpVisible"
        @click="openPageJump"
      >
        第 {{ currentPage }} / {{ totalPages }} 页
      </AppButton>
    </div>

    <div class="nav-buttons">
      <!-- 上一话（无上一话时禁用，保持布局稳定） -->
      <AppButton class="nav-btn" type="button" :disabled="!hasPrev" @click="emit('prevChapter')">← 上一话</AppButton>

      <!-- 目录 -->
      <AppButton class="nav-btn" type="button" @click="emit('catalog')">目录</AppButton>

      <!-- 下一话（无下一话时禁用） -->
      <AppButton class="nav-btn" type="button" :disabled="!hasNext" @click="emit('nextChapter')">下一话 →</AppButton>
    </div>
  </nav>

  <Transition name="page-jump">
    <div v-if="jumpVisible" class="mobile-page-jump" @click.self="closePageJump">
      <section class="page-jump-dialog" role="dialog" aria-modal="true" aria-labelledby="mobile-page-jump-title">
        <header class="page-jump-header">
          <div>
            <span id="mobile-page-jump-title">跳转页码</span>
            <span>输入 1 – {{ totalPages }} 之间的页码</span>
          </div>
          <AppButton type="button" class="page-jump-close" aria-label="关闭跳转窗口" @click="closePageJump">×</AppButton>
        </header>
        <div class="page-jump-control-row">
          <div class="page-jump-input-row">
            <AppButton type="button" class="page-step" aria-label="上一页" :disabled="jumpPage <= 1" @click="adjustPage(-1)">
              −
            </AppButton>
            <input
              v-model.number="jumpPage"
              type="number"
              min="1"
              :max="Math.max(1, totalPages)"
              inputmode="numeric"
              aria-label="输入页码"
              @keydown.enter="confirmPageJump"
            />
            <AppButton
              type="button"
              class="page-step"
              aria-label="下一页"
              :disabled="jumpPage >= totalPages"
              @click="adjustPage(1)"
            >
              +
            </AppButton>
          </div>
          <AppButton variant="primary" type="button" class="page-jump-confirm" @click="confirmPageJump">跳转</AppButton>
        </div>
      </section>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { ref } from 'vue'
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

const jumpVisible = ref(false)
const jumpPage = ref(1)

function openPageJump() {
  jumpPage.value = props.currentPage
  jumpVisible.value = true
}

function closePageJump() {
  jumpVisible.value = false
}

function adjustPage(delta: number) {
  jumpPage.value = Math.min(Math.max(1, Number(jumpPage.value) + delta), Math.max(1, props.totalPages))
}

function confirmPageJump() {
  const targetPage = Math.min(
    Math.max(1, Math.round(Number(jumpPage.value) || props.currentPage)),
    Math.max(1, props.totalPages),
  )
  jumpPage.value = targetPage
  jumpVisible.value = false
  if (targetPage !== props.currentPage) emit('jumpToPage', targetPage)
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
  min-width: 0;
  min-height: 30px;
  padding: 0 var(--space-3);
  border: 0;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-secondary);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.nav-page-progress:active {
  background: rgb(255 255 255 / 10%);
  color: var(--text-primary);
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

.mobile-page-jump {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgb(0 0 0 / 34%);
  animation: page-jump-fade 160ms ease both;
}

.page-jump-dialog {
  position: absolute;
  right: var(--mobile-page-gutter);
  bottom: calc(var(--mobile-tabbar-height) + var(--space-4) + env(safe-area-inset-bottom));
  left: var(--mobile-page-gutter);
  display: grid;
  gap: var(--space-3);
  padding: 18px;
  color: rgb(255 255 255 / 94%);
  background: rgb(22 22 22 / 98%);
  border: 1px solid rgb(255 255 255 / 16%);
  border-radius: 16px;
  box-shadow: 0 24px 56px rgb(0 0 0 / 56%);
}

.page-jump-header,
.page-jump-input-row,
.page-jump-control-row {
  display: flex;
  align-items: center;
}

.page-jump-header {
  justify-content: space-between;
}

.page-jump-header > div {
  display: grid;
  gap: 3px;
}

.page-jump-header span:first-child {
  font-size: 15px;
  font-weight: 750;
}

.page-jump-header span:last-child {
  color: rgb(255 255 255 / 52%);
  font-size: 12px;
}

.page-jump-close {
  width: 32px;
  min-width: 32px;
  height: 32px;
  min-height: 32px;
  padding: 0;
  color: rgb(255 255 255 / 60%);
  font-size: 22px;
  font-weight: 300;
  line-height: 1;
  background: rgb(255 255 255 / 6%);
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: 50%;
}

.page-jump-control-row {
  gap: var(--space-3);
}

.page-jump-input-row {
  flex: 1;
  height: 48px;
  overflow: hidden;
  background: rgb(0 0 0 / 42%);
  border: 1px solid rgb(255 255 255 / 16%);
  border-radius: 8px;
}

.page-jump-input-row input {
  width: 100%;
  min-width: 0;
  height: 100%;
  padding: 0 var(--space-3);
  color: rgb(255 255 255 / 94%);
  font: inherit;
  font-size: 17px;
  font-variant-numeric: tabular-nums;
  text-align: center;
  background: transparent;
  border: 0;
  outline: 0;
  appearance: textfield;
}

.page-jump-input-row input::-webkit-inner-spin-button,
.page-jump-input-row input::-webkit-outer-spin-button {
  margin: 0;
  appearance: none;
}

.page-step {
  width: 48px;
  height: 48px;
  min-width: 48px;
  padding: 0;
  color: rgb(255 255 255 / 76%);
  font-size: 19px;
  background: transparent;
  border: 0;
  border-radius: 0;
}

.page-step:first-child {
  border-right: 1px solid rgb(255 255 255 / 12%);
}

.page-step:last-child {
  border-left: 1px solid rgb(255 255 255 / 12%);
}

.page-jump-confirm {
  min-width: 68px;
  min-height: 48px;
  padding: 0 var(--space-3);
  border-radius: 9px;
  font-size: 14px;
  font-weight: 750;
  box-shadow: 0 8px 18px rgb(229 9 20 / 24%);
}

@keyframes page-jump-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}

.page-jump-enter-active .page-jump-dialog,
.page-jump-leave-active .page-jump-dialog {
  transition: transform 160ms ease, opacity 160ms ease;
}

.page-jump-enter-from .page-jump-dialog,
.page-jump-leave-to .page-jump-dialog {
  opacity: 0;
  transform: translateY(8px);
}

@media (prefers-reduced-motion: reduce) {
  .reader-bottom-nav {
    animation: none;
  }
}
</style>
