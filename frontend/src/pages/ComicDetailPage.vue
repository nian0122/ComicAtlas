<template>
  <div class="comic-detail-page">
    <div v-if="loading" class="state loading">
      <div class="spinner" />
      <span>加载中...</span>
    </div>

    <div v-else-if="error" class="state error">
      <el-icon :size="48"><WarningFilled /></el-icon>
      <span>{{ error }}</span>
      <button class="primary-btn" @click="loadData">重试</button>
    </div>

    <template v-else-if="comic">
      <!-- 顶部 Hero 区 -->
      <section class="hero-section">
        <div class="hero-backdrop" :style="{ backgroundImage: `url(${comic.coverUrl})` }" />
        <div class="hero-content">
          <div class="cover-area">
            <el-image :src="comic.coverUrl" fit="cover" class="cover">
              <template #error>
                <div class="cover-placeholder">
                  <el-icon :size="48"><PictureFilled /></el-icon>
                </div>
              </template>
            </el-image>
          </div>

          <div class="info-area">
            <div class="info-top">
              <h1 class="comic-title">{{ comic.title }}</h1>
              <p v-if="comic.titleJpn" class="comic-title-jpn">{{ comic.titleJpn }}</p>
              <p class="comic-author">{{ comic.author || '未知作者' }}</p>

              <div class="meta-row">
                <span v-if="comic.category" class="meta-item">{{ comic.category }}</span>
                <span v-if="comic.pageCount" class="meta-item">{{ comic.pageCount }} 页</span>
                <span v-if="comic.sourceType" class="meta-item">{{ comic.sourceType }}</span>
                <span v-if="comic.updatedAt" class="meta-item">更新 {{ formatDate(comic.updatedAt) }}</span>
              </div>

              <div v-if="comic.tags && comic.tags.length > 0" class="tags-row">
                <span v-for="tag in comic.tags" :key="tag.name" class="tag-chip">
                  {{ tag.name }}
                </span>
              </div>
            </div>

            <div class="continue-reading">
              <div v-if="comic.lastReadChapterId && comic.progressPercent > 0" class="progress-block">
                <p class="progress-label">继续阅读</p>
                <p class="progress-meta">
                  <span>第 {{ comic.lastReadPage }} / {{ comic.pageCount }} 页</span>
                  <span class="progress-percent">{{ comic.progressPercent }}%</span>
                </p>
                <div class="progress-bar">
                  <div class="progress-fill" :style="{ width: `${comic.progressPercent}%` }" />
                </div>
              </div>

              <div class="continue-actions">
                <button
                  v-if="comic.lastReadChapterId"
                  class="primary-btn large continue-btn"
                  @click="continueRead"
                >
                  继续阅读 ▶
                </button>
                <button
                  v-else-if="firstChapter"
                  class="primary-btn large continue-btn"
                  @click="startRead"
                >
                  开始阅读 ▶
                </button>
                <button class="ghost-btn" @click="goBack">
                  返回
                </button>

                <div class="more-menu">
                  <button class="more-btn" @click="menuOpen = !menuOpen">
                    <el-icon :size="20"><MoreFilled /></el-icon>
                  </button>
                  <div v-if="menuOpen" class="more-dropdown" @click.stop>
                    <button
                      class="menu-item"
                      :disabled="lqGenerating"
                      @click="generateLq"
                    >
                      {{ lqGenerating ? '生成中...' : '生成 LQ' }}
                    </button>
                    <button class="menu-item danger" @click="confirmDelete">
                      删除漫画
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- Catalog 主体 -->
      <section class="catalog-section">
        <div class="catalog-header">
          <h2 class="section-title">目录</h2>
          <span v-if="totalChapters > 0" class="section-count">{{ totalChapters }} 话</span>
        </div>

        <CatalogTree
          v-if="catalogTree.length > 0"
          :tree="catalogTree"
          :active-chapter-id="comic.lastReadChapterId"
          @select="goReader"
        />
        <div v-else class="state empty small">
          <el-icon :size="32"><PictureFilled /></el-icon>
          <span>暂无章节</span>
        </div>
      </section>
    </template>

    <div v-else class="state empty">
      <el-icon :size="48"><PictureFilled /></el-icon>
      <span>漫画不存在</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { PictureFilled, WarningFilled, MoreFilled } from '@element-plus/icons-vue'
