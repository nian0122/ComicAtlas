<template>
  <!-- 标题与操作分组，长标题不挤占触控按钮。 -->
  <FloatingPanel as="header" class="reader-toolbar-mobile">
    <div class="toolbar-heading">
      <AppButton variant="ghost" class="toolbar-btn" icon-only aria-label="返回" @click="emit('back')">
        <el-icon :size="22"><ArrowLeft /></el-icon>
      </AppButton>
      <span class="toolbar-title" :title="title">{{ title }}</span>
      <AppButton variant="ghost" class="toolbar-btn" icon-only aria-label="阅读设置" @click="emit('openSettings')">
        <el-icon :size="22"><Setting /></el-icon>
      </AppButton>
    </div>
    <div class="toolbar-actions">
      <MediaReactionButtons :reaction="reaction" @toggle="emit('toggleReaction', $event)" />
      <div class="toolbar-view-actions">
        <FullscreenButton
          class="toolbar-btn"
          :active="isFullscreen"
          :pending="fullscreenPending"
          @toggle="emit('toggleFullscreen')"
        />
        <AppButton variant="ghost" class="immersive-btn" aria-label="短视频阅读" @click="emit('openImmersive')">
          <el-icon :size="18"><VideoPlay /></el-icon><span>沉浸阅读</span>
        </AppButton>
      </div>
    </div>
  </FloatingPanel>
</template>

<script setup lang="ts">
import { AppButton, FullscreenButton } from '@/shared/ui/button'
import { FloatingPanel } from '@/shared/ui/floating-panel'
import { ArrowLeft, Setting, VideoPlay } from '@element-plus/icons-vue'
import { MediaReactionButtons } from '@/entities/media'
// 哑组件：props 进、emits 出，不接触任何 store / composable。
// 显示与隐藏由父级（ReaderPage）通过 v-if 控制。
interface Props {
  /** 漫画名（移动端不展示长章节标题） */
  title: string
  reaction: 'NONE' | 'LIKE' | 'DISLIKE'
  isFullscreen?: boolean
  fullscreenPending?: boolean
}

defineProps<Props>()

const emit = defineEmits<{
  (e: 'back'): void
  (e: 'openSettings'): void
  (e: 'openImmersive'): void
  (e: 'toggleFullscreen'): void
  (e: 'toggleReaction', reaction: 'NONE' | 'LIKE' | 'DISLIKE'): void
}>()
</script>

<style scoped>
.reader-toolbar-mobile {
  position: fixed;
  top: calc(var(--space-3) + env(safe-area-inset-top));
  left: var(--space-3);
  right: var(--space-3);
  z-index: var(--z-nav);
  display: grid;
  gap: var(--space-1);
  max-width: var(--floating-content-max);
  margin: 0 auto;
  animation: toolbar-fade-in 160ms ease both;
}
.toolbar-heading,
.toolbar-actions,
.toolbar-view-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}
.toolbar-actions {
  justify-content: space-between;
  padding-top: var(--space-1);
  border-top: 1px solid var(--border);
}
.toolbar-btn {
  flex-shrink: 0;
  width: var(--control-min-size);
  height: var(--control-min-size);
  border: 0;
  color: var(--text-secondary);
  border-radius: var(--radius-sm);
}
.toolbar-title {
  flex: 1;
  min-width: 0;
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-weight: 650;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.toolbar-actions :deep(.reaction-buttons .app-button) {
  width: var(--control-min-size);
  height: var(--control-min-size);
  border-radius: var(--radius-sm);
  background: transparent;
  border-color: transparent;
}
.toolbar-actions :deep(.reaction-buttons .app-button.active) {
  background: var(--accent-bg);
  border-color: var(--accent-border);
}
.immersive-btn {
  min-height: var(--control-min-size);
  padding: 0 var(--space-2);
  border: 0;
  color: var(--text-secondary);
}
.immersive-btn :deep(span) {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}
@keyframes toolbar-fade-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@media (prefers-reduced-motion: reduce) {
  .reader-toolbar-mobile {
    animation: none;
  }
}
</style>
