<template>
  <!-- 移动端底部导航：阅读进度展示 + 独立页码跳转 + 章节导航 -->
  <nav class="reader-bottom-nav" aria-label="章节导航">
    <div class="nav-progress-wrap">
      <AppButton
        id="mobile-reader-page-progress"
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
      <section
        ref="jumpDialogRef"
        class="page-jump-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="mobile-page-jump-title"
        tabindex="-1"
        @keydown.esc.stop="closePageJump"
        @keydown.tab="trapDialogFocus"
      >
        <header class="page-jump-header">
          <div>
            <span class="page-jump-eyebrow">阅读定位</span>
            <h2 id="mobile-page-jump-title">跳转页码</h2>
          </div>
          <AppButton type="button" class="page-jump-close" aria-label="关闭跳转窗口" @click="closePageJump"
            >×</AppButton
          >
        </header>

        <div class="page-jump-current">
          <span>当前阅读</span>
          <strong
            >第 {{ currentPage }} 页 <small>/ 共 {{ totalPages }} 页</small></strong
          >
          <div class="page-jump-current-track" aria-hidden="true">
            <span :style="{ width: `${currentProgress}%` }" />
          </div>
        </div>

        <div class="page-jump-field">
          <div class="page-jump-field-label">
            <label for="mobile-page-jump-input">目标页码</label>
            <span>可选 1–{{ totalPages }} 页</span>
          </div>
          <div class="page-jump-value">
            <input
              id="mobile-page-jump-input"
              v-model="jumpPageInput"
              class="unstyled-input"
              type="text"
              inputmode="numeric"
              pattern="[0-9]*"
              autocomplete="off"
              aria-describedby="mobile-page-jump-help"
              :aria-invalid="validJumpPage === null"
              @keydown.enter.stop.prevent="confirmPageJump"
            />
            <span>/ {{ totalPages }}</span>
          </div>
          <p
            id="mobile-page-jump-help"
            class="page-jump-help"
            :class="{ 'is-error': validJumpPage === null }"
            :role="validJumpPage === null ? 'alert' : undefined"
          >
            {{ validJumpPage === null ? `请输入 1–${totalPages} 之间的整数页码` : '输入页码，或拖动下方滑杆' }}
          </p>
          <input
            class="page-jump-range"
            type="range"
            min="1"
            :max="Math.max(1, totalPages)"
            :value="validJumpPage ?? currentPage"
            :style="{ '--jump-progress': `${selectedProgress}%` }"
            :disabled="totalPages <= 1"
            aria-label="拖动选择目标页码"
            @input="onRangeInput"
          />
          <div class="page-jump-quick" aria-label="快捷页码">
            <AppButton type="button" :class="{ 'is-selected': validJumpPage === 1 }" @click="selectPage(1)">
              第 1 页
            </AppButton>
            <AppButton
              type="button"
              :class="{ 'is-selected': validJumpPage === currentPage }"
              @click="selectPage(currentPage)"
            >
              当前页
            </AppButton>
            <AppButton
              type="button"
              :class="{ 'is-selected': validJumpPage === totalPages }"
              @click="selectPage(totalPages)"
            >
              最后一页
            </AppButton>
          </div>
        </div>

        <AppButton
          variant="primary"
          type="button"
          class="page-jump-confirm"
          :disabled="validJumpPage === null"
          @click="confirmPageJump"
        >
          {{ validJumpPage === currentPage ? '返回阅读' : `跳转到第 ${validJumpPage ?? '—'} 页` }}
        </AppButton>
      </section>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { computed, nextTick, ref } from 'vue'
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
const jumpPageInput = ref('1')
const jumpDialogRef = ref<HTMLElement | null>(null)
const validJumpPage = computed(() => {
  if (!/^\d+$/.test(jumpPageInput.value)) return null
  const page = Number(jumpPageInput.value)
  return Number.isSafeInteger(page) && page >= 1 && page <= props.totalPages ? page : null
})
const currentProgress = computed(() =>
  props.totalPages > 0 ? Math.min(100, Math.max(0, (props.currentPage / props.totalPages) * 100)) : 0,
)
const selectedProgress = computed(() =>
  props.totalPages > 1 ? (((validJumpPage.value ?? props.currentPage) - 1) / (props.totalPages - 1)) * 100 : 0,
)

function openPageJump() {
  jumpPageInput.value = String(props.currentPage)
  jumpVisible.value = true
  void nextTick(() => jumpDialogRef.value?.focus())
}

function closePageJump() {
  jumpVisible.value = false
  void nextTick(() => document.getElementById('mobile-reader-page-progress')?.focus())
}

function trapDialogFocus(event: KeyboardEvent) {
  const controls = jumpDialogRef.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled)')
  if (!controls?.length) return
  const firstControl = controls[0]
  const lastControl = controls[controls.length - 1]
  if (event.shiftKey && (document.activeElement === firstControl || document.activeElement === jumpDialogRef.value)) {
    event.preventDefault()
    lastControl.focus()
  } else if (!event.shiftKey && document.activeElement === lastControl) {
    event.preventDefault()
    firstControl.focus()
  }
}

function selectPage(page: number) {
  jumpPageInput.value = String(page)
}

function onRangeInput(event: Event) {
  jumpPageInput.value = (event.target as HTMLInputElement).value
}

