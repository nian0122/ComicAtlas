<template>
  <div class="history-page">
    <header class="page-header">
      <h1 class="page-title">阅读历史</h1>
    </header>

    <div v-loading="loading" class="history-list">
      <el-card
        v-for="item in store.list"
        :key="item.comicId"
        shadow="hover"
        class="history-card"
        @click="continueRead(item)"
      >
        <div class="card-inner">
          <div class="cover-wrapper">
            <el-image :src="item.coverUrl" fit="cover" lazy class="cover-image">
              <template #error>
                <div class="cover-placeholder">
                  <el-icon :size="28"><PictureFilled /></el-icon>
                </div>
              </template>
            </el-image>
          </div>
          <div class="card-info">
            <p class="comic-title">{{ item.comicTitle }}</p>
            <p class="chapter-info">
              第{{ item.chapterNo }}话 · {{ item.pageNumber }}/{{ item.totalPages }} · {{ item.progressPercent }}%
            </p>
            <p class="read-time">{{ formatTime(item.updatedAt) }}</p>
            <div class="mini-progress">
              <el-progress
                :percentage="item.progressPercent"
                :stroke-width="4"
                :show-text="false"
              />
            </div>
          </div>
        </div>
      </el-card>

      <el-empty v-if="!loading && store.list.length === 0" description="暂无阅读历史" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { PictureFilled } from '@element-plus/icons-vue'
import { useHistoryStore } from '@/stores/history-store'
import type { HistoryVO } from '@/types'

const router = useRouter()
const store = useHistoryStore()
const loading = ref(true)

function continueRead(item: HistoryVO) {
  router.push(
    `/comics/${item.comicId}/read?chapterId=${item.chapterId}&page=${item.pageNumber}`
  )
}

function formatTime(ts: string): string {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const diffMs = now.getTime() - d.getTime()
  const diffMin = Math.floor(diffMs / 60000)

  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin}分钟前`
  if (diffMin < 1440) return `${Math.floor(diffMin / 60)}小时前`
  if (diffMin < 43200) return `${Math.floor(diffMin / 1440)}天前`
  return d.toLocaleDateString('zh-CN')
}

onMounted(async () => {
  loading.value = true
  await store.fetchList()
  loading.value = false
})
</script>

<style scoped>
.history-page {
  padding: 24px;
  max-width: 900px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 200px;
}

.history-card {
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  transition: transform 0.2s;
  background: var(--bg);
  border: 1px solid var(--border);
}

.history-card:hover {
  transform: translateX(4px);
}

.card-inner {
  display: flex;
  gap: 16px;
  align-items: center;
}

.cover-wrapper {
  flex-shrink: 0;
  width: 80px;
  height: 106px;
  border-radius: 6px;
  overflow: hidden;
  background: var(--code-bg);
}

.cover-image {
  width: 100%;
  height: 100%;
  display: block;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text);
}

.card-info {
  flex: 1;
  min-width: 0;
}

.comic-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-h);
  margin: 0 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-info {
  font-size: 13px;
  color: var(--text);
  margin: 0 0 4px;
}

.read-time {
  font-size: 12px;
  color: var(--text);
  margin: 0 0 6px;
}

.mini-progress {
  max-width: 200px;
}
</style>
