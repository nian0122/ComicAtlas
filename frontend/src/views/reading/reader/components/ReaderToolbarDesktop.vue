<template>
  <header
    class="reader-toolbar"
    :class="{ 'toolbar-hidden': !settings.showToolbar }"
  >
    <div class="toolbar-left">
      <button class="tool-btn" @click="emit('back')">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <span class="toolbar-title">{{ title }}</span>
    </div>

    <div class="toolbar-center">
      <el-popover
        v-model:visible="jumpVisible"
        placement="bottom"
        :width="220"
        trigger="click"
      >
        <template #reference>
          <button class="tool-btn page-indicator" title="点击跳转页码">
            {{ currentPage }} / {{ totalPages }}
          </button>
        </template>
        <div class="jump-panel">
          <el-input-number
            v-model="jumpPage"
            :min="1"
            :max="Math.max(1, totalPages)"
            size="small"
            class="jump-input"
            @keyup.enter="confirmJump"
          />
          <el-button type="primary" size="small" @click="confirmJump">跳转</el-button>
        </div>
      </el-popover>
    </div>

    <div class="toolbar-right">
      <!-- Chapter nav -->
      <button
        v-if="prevChapterId"
        class="tool-btn chapter-btn"
        @click="emit('prevChapter')"
      >
        上一章
      </button>
      <button
        v-if="nextChapterId"
        class="tool-btn chapter-btn primary"
        @click="emit('nextChapter')"
      >
        下一章
      </button>

      <el-popover
        v-model:visible="settingsVisible"
        placement="bottom-end"
        :width="280"
        trigger="click"
      >
        <template #reference>
          <button class="tool-btn" aria-label="阅读设置" title="阅读设置">
            <el-icon :size="18"><Setting /></el-icon>
          </button>
        </template>
        <div class="desktop-settings-panel">
          <div class="settings-panel-title">阅读设置</div>
          <label class="settings-field">
            <span>画质</span>
            <el-select v-model="settings.qualityMode" size="small" @change="settings.setQualityMode">
              <el-option label="省流" value="LQ_ONLY" />
              <el-option label="智能" value="AUTO" />
              <el-option label="原图" value="HQ_ONLY" />
            </el-select>
          </label>
          <label class="settings-field">
            <span>适配模式</span>
            <el-select v-model="settings.fitMode" size="small" @change="settings.setFitMode">
              <el-option label="自动" value="AUTO" />
              <el-option label="适配宽" value="WIDTH" />
              <el-option label="适配高" value="HEIGHT" />
              <el-option label="原始" value="ORIGINAL" />
            </el-select>
          </label>
          <label class="settings-field">
            <span>阅读方向</span>
            <el-select v-model="settings.readingDirection" size="small" @change="settings.setReadingDirection">
              <el-option label="纵向滚动" value="vertical" />
              <el-option label="横向翻页" value="horizontal" />
            </el-select>
          </label>
          <div class="settings-field settings-zoom-field">
            <span>缩放 {{ settings.zoom }}%</span>
            <div class="zoom-group">
              <button class="tool-btn zoom-btn" @click="settings.zoomOut">−</button>
              <span class="zoom-value">{{ settings.zoom }}%</span>
              <button class="tool-btn zoom-btn" @click="settings.zoomIn">＋</button>
            </div>
          </div>
          <div class="settings-panel-actions">
            <button class="panel-action" @click="settings.togglePreload()">
              {{ settings.enablePreload ? '关闭预加载' : '开启预加载' }}
            </button>
            <button class="panel-action" @click="settings.resetZoom()">重置缩放</button>
            <button class="panel-action" @click="settings.toggleToolbar(); settingsVisible = false">隐藏工具栏</button>
          </div>
        </div>
      </el-popover>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ArrowLeft, Setting } from '@element-plus/icons-vue'
import {
  ElSelect,
  ElOption,
  ElPopover,
  ElInputNumber,
  ElButton,
} from 'element-plus'
import { useReaderSettingsStore } from '@/stores/reader-settings-store'

