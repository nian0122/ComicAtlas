<template>
  <!-- 移动端阅读设置抽屉：底部滑入，选项即时生效（直接写 reader-settings-store） -->
  <Transition name="drawer">
    <div
      v-if="visible"
      class="reader-settings-drawer"
      @click.self="emit('close')"
    >
      <section
        class="drawer-panel"
        role="dialog"
        aria-modal="true"
        aria-label="阅读设置"
      >
        <!-- 标题 + 关闭 -->
        <header class="drawer-header">
          <span class="drawer-header-spacer" aria-hidden="true" />
          <span class="drawer-title">阅读设置</span>
          <button
            class="drawer-close"
            type="button"
            aria-label="关闭"
            @click="emit('close')"
          >
            <svg
              width="22"
              height="22"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              aria-hidden="true"
            >
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </header>

        <div class="drawer-body">
          <!-- 阅读方向（高频优先；移动端只暴露 纵向/横向） -->
          <div class="setting-section">
            <span id="reader-direction-label" class="setting-label">阅读方向</span>
            <div class="segmented" role="radiogroup" aria-labelledby="reader-direction-label">
              <label
                v-for="option in directionOptions"
                :key="option.value"
                class="segment"
                :class="{ active: settings.readingDirection === option.value }"
              >
                <input
                  class="visually-hidden"
                  type="radio"
                  name="reader-direction"
                  :value="option.value"
                  :checked="settings.readingDirection === option.value"
                  @change="settings.setReadingDirection(option.value)"
                />
                {{ option.label }}
              </label>
            </div>
          </div>

          <!-- 适配模式 -->
          <div class="setting-section">
            <span id="reader-fit-label" class="setting-label">适配模式</span>
            <div class="segmented" role="radiogroup" aria-labelledby="reader-fit-label">
              <label
                v-for="option in fitOptions"
                :key="option.value"
                class="segment"
                :class="{ active: settings.fitMode === option.value }"
              >
                <input
                  class="visually-hidden"
                  type="radio"
                  name="reader-fit"
                  :value="option.value"
                  :checked="settings.fitMode === option.value"
                  @change="settings.setFitMode(option.value)"
                />
                {{ option.label }}
              </label>
            </div>
          </div>

          <!-- 缩放：± 按钮 + 滑块，步进 5% -->
          <div class="setting-section">
            <span class="setting-label">缩放</span>
            <div class="zoom-control">
              <div class="zoom-row">
                <button
                  class="zoom-btn"
                  type="button"
                  aria-label="缩小"
                  :disabled="settings.zoom <= ZOOM_MIN"
                  @click="adjustZoom(-ZOOM_STEP)"
                >
                  −
                </button>
                <input
                  class="zoom-slider"
                  type="range"
                  :min="ZOOM_MIN"
                  :max="ZOOM_MAX"
                  :step="ZOOM_STEP"
                  :value="settings.zoom"
                  aria-label="缩放比例"
                  @input="onZoomInput"
                />
                <button
                  class="zoom-btn"
                  type="button"
                  aria-label="放大"
                  :disabled="settings.zoom >= ZOOM_MAX"
                  @click="adjustZoom(ZOOM_STEP)"
                >
                  +
                </button>
              </div>
              <span class="zoom-value">{{ settings.zoom }}%</span>
            </div>
          </div>

          <!-- 画质（低频） -->
          <div class="setting-section">
            <span id="reader-quality-label" class="setting-label">画质</span>
            <div class="segmented" role="radiogroup" aria-labelledby="reader-quality-label">
              <label
                v-for="option in qualityOptions"
                :key="option.value"
                class="segment"
                :class="{ active: settings.qualityMode === option.value }"
              >
                <input
                  class="visually-hidden"
                  type="radio"
                  name="reader-quality"
                  :value="option.value"
                  :checked="settings.qualityMode === option.value"
                  @change="settings.setQualityMode(option.value)"
                />
                {{ option.label }}
              </label>
            </div>
          </div>

          <!-- 高级 -->
          <div class="advanced-divider" role="separator">高级</div>

          <label class="toggle-row">
            <input
              class="visually-hidden"
              type="checkbox"
              :checked="settings.enablePreload"
              @change="settings.togglePreload()"
            />
            <span class="toggle-label">预加载</span>
            <span class="toggle" :class="{ on: settings.enablePreload }" aria-hidden="true">
              <span class="toggle-thumb" />
            </span>
          </label>

          <label class="toggle-row">
            <input
              class="visually-hidden"
              type="checkbox"
              :checked="settings.enableProgressiveImage"
              @change="settings.toggleProgressiveImage()"
            />
            <span class="toggle-label">渐进加载</span>
            <span class="toggle" :class="{ on: settings.enableProgressiveImage }" aria-hidden="true">
              <span class="toggle-thumb" />
            </span>
          </label>
        </div>
      </section>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { useReaderSettingsStore, ZOOM_LEVELS } from '@/stores/reader-settings-store'
import type {
  FitMode,
  QualityMode,
  ReadingDirection,
} from '@/stores/reader-settings-store'

// 本组件是唯一允许接触 reader-settings-store 的移动端阅读组件：
// 所有选项直接写 store、即时生效，无「保存」按钮。
interface Props {
  /** 抽屉可见性，由父级（ReaderPage）控制 */
  visible: boolean
}

defineProps<Props>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const settings = useReaderSettingsStore()

/** 移动端只暴露 纵向/横向（ltr/rtl 仅桌面端可用） */
const directionOptions: ReadonlyArray<{ value: ReadingDirection; label: string }> = [
  { value: 'vertical', label: '纵向' },
  { value: 'horizontal', label: '横向' },
]

