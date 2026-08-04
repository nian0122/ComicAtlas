<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { RecycleScroller } from 'vue-virtual-scroller'
import { useMediaStore } from '@/stores/management/media'
import { useMediaUploadQueueStore } from '@/stores/management/mediaUploadQueue'
import { chapterOptionLabel } from '@/types/management/media'
import type { ManagementMediaItem } from '@/types/management/media'
import MediaGridItem from './MediaGridItem.vue'
import MediaUploadPanel from './MediaUploadPanel.vue'
import MediaPreviewDialog from './MediaPreviewDialog.vue'

/**
 * 媒体管理控制台（Task 19）：章节选择 + 虚拟滚动媒体网格（10k+ 混排）
 * + 分块上传（拖入/选择、暂停续传、取消、逐文件重试、替换保留 ID）+ 键盘重排 + 回收/恢复。
 */

const TILE_WIDTH = 176
const TILE_HEIGHT = 200

const route = useRoute()
const router = useRouter()

const mediaStore = useMediaStore()
const queue = useMediaUploadQueueStore()

const comicId = Number(route.params.id)
const previewItem = ref<ManagementMediaItem | null>(null)

// vue-virtual-scroller 2.x 使用 gridItems（列数）而非旧版 grid-mode：
// 按容器实际宽度计算可容纳的列数，避免横向溢出。
const scrollerWrapRef = ref<HTMLElement | null>(null)
const gridColumns = ref(1)
let resizeObserver: ResizeObserver | null = null

function updateGridColumns(): void {
  const el = scrollerWrapRef.value
  if (el) {
    gridColumns.value = Math.max(1, Math.floor(el.clientWidth / TILE_WIDTH))
  }
}

/**
 * 滚动容器是条件渲染的（媒体列表加载后才出现），因此用函数 ref 在挂载时
 * 建立 ResizeObserver，避免 onMounted 时容器尚不存在导致列数恒为 1。
 */
function onScrollerWrapMount(el: unknown): void {
  scrollerWrapRef.value = (el as HTMLElement | null) ?? null
  if (el) {
    if (typeof ResizeObserver !== 'undefined' && !resizeObserver) {
      updateGridColumns()
      resizeObserver = new ResizeObserver(updateGridColumns)
      resizeObserver.observe(el as HTMLElement)
    }
  } else if (resizeObserver) {
    // 容器卸载（如章节加载失败后列表清空）时断开，重挂载时重建
    resizeObserver.disconnect()
    resizeObserver = null
  }
}

function goBack(): void {
  router.push('/manage/comics')
}

async function onChapterChange(chapterId: number): Promise<void> {
  queue.setTarget(comicId, chapterId)
  await mediaStore.selectChapter(chapterId)
}

async function saveOrder(): Promise<void> {
  try {
    await mediaStore.saveOrder()
  } catch {
    ElMessage.error('保存排序失败，请刷新后重试')
  }
}

async function onTrash(item: ManagementMediaItem): Promise<void> {
  try {
    await mediaStore.trashItem(item.id)
    ElMessage.success(`已回收媒体 #${item.id}`)
  } catch {
    ElMessage.error('回收媒体失败')
  }
}

async function onRestore(item: ManagementMediaItem): Promise<void> {
  try {
    await mediaStore.restoreItem(item.id)
    ElMessage.success(`已恢复媒体 #${item.id}`)
  } catch {
    ElMessage.error('恢复媒体失败')
  }
}

function onReplace(item: ManagementMediaItem): void {
  queue.setReplaceTarget(item.id)
}

// 队列全部完成后提交会话并刷新章节媒体列表（替换场景保留原 ID）。
// 完成后不清空队列：已完成条目保留展示，直到下一次上传开始时清理。
watch(
  () => queue.canComplete,
  async (canComplete) => {
    if (!canComplete) return
    try {
      await queue.completeUpload()
      const chapterId = mediaStore.currentChapterId
      if (chapterId !== null) {
        await mediaStore.loadChapter(chapterId)
      }
    } catch {
      ElMessage.error('上传完成提交失败，请检查后重试')
    }
  },
)

