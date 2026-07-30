<template>
  <div class="history-page">
    <header class="page-header">
      <div class="header-left">
        <p class="page-eyebrow">LEDGER / HISTORY</p>
        <h1 class="page-title">阅读历史</h1>
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
      :items="historyItems"
      :item-size="historyItemSize"
      key-field="key"
      :buffer="200"
    >
      <template #default="{ item }">
        <div v-if="item.kind === 'end'" class="history-end">
          <el-icon :size="34"><Clock /></el-icon>
          <span>END OF HISTORY</span>
        </div>
        <article v-else class="history-item">
          <button type="button" class="history-thumb" @click="continueRead(item.value)">
            <img :src="item.value.coverUrl" :alt="item.value.comicTitle || `漫画 #${item.value.comicId}`">
          </button>
          <button type="button" class="history-copy" @click="continueRead(item.value)">
            <span class="history-title">{{ item.value.comicTitle || `漫画 #${item.value.comicId}` }}</span>
            <span class="history-meta">{{ subtitleFor(item.value) }}</span>
            <span class="history-progress" aria-hidden="true">
              <span :style="{ width: `${item.value.progressPercent}%` }" />
            </span>
          </button>
          <button type="button" class="history-play" aria-label="继续阅读" @click="continueRead(item.value)">
            <el-icon :size="20"><VideoPlay /></el-icon>
          </button>
        </article>
      </template>
    </RecycleScroller>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { RecycleScroller } from 'vue-virtual-scroller'
import { Clock, PictureFilled, VideoPlay, WarningFilled } from '@element-plus/icons-vue'
import { useHistoryStore } from '@/stores/history-store'
import type { HistoryVO } from '@/types'
import { useInteractionMode } from '@/views/reading/reader/composables/useInteractionMode'

const router = useRouter()
const store = useHistoryStore()
const { mode } = useInteractionMode()

const recentCount = computed(() => store.list.length)
const historyItemSize = computed(() =>
  mode.value === 'mobile' ? 208 : 88
)

type HistoryScrollerItem =
  | { kind: 'history'; key: string; value: HistoryVO }
  | { kind: 'end'; key: 'history-end' }

const historyItems = computed<HistoryScrollerItem[]>(() => [
  ...store.list.map((value) => ({
    kind: 'history' as const,
    key: `history-${value.comicId}`,
    value,
  })),
  { kind: 'end' as const, key: 'history-end' },
])

function subtitleFor(item: HistoryVO): string {
  return `第 ${item.chapterNo} 话 · ${item.pageNumber} / ${item.totalPages || '?'} 页 · ${item.progressPercent}%`
}

function continueRead(item: HistoryVO) {
  router.push(`/reader/${item.chapterId}?page=${item.pageNumber}`)
}

onMounted(() => {
  store.fetchList()
})
</script>

<style scoped>
.history-page {
  height: calc(100dvh - var(--nav-height) - var(--space-10));
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--space-8) 0 var(--space-6);
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

.page-eyebrow {
  margin-bottom: var(--space-1);
  color: var(--accent);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.page-title {
  font-size: var(--text-page);
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
  border-block: 1px solid var(--border);
}

/* 单行：88px 固定高，与 :item-size 一致 */
.history-item {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  height: 88px;
  padding-inline: var(--space-3);
  box-sizing: border-box;
  border-bottom: 1px solid var(--border);
  transition:
    background-color var(--transition-fast),
    box-shadow var(--transition-fast);
}

.history-item:hover,
.history-item:focus-within {
  background: var(--bg-secondary);
  box-shadow: inset 2px 0 var(--accent);
}

.history-thumb {
  width: 48px;
  height: 72px;
  flex: 0 0 auto;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
}

.history-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.history-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-1);
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
}

.history-title {
  width: 100%;
  overflow: hidden;
  color: var(--text-primary);
  font-size: var(--text-md);
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-meta {
  width: 100%;
  overflow: hidden;
  color: var(--text-muted);
  font-size: var(--text-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-progress {
  width: min(260px, 100%);
  height: 3px;
  margin-top: var(--space-1);
  overflow: hidden;
  background: var(--color-progress-track);
}

.history-progress span {
  display: block;
  height: 100%;
  background: var(--accent);
}

.history-play {
  display: inline-grid;
  place-items: center;
  width: 42px;
  height: 42px;
  flex: 0 0 auto;
  padding: 0;
  border: 1px solid var(--color-border-faint);
  border-radius: 50%;
  background: var(--color-overlay-soft);
  color: var(--text-primary);
}

.history-end {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  height: 88px;
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-weight: 800;
  letter-spacing: 0.24em;
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
    height: calc(
      100dvh - var(--mobile-nav-height) - var(--mobile-tabbar-height) - var(--space-8)
    );
    padding: calc(var(--space-12) + var(--space-2)) 0 var(--space-4);
  }

  .page-title {
    font-size: var(--text-page);
  }

  .page-header {
    align-items: flex-end;
    margin-bottom: var(--space-12);
  }

  .page-eyebrow {
    color: var(--text-secondary);
  }

  .page-title {
    color: var(--accent);
  }

  .page-subtitle,
  .primary-btn {
    display: none;
  }

  .history-item {
    gap: var(--space-3);
    height: var(--mobile-history-row-height);
    padding-inline: 0;
  }

  .history-thumb {
    width: var(--mobile-history-thumb-width);
    height: auto;
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-md);
  }

  .history-title {
    display: -webkit-box;
    overflow: hidden;
    font-size: 15px;
    line-height: 1.35;
    white-space: normal;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  .history-meta {
    font-size: 11px;
  }

  .history-play {
    width: 38px;
    height: 38px;
    border-color: var(--accent);
    background: transparent;
    color: var(--accent);
  }

  .history-end {
    flex-direction: column;
    height: var(--mobile-history-row-height);
  }
}
</style>
