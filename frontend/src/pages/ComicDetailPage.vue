<template>
  <div class="comic-detail-page" v-loading="loading">
    <template v-if="comic">
      <div class="detail-header">
        <div class="cover-section">
          <el-image :src="comic.coverUrl" fit="cover" class="detail-cover">
            <template #error>
              <div class="cover-placeholder">
                <el-icon :size="48"><PictureFilled /></el-icon>
              </div>
            </template>
          </el-image>
        </div>
        <div class="info-section">
          <h1 class="comic-title">{{ comic.title }}</h1>
          <p class="comic-subtitle" v-if="comic.titleJpn">{{ comic.titleJpn }}</p>
          <p class="comic-author">作者：{{ comic.author || '未知' }}</p>

          <div class="meta-row">
            <span class="meta-item">来源：{{ comic.sourceType }}</span>
            <span class="meta-item">分类：{{ comic.category }}</span>
            <span class="meta-item">页数：{{ comic.pageCount }}</span>
          </div>

          <div class="tags-row" v-if="comic.tags && comic.tags.length > 0">
            <el-tag
              v-for="tag in comic.tags"
              :key="tag.name"
              :type="tag.type === 'genre' ? '' : 'info'"
              effect="plain"
              size="small"
            >
              {{ tag.name }}
            </el-tag>
          </div>

          <div class="progress-info" v-if="comic.progressPercent > 0">
            <el-progress
              :percentage="comic.progressPercent"
              :stroke-width="6"
              :text-inside="true"
            />
          </div>

          <div class="action-buttons">
            <el-button
              v-if="comic.lastReadChapterId"
              type="primary"
              @click="continueRead"
            >
              继续阅读
            </el-button>
            <el-button @click="startRead">从头开始</el-button>
            <el-popconfirm
              title="确定删除此漫画？"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="deleteComic"
            >
              <template #reference>
                <el-button type="danger" plain>删除漫画</el-button>
              </template>
            </el-popconfirm>
          </div>
        </div>
      </div>

      <div class="chapter-section">
        <h2 class="section-title">章节列表 ({{ comic.chapters?.length || 0 }})</h2>
        <div class="chapter-list">
          <div
            v-for="ch in comic.chapters"
            :key="ch.id"
            class="chapter-item"
            @click="goReader(ch.id)"
          >
            <span class="chapter-no">第{{ ch.chapterNo }}话</span>
            <span class="chapter-title">{{ ch.title }}</span>
            <span class="chapter-pages">{{ ch.pageCount }}页</span>
          </div>
          <el-empty v-if="!comic.chapters || comic.chapters.length === 0" description="暂无章节" />
        </div>
      </div>
    </template>

    <el-empty v-else-if="!loading" description="漫画不存在" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { PictureFilled } from '@element-plus/icons-vue'
import { comicApi } from '@/services/api'
import type { ComicDetailVO } from '@/types'

const route = useRoute()
const router = useRouter()

const comic = ref<ComicDetailVO | null>(null)
const loading = ref(true)

function continueRead() {
  if (!comic.value) return
  router.push(
    `/comics/${comic.value.id}/read?chapterId=${comic.value.lastReadChapterId}&page=${comic.value.lastReadPage}`
  )
}

function startRead() {
  if (!comic.value || !comic.value.chapters || comic.value.chapters.length === 0) return
  const firstChapter = comic.value.chapters[0]
  router.push(`/comics/${comic.value.id}/read?chapterId=${firstChapter.id}&page=1`)
}

function goReader(chapterId: number) {
  if (!comic.value) return
  router.push(`/comics/${comic.value.id}/read?chapterId=${chapterId}&page=1`)
}

async function deleteComic() {
  if (!comic.value) return
  try {
    await comicApi.delete(comic.value.id)
    ElMessage.success('删除成功')
    router.push('/comics')
  } catch {
    ElMessage.error('删除失败')
  }
}

onMounted(async () => {
  const id = Number(route.params.id)
  if (!id) {
    loading.value = false
    return
  }
  try {
    const res = await comicApi.detail(id)
    comic.value = res.data as ComicDetailVO
  } catch {
    ElMessage.error('加载漫画详情失败')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.comic-detail-page {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.detail-header {
  display: flex;
  gap: 32px;
  margin-bottom: 40px;
}

.cover-section {
  flex-shrink: 0;
  width: 240px;
}

.detail-cover {
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: 8px;
  overflow: hidden;
  background: var(--code-bg);
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text);
}

.info-section {
  flex: 1;
  min-width: 0;
}

.comic-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0 0 4px;
}

.comic-subtitle {
  font-size: 16px;
  color: var(--text);
  margin: 0 0 8px;
}

.comic-author {
  font-size: 14px;
  color: var(--text);
  margin: 0 0 12px;
}

.meta-row {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.meta-item {
  font-size: 13px;
  color: var(--text);
  background: var(--code-bg);
  padding: 4px 10px;
  border-radius: 4px;
}

.tags-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 16px;
}

.progress-info {
  margin-bottom: 20px;
  max-width: 300px;
}

.action-buttons {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.section-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-h);
  margin: 0 0 16px;
}

.chapter-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.chapter-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  cursor: pointer;
  transition: background 0.2s;
}

.chapter-item:hover {
  background: var(--social-bg);
}

.chapter-no {
  font-weight: 600;
  color: var(--accent);
  margin-right: 12px;
  min-width: 60px;
}

.chapter-title {
  flex: 1;
  color: var(--text-h);
  font-size: 14px;
}

.chapter-pages {
  color: var(--text);
  font-size: 12px;
}

@media (max-width: 768px) {
  .detail-header {
    flex-direction: column;
    align-items: center;
  }

  .cover-section {
    width: 200px;
  }

  .info-section {
    text-align: center;
  }

  .action-buttons {
    justify-content: center;
  }

  .tags-row {
    justify-content: center;
  }
}
</style>
