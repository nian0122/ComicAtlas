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
      <span class="page-indicator">{{ currentPage }} / {{ totalPages }}</span>
    </div>

    <div class="toolbar-right">
      <!-- Quality -->
      <el-select
        v-model="settings.qualityMode"
        size="small"
        class="toolbar-select"
        @change="settings.setQualityMode"
      >
        <el-option label="自动" value="AUTO" />
        <el-option label="原图" value="HQ_ONLY" />
        <el-option label="省流" value="LQ_ONLY" />
      </el-select>

      <!-- Fit -->
      <el-select
        v-model="settings.fitMode"
        size="small"
        class="toolbar-select"
        @change="settings.setFitMode"
      >
        <el-option label="自动" value="AUTO" />
        <el-option label="适配宽" value="WIDTH" />
        <el-option label="适配高" value="HEIGHT" />
        <el-option label="原始" value="ORIGINAL" />
      </el-select>

      <!-- Direction -->
      <el-select
        v-model="settings.readingDirection"
        size="small"
        class="toolbar-select"
        @change="settings.setReadingDirection"
      >
        <el-option label="纵向" value="vertical" />
        <el-option label="横向" value="horizontal" />
      </el-select>

      <!-- Zoom -->
      <div class="zoom-group">
        <button class="tool-btn zoom-btn" @click="settings.zoomOut">-</button>
        <span class="zoom-value">{{ settings.zoom }}%</span>
        <button class="tool-btn zoom-btn" @click="settings.zoomIn">+</button>
      </div>

      <!-- Settings -->
      <el-dropdown trigger="click" @command="onCommand">
        <button class="tool-btn">
          <el-icon :size="18"><Setting /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="toggleToolbar">
              {{ settings.showToolbar ? '隐藏工具栏' : '显示工具栏' }}
            </el-dropdown-item>
            <el-dropdown-item command="togglePreload">
              {{ settings.enablePreload ? '关闭预加载' : '开启预加载' }}
            </el-dropdown-item>
            <el-dropdown-item command="toggleProgressive">
              {{ settings.enableProgressiveImage ? '关闭渐进加载' : '开启渐进加载' }}
            </el-dropdown-item>
            <el-dropdown-item divided command="resetZoom">重置缩放</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

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
    </div>
  </header>
</template>

<script setup lang="ts">
import { ArrowLeft, Setting } from '@element-plus/icons-vue'
import {
  ElSelect,
  ElOption,
  ElDropdown,
  ElDropdownMenu,
  ElDropdownItem,
} from 'element-plus'
import { useReaderSettingsStore } from '@/stores/reader-settings-store'

interface Props {
  title: string
  currentPage: number
  totalPages: number
  prevChapterId: number | null
  nextChapterId: number | null
}

defineProps<Props>()
const emit = defineEmits<{
  (e: 'back'): void
  (e: 'prevChapter'): void
  (e: 'nextChapter'): void
}>()

const settings = useReaderSettingsStore()

function onCommand(command: string) {
  switch (command) {
    case 'toggleToolbar':
      settings.toggleToolbar()
      break
    case 'togglePreload':
      settings.togglePreload()
      break
    case 'toggleProgressive':
      settings.toggleProgressiveImage()
      break
    case 'resetZoom':
      settings.resetZoom()
      break
  }
}
</script>

<style scoped>
.reader-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 var(--space-lg);
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  z-index: 10;
  transition: height 200ms ease, padding 200ms ease, opacity 200ms ease;
}

.reader-toolbar.toolbar-hidden {
  height: 0;
  padding-top: 0;
  padding-bottom: 0;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
  border-bottom: none;
}

.toolbar-left,
.toolbar-right,
.toolbar-center {
  display: flex;
  align-items: center;
  gap: var(--space-base);
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-h);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}

.page-indicator {
  font-size: 13px;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

.toolbar-select {
  width: 90px;
}

:deep(.toolbar-select .el-input__wrapper) {
  background: var(--surface-elevated);
  box-shadow: 0 0 0 1px var(--border) inset;
}

:deep(.toolbar-select .el-input__inner) {
  color: var(--text-h);
}

.zoom-group {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: var(--surface-elevated);
  border-radius: var(--radius-sm);
  padding: 0 4px;
}

.zoom-value {
  min-width: 44px;
  text-align: center;
  font-size: 13px;
  color: var(--text-h);
  font-variant-numeric: tabular-nums;
}

.zoom-btn {
  width: 28px;
  padding: 0;
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
  color: var(--text-h);
  font-size: 14px;
  cursor: pointer;
  transition: background 150ms ease;
}

.tool-btn:hover {
  background: var(--surface-elevated);
}

.chapter-btn.primary {
  background: var(--accent);
  color: #fff;
}

.chapter-btn.primary:hover {
  background: var(--accent-hover);
}

@media (max-width: 768px) {
  .reader-toolbar {
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
}
</style>