import { comicApi, catalogApi, lqApi } from '@/services/api'
import type { ComicDetailVO, CatalogNode, ChapterRef } from '@/types'
import CatalogTree from '@/components/comic/CatalogTree.vue'

const route = useRoute()
const router = useRouter()

const comic = ref<ComicDetailVO | null>(null)
const catalogTree = ref<CatalogNode[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const lqGenerating = ref(false)
const menuOpen = ref(false)

const firstChapter = computed<ChapterRef | null>(() => {
  for (const node of catalogTree.value) {
    const ch = findFirstChapter(node)
    if (ch) return ch
  }
  return null
})

const totalChapters = computed(() => {
  let count = 0
  for (const node of catalogTree.value) {
    count += countChapters(node)
  }
  return count
})

function findFirstChapter(node: CatalogNode): ChapterRef | null {
  if (node.chapters && node.chapters.length > 0) return node.chapters[0]
  for (const child of node.children || []) {
    const found = findFirstChapter(child)
    if (found) return found
  }
  return null
}

function countChapters(node: CatalogNode): number {
  let n = node.chapters?.length || 0
  for (const child of node.children || []) n += countChapters(child)
  return n
}

function formatDate(s: string): string {
  return s?.slice(0, 10) || ''
}

function goBack() {
  router.push('/comics')
}

function continueRead() {
  if (!comic.value) return
  router.push(
    `/comics/${comic.value.id}/read?chapterId=${comic.value.lastReadChapterId}&page=${comic.value.lastReadPage}`
  )
}

function startRead() {
  const ch = firstChapter.value
  if (!ch || !comic.value) return
  router.push(`/comics/${comic.value.id}/read?chapterId=${ch.id}&page=1`)
}

function goReader(chapterId: number) {
  if (!comic.value) return
  router.push(`/comics/${comic.value.id}/read?chapterId=${chapterId}&page=1`)
}

async function generateLq() {
  if (!comic.value) return
  menuOpen.value = false
  lqGenerating.value = true
  try {
    await lqApi.generateComic(comic.value.id)
    ElMessage.success('LQ 生成任务已提交')
  } catch {
    ElMessage.error('提交失败')
  } finally {
    lqGenerating.value = false
  }
}

async function confirmDelete() {
  if (!comic.value) return
  menuOpen.value = false
  try {
    await ElMessageBox.confirm('确定删除此漫画？该操作不可恢复。', '删除漫画', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await comicApi.delete(comic.value.id)
    ElMessage.success('删除成功')
    router.push('/comics')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

async function loadData() {
  const id = Number(route.params.id)
  if (!id) {
    error.value = '参数不完整'
    return
  }
  loading.value = true
  error.value = null
  try {
    const [detailRes, catalogRes] = await Promise.all([
      comicApi.detail(id),
      catalogApi.tree(id),
    ])
    comic.value = detailRes.data as ComicDetailVO
    catalogTree.value = (catalogRes.data || []) as CatalogNode[]
  } catch (err: any) {
    error.value = err?.response?.data?.message || '加载漫画详情失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.comic-detail-page {
  max-width: 1200px;
  margin: 0 auto;
  padding-bottom: var(--space-3xl);
}

/* Hero */
.hero-section {
  position: relative;
  margin: 0 calc(-1 * var(--space-lg)) var(--space-xl);
  overflow: hidden;
}

.hero-backdrop {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center;
  filter: blur(40px) brightness(0.4);
  transform: scale(1.2);
  z-index: 0;
}

.hero-backdrop::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(to top, var(--bg) 10%, rgba(20, 20, 20, 0.6) 50%, transparent);
}

.hero-content {
  position: relative;
  z-index: 1;
  display: flex;
  gap: var(--space-xl);
  padding: var(--space-2xl) var(--space-lg);
}

.cover-area {
  flex-shrink: 0;
  width: 200px;
}

.cover {
  width: 100%;
  aspect-ratio: 2 / 3;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--surface-elevated);
  box-shadow: var(--shadow-lg);
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}

.info-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding-top: var(--space-base);
}

.comic-title {
  font-size: 32px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0 0 4px;
  line-height: 1.1;
}

.comic-title-jpn {
  font-size: 16px;
  color: var(--text);
  margin: 0 0 8px;
}

.comic-author {
  font-size: 14px;
  color: var(--text);
  margin: 0 0 var(--space-base);
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-bottom: var(--space-base);
}

.meta-item {
  font-size: 12px;
  color: var(--text);
  background: rgba(255, 255, 255, 0.1);
  padding: 4px 10px;
  border-radius: var(--radius-sm);
}

.tags-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: var(--space-lg);
}

.tag-chip {
  font-size: 12px;
  color: var(--text-h);
  background: var(--accent-bg);
  padding: 3px 10px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--accent-border);
}