function confirmPageJump() {
  const targetPage = validJumpPage.value
  if (targetPage === null) return
  closePageJump()
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
  background: var(--color-overlay-scrim);
  animation: page-jump-fade 160ms ease both;
}

.page-jump-dialog {
  position: absolute;
  right: var(--mobile-page-gutter);
  bottom: calc(var(--mobile-tabbar-height) + var(--space-3) + env(safe-area-inset-bottom));
  left: var(--mobile-page-gutter);
  display: grid;
  gap: var(--space-5);
  width: min(calc(100% - 2 * var(--mobile-page-gutter)), 440px);
  max-height: calc(100dvh - var(--mobile-tabbar-height) - var(--space-6) - env(safe-area-inset-bottom));
  padding: var(--space-5);
  margin: 0 auto;
  overflow-y: auto;
  color: var(--text-primary);
  background: var(--bg-secondary);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  box-shadow: var(--card-shadow-hover);
}

.page-jump-dialog:focus {
  outline: none;
}

.page-jump-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.page-jump-header > div {
  display: grid;
  gap: var(--space-1);
}

.page-jump-eyebrow {
  color: var(--accent);
  font-size: var(--text-micro);
  font-weight: 700;
  letter-spacing: var(--tracking-kicker);
}

.page-jump-header h2 {
  color: var(--text-primary);
  font-size: var(--text-lg);
  line-height: 1.2;
}

.page-jump-close {
  width: var(--control-min-size);
  min-width: var(--control-min-size);
  height: var(--control-min-size);
  min-height: var(--control-min-size);
  padding: 0;
  color: var(--text-secondary);
  font-size: 24px;
  font-weight: 300;
  line-height: 1;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: 50%;
}

.page-jump-current {
  display: grid;
  gap: var(--space-2);
  padding: var(--space-4);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.page-jump-current > span,
.page-jump-field-label span,
.page-jump-help {
  color: var(--text-muted);
  font-size: var(--text-xs);
}

.page-jump-current strong {
  font-size: var(--text-md);
  font-variant-numeric: tabular-nums;
}

.page-jump-current small {
  color: var(--text-secondary);
  font-size: var(--text-xs);
  font-weight: 400;
}

.page-jump-current-track {
  height: 3px;
  overflow: hidden;
  background: var(--color-progress-track);
  border-radius: var(--radius-pill);
}

.page-jump-current-track span {
  display: block;
  height: 100%;
  background: var(--accent);
}

.page-jump-field {
  display: grid;
  gap: var(--space-3);
}

.page-jump-field-label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-2);
}

.page-jump-field-label label {
  font-weight: 700;
}

.page-jump-value {
  display: flex;
  align-items: center;
  min-height: 58px;
  padding: 0 var(--space-4);
  background: var(--control-bg);
  border: 1px solid var(--control-border);
  border-radius: var(--control-radius);
}

.page-jump-value:focus-within {
  border-color: var(--control-focus-border);
  box-shadow: 0 0 0 3px var(--control-focus-ring);
}

.page-jump-value input {
  width: 100%;
  min-width: 0;
  height: 56px;
  padding: 0;
  color: var(--text-primary);
  font: inherit;
  font-size: 28px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  background: transparent;
  border: 0;
  outline: 0;
}

.page-jump-value > span {
  color: var(--text-muted);
  font-size: var(--text-sm);
  white-space: nowrap;
}

.page-jump-help {
  min-height: 18px;
  margin: calc(-1 * var(--space-2)) 0 0;
}

.page-jump-help.is-error {
  color: var(--danger);
}

.page-jump-range {
  width: 100%;
  height: 32px;
  margin: 0;
  appearance: none;
  background: transparent;
  cursor: pointer;
}

.page-jump-range::-webkit-slider-runnable-track {
  height: 4px;
  background: linear-gradient(
    to right,
    var(--accent) 0,
    var(--accent) var(--jump-progress),
    var(--control-border) var(--jump-progress),
    var(--control-border) 100%
  );
  border-radius: var(--radius-pill);
}

.page-jump-range::-webkit-slider-thumb {
  width: 20px;
  height: 20px;
  margin-top: -8px;
  appearance: none;
  background: var(--text-primary);
  border: 4px solid var(--accent);
  border-radius: 50%;
}

.page-jump-range::-moz-range-track {
  height: 4px;
  background: var(--control-border);
  border-radius: var(--radius-pill);
}

.page-jump-range::-moz-range-progress {
  height: 4px;
  background: var(--accent);
  border-radius: var(--radius-pill);
}

.page-jump-range::-moz-range-thumb {
  width: 12px;
  height: 12px;
  background: var(--text-primary);
  border: 4px solid var(--accent);
  border-radius: 50%;
}

.page-jump-quick {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-2);
}

.page-jump-quick .app-button {
  min-width: 0;
  min-height: var(--control-min-size);
  padding: 0 var(--space-1);
  color: var(--text-secondary);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
}

.page-jump-quick .app-button.is-selected {
  color: var(--text-primary);
  background: var(--accent-bg);
  border-color: var(--accent-border);
}

.page-jump-confirm {
  width: 100%;
  min-height: 48px;
  border-radius: var(--control-radius);
  font-weight: 700;
}

@keyframes page-jump-fade {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.page-jump-enter-active .page-jump-dialog,
.page-jump-leave-active .page-jump-dialog {
  transition:
    transform 160ms ease,
    opacity 160ms ease;
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
