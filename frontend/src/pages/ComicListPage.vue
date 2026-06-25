<template>
  <div class="comic-list-page">
    <header class="page-header">
      <h1 class="page-title">漫画库</h1>
      <div class="search-bar">
        <el-input
          v-model="keyword"
          placeholder="搜索漫画..."
          clearable
          class="search-input"
          @clear="onSearch"
          @keyup.enter="onSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-select
          v-model="tagFilter"
          placeholder="标签筛选"
          clearable
          class="tag-select"
          @change="onSearch"
        >
          <el-option
            v-for="t in tags"
            :key="t.name"
            :label="t.name"
            :value="t.name"
          />
        </el-select>
        <el-select
          v-model="store.query.sort"
          class="sort-select"
          @change="onSearch"
        >
          <el-option label="创建时间" value="createdAt" />
          <el-option label="更新时间" value="updatedAt" />
          <el-option label="标题" value="title" />
          <el-option label="页数" value="pageCount" />
          <el-option label="最近阅读" value="lastReadTime" />
        </el-select>
      </div>
    </header>

    <el-row :gutter="20" class="comic-grid">
      <el-col
        v-for="comic in store.list"
        :key="comic.id"
        :xs="12"
        :sm="8"
        :md="6"
        :lg="6"
        class="comic-col"
      >
        <el-card
          shadow="hover"
          class="comic-card"
          :body-style="{ padding: '0' }"
          @click="goDetail(comic.id)"
        >
          <div class="cover-wrapper">
            <el-image
              :src="comic.coverUrl"
              fit="cover"
              lazy
              class="cover-image"
            >
              <template #error>
                <div class="cover-placeholder">
                  <el-icon :size="36"><PictureFilled /></el-icon>
                </div>
              </template>
            </el-image>
            <div
              v-if="comic.progressPercent > 0 && comic.progressPercent < 100"
              class="progress-badge"
            >
              继续阅读 {{ comic.lastReadPage }}/{{ comic.pageCount }} · {{ comic.progressPercent }}%
            </div>
            <div class="status-badges">
              <el-tag
                v-if="comic.status"
                :type="comic.status === '完成' ? 'success' : 'warning'"
                size="small"
              >
                {{ comic.status }}
              </el-tag>
            </div>
          </div>
          <div class="card-body">
            <p class="comic-title">{{ comic.title }}</p>
            <p class="comic-author">{{ comic.author || '未知作者' }}</p>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-empty v-if="!store.loading && store.list.length === 0" description="暂无漫画" />

    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="store.query.page"
        :page-size="store.query.size"
        :total="store.total"
        layout="prev, pager, next"
        background
        @current-change="onPageChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search, PictureFilled } from '@element-plus/icons-vue'
import { useComicStore } from '@/stores/comic-store'
import { useTagStore } from '@/stores/tag-store'

const router = useRouter()
const store = useComicStore()
const tagStore = useTagStore()

const keyword = ref('')
const tagFilter = ref('')

const tags = ref<{ name: string }[]>([])

function onSearch() {
  store.query.keyword = keyword.value || undefined
  store.query.tag = tagFilter.value || undefined
  store.query.page = 1
  store.fetchList()
}

function onPageChange(page: number) {
  store.query.page = page
  store.fetchList()
}

function goDetail(id: number) {
  router.push(`/comics/${id}`)
}

onMounted(async () => {
  await tagStore.fetch()
  tags.value = tagStore.tags as { name: string }[]
  store.fetchList()
})
</script>

<style scoped>
.comic-list-page {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0 0 16px;
}

.search-bar {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.search-input {
  flex: 1;
  min-width: 200px;
}

.tag-select {
  width: 160px;
}

.sort-select {
  width: 140px;
}

.comic-grid {
  margin-bottom: 24px;
}

.comic-col {
  margin-bottom: 20px;
}

.comic-card {
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  transition: transform 0.2s, box-shadow 0.2s;
  background: var(--bg);
  border: 1px solid var(--border);
}

.comic-card:hover {
  transform: translateY(-4px);
}

.cover-wrapper {
  position: relative;
  aspect-ratio: 3 / 4;
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

.progress-badge {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.8));
  color: #fff;
  font-size: 12px;
  padding: 20px 8px 6px;
  text-align: center;
}

.status-badges {
  position: absolute;
  top: 8px;
  right: 8px;
}

.card-body {
  padding: 12px;
}

.comic-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-h);
  margin: 0 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.comic-author {
  font-size: 12px;
  color: var(--text);
  margin: 0;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
