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
        <button class="primary-btn" @click="router.push('/comics')">去漫画库</button>
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
      <button class="primary-btn" @click="router.push('/comics')">开始阅读</button>
    </div>

    <!-- 列表 -->
    <template v-else>
      <!-- 今天 -->
      <section v-if="today.length > 0" class="history-section">
        <h2 class="section-title">
          今天
          <span class="section-count">{{ today.length }}</span>
        </h2>
        <div class="history-cards">
          <HistoryCard
            v-for="item in today"
            :key="item.comicId"
            :item="item"
            @continue="continueRead(item)"
            @detail="goDetail(item.comicId)"
          />
        </div>
      </section>

      <!-- 昨天 -->
      <section v-if="yesterday.length > 0" class="history-section">
        <h2 class="section-title">
          昨天
          <span class="section-count">{{ yesterday.length }}</span>
        </h2>
        <div class="history-cards">
          <HistoryCard
            v-for="item in yesterday"
            :key="item.comicId"
            :item="item"
            @continue="continueRead(item)"
            @detail="goDetail(item.comicId)"
          />
        </div>
      </section>

      <!-- 更早 -->
      <section v-if="earlier.length > 0" class="history-section">
        <h2 class="section-title">
          更早
          <span class="section-count">{{ earlier.length }}</span>
        </h2>
        <div class="history-cards">
          <HistoryCard
            v-for="item in earlier"
            :key="item.comicId"
            :item="item"
            @continue="continueRead(item)"
            @detail="goDetail(item.comicId)"
          />
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import { useHistoryStore } from '@/stores/history-store'
import type { HistoryVO } from '@/types'
import HistoryCard from '@/components/history/HistoryCard.vue'

const router = useRouter()
const store = useHistoryStore()

function bucketDate(ts: string): { today: boolean; yesterday: boolean } {
  const d = new Date(ts)
  const now = new Date()
  const diffDays = Math.floor((now.getTime() - d.getTime()) / 86400000)
  return {
    today: diffDays === 0,
    yesterday: diffDays === 1,
  }
}

const today = computed(() => store.list.filter((i: HistoryVO) => bucketDate(i.updatedAt).today))
const yesterday = computed(() => store.list.filter((i: HistoryVO) => bucketDate(i.updatedAt).yesterday))
const earlier = computed(() =>
  store.list.filter((i: HistoryVO) => {
    const b = bucketDate(i.updatedAt)
    return !b.today && !b.yesterday
  })
)
const recentCount = computed(() => store.list.length)

function continueRead(item: HistoryVO) {
  router.push(
    `/comics/${item.comicId}/read?chapterId=${item.chapterId}&page=${item.pageNumber}`
  )
}

function goDetail(comicId: number) {
  router.push(`/comics/${comicId}`)
}

onMounted(() => {
  store.fetchList()
})
</script>

<style scoped>
.history-page {
  max-width: 900px;
  margin: 0 auto;
  padding: var(--space-xl) var(--space-lg) var(--space-3xl);
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
  gap: 4px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0;
}

.page-subtitle {
  font-size: 14px;
  color: var(--text);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: var(--space-sm);
}

/* Section */
.history-section {
  margin-bottom: var(--space-2xl);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: 14px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0 0 var(--space-base);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.section-count {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  background: var(--surface);
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--border);
}

.history-cards {
  display: flex;
  flex-direction: column;
  gap: var(--space-base);
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
  color: var(--text);
}

.state.error {
  color: var(--danger);
  background: var(--surface);
  border-radius: var(--radius-md);
  padding: var(--space-xl);
}

.state.empty {
  color: var(--text-muted);
}

.empty-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-h);
  margin: 0;
}

.empty-desc {
  font-size: 13px;
  color: var(--text);
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
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 150ms ease;
}

.primary-btn:hover {
  background: var(--accent-hover);
}

.ghost-btn {
  padding: 8px 16px;
  background: transparent;
  color: var(--text-h);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 150ms ease;
}

.ghost-btn:hover {
  background: var(--surface);
  border-color: var(--text-muted);
}
</style>