/* Continue Reading */
.continue-reading {
  margin-top: var(--space-lg);
}

.progress-block {
  margin-bottom: var(--space-base);
  max-width: 480px;
}

.progress-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-h);
  margin: 0 0 6px;
}

.progress-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--text);
  margin: 0 0 8px;
}

.progress-percent {
  font-weight: 700;
  color: var(--accent);
}

.progress-bar {
  height: 4px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: var(--radius-pill);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--accent);
  border-radius: var(--radius-pill);
  transition: width 300ms ease;
}

.continue-actions {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  flex-wrap: wrap;
}

.primary-btn.large {
  font-size: 16px;
  padding: 12px 24px;
}

.continue-btn {
  letter-spacing: 0.05em;
}

.ghost-btn {
  padding: 12px 20px;
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-h);
  border: none;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 150ms ease;
}

.ghost-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

/* More menu */
.more-menu {
  position: relative;
  margin-left: auto;
}

.more-btn {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: var(--text-h);
  border: none;
  border-radius: 50%;
  cursor: pointer;
  transition: background 150ms ease;
}

.more-btn:hover {
  background: rgba(255, 255, 255, 0.1);
}

.more-dropdown {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 8px;
  min-width: 160px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-lg);
  z-index: 20;
  overflow: hidden;
}

.menu-item {
  display: block;
  width: 100%;
  padding: 10px 16px;
  background: transparent;
  border: none;
  color: var(--text-h);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: background 150ms ease;
}

.menu-item:hover:not(:disabled) {
  background: var(--surface-elevated);
}

.menu-item.danger:hover {
  color: var(--danger);
}

.menu-item:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Catalog section */
.catalog-section {
  padding: 0 var(--space-lg);
}

.catalog-header {
  display: flex;
  align-items: baseline;
  gap: var(--space-base);
  margin-bottom: var(--space-lg);
}

.section-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0;
}

.section-count {
  font-size: 13px;
  color: var(--text-muted);
}

/* States */
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-base);
  padding: var(--space-3xl) 0;
  color: var(--text);
}

.state.small {
  padding: var(--space-xl) 0;
}

.state.error {
  color: var(--danger);
}

.state.empty p {
  color: var(--text-muted);
  font-size: 13px;
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

.primary-btn {
  padding: 8px 20px;
  background: var(--accent);
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 150ms ease;
}

.primary-btn:hover {
  background: var(--accent-hover);
}

/* Responsive */
@media (max-width: 768px) {
  .hero-content {
    flex-direction: column;
    align-items: center;
    text-align: center;
    padding: var(--space-lg);
  }

  .cover-area {
    width: 160px;
  }

  .info-top {
    align-items: center;
  }

  .comic-title {
    font-size: 24px;
  }

  .meta-row,
  .tags-row {
    justify-content: center;
  }

  .continue-reading {
    width: 100%;
  }

  .progress-block {
    margin-left: auto;
    margin-right: auto;
  }

  .continue-actions {
    justify-content: center;
  }

  .more-menu {
    margin-left: 0;
  }
}
</style>