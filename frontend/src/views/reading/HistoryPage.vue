<template>
  <div class="history-page">
    <header class="page-header">
      <div class="header-left">
        <p class="page-eyebrow">LEDGER / HISTORY</p>
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
          <MaterialSymbolIcon name="history" class="history-end-icon" />
          <span class="history-end-label history-end-label--desktop">END OF HISTORY</span>
          <span class="history-end-label history-end-label--mobile">历史记录已加载完毕</span>
        </div>
        <article v-else class="history-item">
          <button type="button" class="history-thumb" @click="continueRead(item.value)">
            <img :src="item.value.coverUrl" :alt="item.value.comicTitle || `漫画 #${item.value.comicId}`">
            <span class="history-thumb-progress" aria-hidden="true">
              <span :style="{ width: `${progressFor(item.value)}%` }" />
            </span>
          </button>
          <button type="button" class="history-copy" @click="continueRead(item.value)">
            <span class="history-title">{{ item.value.comicTitle || `漫画 #${item.value.comicId}` }}</span>
            <span class="history-meta">{{ subtitleFor(item.value) }}</span>
            <span class="history-progress-row">
              <span class="history-progress" aria-hidden="true">
                <span :style="{ width: `${progressFor(item.value)}%` }" />
              </span>
              <span class="history-percent">{{ progressFor(item.value) }}%</span>
            </span>
          </button>
          <button type="button" class="history-play" aria-label="继续阅读" @click="continueRead(item.value)">
            <MaterialSymbolIcon name="play" class="history-play-icon" />
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
import { PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import MaterialSymbolIcon from '@/components/icons/MaterialSymbolIcon.vue'
import { BREAKPOINTS, useBreakpoint } from '@/composables/useBreakpoint'
import { useHistoryStore } from '@/stores/history-store'
import type { HistoryVO } from '@/types'

const router = useRouter()
const store = useHistoryStore()
const viewportWidth = useBreakpoint()

const recentCount = computed(() => store.list.length)
const historyItemSize = computed(() =>
  viewportWidth.value <= BREAKPOINTS.tablet ? 148 : 88
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
  return `第 ${item.chapterNo} 话 · ${item.pageNumber} / ${item.totalPages || '?'} 页`
}

function progressFor(item: HistoryVO): number {
  return Math.min(100, Math.max(0, Math.round(item.progressPercent)))
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
  flex: 1;
  height: 3px;
  overflow: hidden;
  background: var(--color-progress-track);
}

.history-progress-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: min(300px, 100%);
  margin-top: var(--space-1);
}

.history-progress span {
  display: block;
  height: 100%;
  background: var(--accent);
}

.history-percent {
  color: var(--text-muted);
  font-size: var(--text-micro);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.history-thumb-progress {
  display: none;
}

.history-end-label--mobile {
  display: none;
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

.history-play-icon {
  width: var(--mobile-history-play-icon-size);
  height: var(--mobile-history-play-icon-size);
}

.history-end-icon {
  width: var(--mobile-history-end-icon-size);
  height: var(--mobile-history-end-icon-size);
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

@media (max-width: 1024px) {
  .history-page {
    height: calc(
      100dvh - var(--mobile-nav-height) - var(--mobile-tabbar-height) - var(--space-8)
    );
    max-width: var(--mobile-history-content-max);
    padding: var(--space-8) 0 var(--space-4);
    overflow: visible;
  }

  .page-header {
    height: 1px;
    margin-bottom: var(--space-6);
    background: var(--color-border-faint);
  }

  .header-left,
  .header-actions {
    display: none;
  }

  .history-scroller {
    width: calc(100% + var(--space-4));
    transform: translateX(calc(var(--space-2) * -1));
    border-block: 0;
    scrollbar-width: none;
  }

  .history-scroller::-webkit-scrollbar {
    display: none;
  }

  .history-item {
    gap: var(--space-4);
    height: var(--mobile-history-card-height);
    margin-bottom: var(--space-6);
    padding: var(--space-4) var(--space-2);
    border: 1px solid var(--color-border-faint);
    border-radius: var(--mobile-history-card-radius);
    background: var(--color-overlay-faint);
  }

  .history-item:hover,
  .history-item:focus-within {
    background: var(--bg-secondary);
    box-shadow: none;
  }

  .history-thumb {
    width: var(--mobile-history-thumb-width);
    height: auto;
    aspect-ratio: 16 / 9;
    position: relative;
    border-radius: var(--radius-sm);
    box-shadow: var(--shadow-sm);
  }

  .history-thumb-progress {
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    display: block;
    height: var(--space-1);
    background: var(--color-progress-track);
  }

  .history-thumb-progress span {
    display: block;
    height: 100%;
    background: var(--accent);
  }

  .history-title {
    display: block;
    overflow: hidden;
    font-size: var(--text-md);
    line-height: 1.4;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .history-meta {
    color: var(--text-secondary);
    font-size: var(--text-xs);
  }

  .history-progress-row {
    width: 100%;
    margin-top: 0;
  }

  .history-play {
    position: relative;
    width: var(--mobile-history-play-target-size);
    height: var(--mobile-history-play-target-size);
    margin-right: 0;
    border-color: transparent;
    background: transparent;
    color: var(--accent);
  }

  .history-play::before {
    position: absolute;
    inset: var(--mobile-history-play-inset);
    border-radius: 50%;
    content: "";
    background: var(--color-overlay-faint);
  }

  .history-play-icon {
    position: relative;
    z-index: var(--z-base);
  }

  .history-play:active {
    filter: brightness(1.2);
    transform: scale(0.96);
  }

  .history-end {
    flex-direction: column;
    height: var(--mobile-history-row-height);
    gap: var(--space-2);
    opacity: 0.4;
  }

  .history-end-label--desktop {
    display: none;
  }

  .history-end-label--mobile {
    display: inline;
    font-size: var(--text-xs);
    letter-spacing: 0.3em;
  }
}
</style>