onMounted(async () => {
  try {
    await mediaStore.loadComic(comicId)
    const firstChapter = mediaStore.currentChapterId ?? mediaStore.chapters[0]?.chapterId ?? 0
    if (firstChapter > 0) {
      queue.setTarget(comicId, firstChapter)
    }
  } catch {
    ElMessage.error('加载漫画失败')
    router.push('/manage/comics')
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<template>
  <div class="media-page" data-testid="media-page">
    <header class="media-header">
      <button class="back-btn" type="button" @click="goBack">
        <el-icon :size="16"><ArrowLeft /></el-icon>
        <span>返回</span>
      </button>
      <div class="header-info">
        <h1 class="page-title">媒体管理</h1>
        <span class="comic-title">{{ mediaStore.comicTitle }}</span>
        <span class="comic-id-badge">ID: {{ comicId }}</span>
      </div>
    </header>

    <div class="media-toolbar">
      <label class="chapter-label" for="media-chapter-select">章节</label>
      <el-select
        id="media-chapter-select"
        class="chapter-select"
        :model-value="mediaStore.currentChapterId"
        placeholder="选择章节"
        data-testid="chapter-selector"
        @change="onChapterChange"
      >
        <el-option
          v-for="chapter in mediaStore.chapters"
          :key="chapter.chapterId"
          :label="chapterOptionLabel(chapter)"
          :value="chapter.chapterId"
        />
      </el-select>
      <span class="media-counter" data-testid="media-counter">
        共 {{ mediaStore.total }} 项
      </span>
      <span v-if="mediaStore.selectedCount > 0" class="selected-counter">
        已选 {{ mediaStore.selectedCount }} 项
      </span>
    </div>

    <MediaUploadPanel />

    <div v-if="mediaStore.loading && mediaStore.items.length === 0" class="media-state" data-testid="media-loading">
      <div class="spinner" aria-hidden="true" />
      <span>加载媒体中…</span>
    </div>

    <div v-else-if="mediaStore.error" class="media-state media-state--error" data-testid="media-error">
      <span>{{ mediaStore.error }}</span>
      <button class="retry-btn" type="button" @click="mediaStore.loadChapter(mediaStore.currentChapterId ?? 0)">
        重试
      </button>
    </div>

    <div v-else-if="mediaStore.items.length === 0" class="media-state" data-testid="media-empty">
      <span>暂无媒体，可拖入或选择文件上传</span>
    </div>

    <template v-else>
      <div v-if="mediaStore.selectedCount > 0" class="batch-bar" data-testid="batch-bar">
        <span class="batch-bar-info">
          已选 <strong>{{ mediaStore.selectedCount }}</strong> 项
        </span>
        <button class="batch-link" type="button" @click="mediaStore.selectAll()">
          {{ mediaStore.allSelected ? '取消全选' : '全选' }}
        </button>
        <button class="batch-link" type="button" @click="mediaStore.selectedIds = []">
          清除选择
        </button>
      </div>

      <div v-if="mediaStore.orderDirty" class="reorder-bar" data-testid="reorder-bar">
        <span class="reorder-hint">排序已修改</span>
        <button
          class="reorder-save-btn"
          type="button"
          :disabled="mediaStore.savingOrder"
          data-testid="reorder-save"
          @click="saveOrder"
        >
          {{ mediaStore.savingOrder ? '保存中…' : '保存排序' }}
        </button>
      </div>
      <div v-else-if="mediaStore.savedOrder" class="reorder-saved" data-testid="reorder-saved">
        已保存排序
      </div>

      <div class="media-scroller-wrap" :ref="onScrollerWrapMount">
        <RecycleScroller
          class="media-scroller"
          data-testid="media-scroller"
          :items="[...mediaStore.items]"
          :item-size="TILE_HEIGHT"
          :item-secondary-size="TILE_WIDTH"
          :grid-items="gridColumns"
          key-field="id"
          v-slot="{ item }"
        >
          <MediaGridItem
            :item="item"
            :selected="mediaStore.isSelected(item.id)"
            @select="mediaStore.toggleSelect"
            @preview="previewItem = $event"
            @replace="onReplace"
            @trash="onTrash"
            @restore="onRestore"
            @move="mediaStore.moveItem"
          />
        </RecycleScroller>
      </div>
    </template>

    <MediaPreviewDialog :item="previewItem" @close="previewItem = null" />
  </div>
</template>

<style scoped>
.media-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  max-width: 1120px;
  margin: 0 auto;
  min-height: 0;
  min-width: 0;
}

.media-header {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 8px 14px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.back-btn:hover {
  background: var(--surface-highlight);
}

.header-info {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  min-width: 0;
}

.page-title {
  margin: 0;
  font-size: var(--text-page);
  font-weight: 700;
  color: var(--text-primary);
}

.comic-title {
  font-size: var(--text-sm);
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.comic-id-badge {
  padding: 2px 8px;
  border-radius: var(--radius-xs);
  background: var(--bg-primary);
  border: 1px solid var(--border);
  font-size: 10px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.media-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.chapter-label {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-secondary);
}

.chapter-select {
  width: 260px;
}

.media-counter {
  font-size: var(--text-sm);
  font-weight: 700;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.selected-counter {
  font-size: var(--text-sm);
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}

.media-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  min-height: 160px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-md);
  color: var(--text-muted);
  font-size: var(--text-sm);
}

.media-state--error {
  color: var(--danger);
  border-color: var(--danger);
  flex-direction: column;
  gap: var(--space-3);
}

.retry-btn {
  padding: 6px 14px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  color: var(--text-primary);
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--border-strong);
  border-right-color: transparent;
  border-radius: 50%;
  animation: media-spin 0.7s linear infinite;
}

@keyframes media-spin {
  to {
    transform: rotate(360deg);
  }
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--surface-highlight);
  border: 1px solid var(--border);
}

.batch-bar-info {
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.batch-bar-info strong {
  color: var(--accent);
}

.batch-link {
  padding: 0;
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.batch-link:hover {
  color: var(--text-primary);
}

.reorder-bar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  background: rgb(216 165 79 / 10%);
  border: 1px solid var(--warning);
}

.reorder-hint {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--warning);
}

.reorder-save-btn {
  margin-left: auto;
  padding: 6px 16px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: var(--color-on-brand);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
}

.reorder-save-btn:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}

.reorder-saved {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  background: rgb(102 197 139 / 10%);
  border: 1px solid var(--success);
  color: var(--success);
  font-size: var(--text-sm);
  font-weight: 600;
}

.media-scroller-wrap {
  position: relative;
  height: max(360px, calc(100vh - 520px));
  min-height: 0;
  min-width: 0;
}

.media-scroller {
  height: 100%;
  width: 100%;
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation-duration: 1.4s;
  }
}
</style>