interface Props {
  title: string
  currentPage: number
  totalPages: number
  prevChapterId: number | null
  nextChapterId: number | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'back'): void
  (e: 'prevChapter'): void
  (e: 'nextChapter'): void
  (e: 'jumpToPage', page: number): void
}>()

const settings = useReaderSettingsStore()

const jumpVisible = ref(false)
const jumpPage = ref(1)
const settingsVisible = ref(false)

watch(jumpVisible, (visible) => {
  if (visible) jumpPage.value = props.currentPage
})

function confirmJump() {
  jumpVisible.value = false
  emit('jumpToPage', jumpPage.value)
}

</script>

<style scoped>
.reader-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  height: 56px;
  gap: var(--space-lg);
  padding: 0 clamp(16px, 2vw, 32px);
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  z-index: 10;
  transition: transform 200ms ease, opacity 200ms ease;
}

.reader-toolbar.toolbar-hidden {
  transform: translateY(-100%);
  opacity: 0;
  pointer-events: none;
}

.toolbar-left,
.toolbar-right,
.toolbar-center {
  display: flex;
  align-items: center;
  gap: var(--space-base);
}

.toolbar-left {
  min-width: 0;
}

.toolbar-center {
  justify-self: center;
}

.toolbar-right {
  justify-self: end;
  min-width: 0;
  gap: 8px;
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  max-width: min(42vw, 560px);
}

.page-indicator {
  min-width: 84px;
  padding-inline: 14px;
  border: 1px solid var(--border);
  background: var(--bg-surface);
  font-size: 13px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.jump-panel {
  display: flex;
  align-items: center;
  gap: 8px;
}

.jump-panel .jump-input {
  flex: 1;
  width: auto;
  min-width: 0;
}

.zoom-group {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: var(--bg-surface);
  border-radius: var(--radius-sm);
  padding: 0 4px;
}

.zoom-value {
  min-width: 44px;
  text-align: center;
  font-size: 13px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.zoom-btn {
  width: 28px;
  padding: 0;
}

.desktop-settings-panel {
  display: grid;
  gap: var(--space-3);
  color: var(--text-primary);
}

.settings-panel-title {
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--border);
  font-size: 14px;
  font-weight: 700;
}

.settings-field {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: var(--space-3);
  color: var(--text-secondary);
  font-size: 12px;
}

.settings-field :deep(.el-select) {
  width: 100%;
}

.settings-zoom-field {
  grid-template-columns: 72px minmax(0, 1fr);
}

.settings-panel-actions {
  display: flex;
  gap: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px solid var(--border);
}

.panel-action {
  flex: 1;
  min-height: 32px;
  padding: 0 var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  color: var(--text-secondary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.panel-action:hover {
  border-color: var(--accent);
  color: var(--text-primary);
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 14px;
  cursor: pointer;
  transition: background 150ms ease;
}

.tool-btn:hover {
  background: var(--bg-surface);
}

.chapter-btn.primary {
  background: var(--accent);
  color: var(--text-primary);
}

.chapter-btn.primary:hover {
  background: var(--accent-hover);
}

@media (max-width: 1024px) {
  .reader-toolbar {
    display: flex;
    flex-wrap: wrap;
    height: auto;
    padding: var(--space-sm) var(--space-base);
    gap: var(--space-sm);
  }

  .toolbar-left,
  .toolbar-right,
  .toolbar-center {
    flex: 1 1 100%;
    justify-content: center;
  }

  .toolbar-left,
  .toolbar-right,
  .toolbar-center {
    min-width: 0;
  }
}

@media (max-width: 1200px) and (min-width: 1025px) {
  .reader-toolbar {
    gap: 12px;
    padding-inline: 16px;
  }

  .toolbar-right {
    gap: 4px;
  }

  .toolbar-title {
    max-width: 260px;
  }

  .chapter-btn {
    padding-inline: 8px;
  }
}
</style>
