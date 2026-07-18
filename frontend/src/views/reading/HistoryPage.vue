<template>
  <div class="history-page">
    <header class="page-header">
      <div class="header-left">
        <h1 class="page-title">阅读中心</h1>
        <p v-if="recentCount > 0" class="page-subtitle">
          最近阅读 {{ recentCount }} 部漫画
        </p>
      </div>
      <div class="header-actions">
        <button class="ghost-btn" @click="store.refresh">刷新</button>
        <button class="primary-btn" @click="router.push('/library')">去漫画库</button>
      </div>
    </header>

    <!-- 加载 -->
    <div v-if="store.loading && store.list.length === 0" class="state loading">
      <div class="spinner" />
      <span>加载中...</span>
    </div>

    <!-- 错误 -->
    <div v-else-if="store.error" class="state error">
      <el-icon :size="32"><WarningFilled /></el-icon>
      <span>{{ store.error }}</span>
      <button class="ghost-btn" @click="store.refresh">重试</button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="store.list.length === 0" class="state empty">
      <el-icon :size="56"><PictureFilled /></el-icon>
      <h2 class="empty-title">还没有阅读记录</h2>
      <p class="empty-desc">阅读任意漫画后，这里会显示你的最近进度</p>
      <button class="primary-btn" @click="router.push('/library')">开始阅读</button>
    </div>

    <!-- 列表（虚拟滚动：500+ 条记录仅渲染可视区行） -->
    <RecycleScroller
      v-else
      class="history-scroller"
      :items="store.list"
      :item-size="72"
      key-field="comicId"
      :buffer="200"
    >
      <template #default="{ item }">
        <div class="history-item">
          <ComicPoster
            :id="item.comicId"
            :cover-url="item.coverUrl"
            :title="item.comicTitle || `漫画 #${item.comicId}`"
            :subtitle="subtitleFor(item)"
            :progress="item.progressPercent"
            size="md"
            @click="continueRead(item)"
            @continue="continueRead(item)"
            @detail="goDetail(item.comicId)"
          />
        </div>
      </template>
    </RecycleScroller>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { RecycleScroller } from 'vue-virtual-scroller'
import { PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import { useHistoryStore } from '@/stores/history-store'
import type { HistoryVO } from '@/types'
import ComicPoster from '@/components/reading/comic/ComicPoster.vue'

const router = useRouter()
const store = useHistoryStore()

const recentCount = computed(() => store.list.length)

function subtitleFor(item: HistoryVO): string {
  return `第 ${item.chapterNo} 话 · ${item.pageNumber} / ${item.totalPages || '?'} 页 · ${item.progressPercent}%`
}

function continueRead(item: HistoryVO) {
  router.push(`/reader/${item.chapterId}?page=${item.pageNumber}`)
}

function goDetail(comicId: number) {
  router.push(`/comic/${comicId}`)
}

onMounted(() => {
  store.fetchList()
})
</script>

<style scoped>
.history-page {
  height: 100vh;
  max-width: var(--page-width);
  margin: 0 auto;
  padding: var(--space-xl) var(--page-padding) var(--space-lg);
  background: var(--bg-primary);
  color: var(--text-secondary);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--space-2xl);
  gap: var(--space-base);
  flex-wrap: wrap;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.page-subtitle {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: var(--space-sm);
}

/* 虚拟列表容器：必须有确定高度，RecycleScroller 才能计算可视区 */
.history-scroller {
  flex: 1;
  min-height: 0;
}

/* 单行：72px 固定高，与 :item-size 一致 */
.history-item {
  --history-thumb-height: 60px;
  --history-thumb-width: 40px; /* 2:3 封面比例 */
  display: flex;
  align-items: center;
  height: 72px;
  box-sizing: border-box;
  border-bottom: 1px solid var(--border);
}

/* 将 ComicPoster 从竖版卡片适配为 72px 横向行，组件本身不改 */
.history-item :deep(.comic-poster) {
  flex-direction: row;
  align-items: center;
  gap: var(--space-base);
  width: 100%;
  min-width: 0;
}

.history-item :deep(.comic-poster.is-hoverable:hover) {
  transform: none;
}

.history-item :deep(.poster-frame) {
  width: var(--history-thumb-width);
  height: var(--history-thumb-height);
  aspect-ratio: auto;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
}

.history-item :deep(.poster-info) {
  margin-top: 0;
  flex: 1;
  min-width: 0;
}

/* 72px 行内空间不足以容纳悬浮按钮，点击整行即继续阅读 */
.history-item :deep(.poster-overlay) {
  display: none;
}

/* States */
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-base);
  padding: var(--space-3xl) 0;
  text-align: center;
}

.state.loading {
  color: var(--text-secondary);
}

.state.error {
  color: var(--danger);
  background: var(--bg-surface);
  border-radius: var(--card-radius);
  padding: var(--space-xl);
}

.state.empty {
  color: var(--text-muted);
}

.empty-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.empty-desc {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0;
}

.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--border-strong);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Buttons */
.primary-btn {
  padding: 8px 16px;
  background: var(--accent);
  color: var(--text-primary);
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.primary-btn:hover {
  background: var(--accent-hover);
}

.ghost-btn {
  padding: 8px 16px;
  background: transparent;
  color: var(--text-primary);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.ghost-btn:hover {
  background: var(--bg-surface);
  border-color: var(--text-muted);
}

@media (max-width: 640px) {
  .history-page {
    padding: var(--space-lg) var(--space-base) var(--space-base);
  }

  .page-title {
    font-size: 22px;
  }
}
</style>