const fitOptions: ReadonlyArray<{ value: FitMode; label: string }> = [
  { value: 'AUTO', label: '自动' },
  { value: 'WIDTH', label: '适宽' },
  { value: 'HEIGHT', label: '适高' },
  { value: 'ORIGINAL', label: '原始' },
]

const qualityOptions: ReadonlyArray<{ value: QualityMode; label: string }> = [
  { value: 'AUTO', label: '自动' },
  { value: 'HQ_ONLY', label: '原图' },
  { value: 'LQ_ONLY', label: '省流' },
]

// 缩放范围取自 store 的 ZOOM_LEVELS（50–200）。
// 注意：store.setZoom 会吸附到预设档位，移动端滑块要求 5% 步进，
// 因此这里直接写 settings.zoom（下一次任意 setter 调用时随整体状态持久化）。
const ZOOM_MIN = Math.min(...ZOOM_LEVELS)
const ZOOM_MAX = Math.max(...ZOOM_LEVELS)
const ZOOM_STEP = 5

/** 滑块输入：直接写入缩放值（步进 5%） */
function onZoomInput(event: Event) {
  const target = event.target as HTMLInputElement
  settings.zoom = Number(target.value)
}

/** ± 按钮：步进 5%，并夹在 [ZOOM_MIN, ZOOM_MAX] 内 */
function adjustZoom(delta: number) {
  settings.zoom = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, settings.zoom + delta))
}
</script>

<style scoped>
/* 半透明遮罩：点击关闭 */
.reader-settings-drawer {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: color-mix(in srgb, var(--bg-primary) 60%, transparent);
}

/* 底部抽屉面板：约 60vh */
.drawer-panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  max-height: 60vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-secondary);
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  box-shadow: var(--shadow-lg);
  padding-bottom: env(safe-area-inset-bottom);
}

/* 滑入/滑出过渡：遮罩淡入 + 面板上滑 */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity var(--transition-normal);
}

.drawer-enter-active .drawer-panel,
.drawer-leave-active .drawer-panel {
  transition: transform var(--transition-normal);
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}

.drawer-enter-from .drawer-panel,
.drawer-leave-to .drawer-panel {
  transform: translateY(100%);
}

/* 标题栏 */
.drawer-header {
  display: flex;
  align-items: center;
  height: 56px;
  flex-shrink: 0;
  padding: 0 var(--space-xs);
  border-bottom: 1px solid var(--border);
}

/* 左侧占位与右侧关闭按钮等宽，保证标题光学居中 */
.drawer-header-spacer {
  width: 48px;
  flex-shrink: 0;
}

.drawer-title {
  flex: 1;
  text-align: center;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.drawer-close {
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
  color: var(--text-secondary);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.drawer-close:active {
  background: var(--bg-surface);
  color: var(--text-primary);
}

/* 设置列表：可滚动 */
.drawer-body {
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0 var(--space-base) var(--space-lg);
}

/* 单个设置区块：标签左 + 控件右 */
.setting-section {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-sm) 0;
  border-bottom: 1px solid var(--border);
}

.setting-label {
  width: 64px;
  flex-shrink: 0;
  font-size: 14px;
  color: var(--text-secondary);
}

/* 分段单选组：2 列网格（4 项时呈 2×2，与线框图一致） */
.segmented {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-sm);
}

/* 单个分段选项：触控目标 ≥ 48px */
.segment {
  min-height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--text-secondary);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: border-color var(--transition-fast), color var(--transition-fast),
    background var(--transition-fast);
}

.segment.active {
  background: var(--accent-bg);
  border-color: var(--accent);
  color: var(--text-primary);
}

.segment:focus-within {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}

/* 缩放控件 */
.zoom-control {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.zoom-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.zoom-btn {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.zoom-btn:active:not(:disabled) {
  background: var(--accent-bg);
  border-color: var(--accent);
}

.zoom-btn:disabled {
  opacity: 0.35;
  cursor: default;
}

/* 原生滑块：轨道 + 圆形滑钮（触控高度 48px） */
.zoom-slider {
  flex: 1;
  min-width: 0;
  height: 48px;
  margin: 0;
  -webkit-appearance: none;
  appearance: none;
  background: transparent;
  cursor: pointer;
}

.zoom-slider::-webkit-slider-runnable-track {
  height: 4px;
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
}

.zoom-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 22px;
  height: 22px;
  margin-top: -9px;
  border-radius: 50%;
  border: none;
  background: var(--accent);
}

.zoom-slider::-moz-range-track {
  height: 4px;
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
}

.zoom-slider::-moz-range-thumb {
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 50%;
  background: var(--accent);
}

.zoom-value {
  font-size: 13px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

/* 高级分割标题 */
.advanced-divider {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md) 0 var(--space-xs);
  font-size: 12px;
  color: var(--text-muted);
}

.advanced-divider::before,
.advanced-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border);
}

/* 开关行：整行可点，触控目标 ≥ 48px */
.toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.toggle-row:focus-within .toggle {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}

.toggle-label {
  font-size: 14px;
  color: var(--text-primary);
}

/* 自定义开关 */
.toggle {
  position: relative;
  width: 48px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.toggle.on {
  background: var(--accent);
  border-color: var(--accent);
}

.toggle-thumb {
  position: absolute;
  top: 50%;
  left: 3px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--text-primary);
  transform: translateY(-50%);
  transition: transform var(--transition-fast);
}

.toggle.on .toggle-thumb {
  transform: translate(20px, -50%);
}

/* 无障碍隐藏（保留原生控件语义与焦点） */
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

@media (prefers-reduced-motion: reduce) {
  .drawer-enter-active,
  .drawer-leave-active,
  .drawer-enter-active .drawer-panel,
  .drawer-leave-active .drawer-panel,
  .segment,
  .toggle,
  .toggle-thumb {
    transition: none;
  }
}
</style>
