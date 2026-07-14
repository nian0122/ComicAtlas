<template>
  <div class="comic-detail-page fade-in">
    <div v-if="loading" class="state loading">
      <div class="spinner" />
      <span>加载中...</span>
    </div>

    <div v-else-if="error" class="state error">
      <el-icon :size="48"><WarningFilled /></el-icon>
      <span>{{ error }}</span>
      <button class="hero-btn hero-btn--primary" @click="loadData">重试</button>
    </div>

    <template v-else-if="comic">
      <!-- Hero -->
      <HeroBanner
        :background-url="comic.coverUrl"
        :poster-url="comic.coverUrl"
        :title="comic.title"
        :subtitle="progressSubtitle"
        :primary-action="primaryAction"
        :secondary-action="secondaryAction"
      >
        <template #description>
          <div class="progress-block">
            <p class="progress-label">阅读进度</p>
            <div class="progress-meta">
              <span>{{ progressMetaText }}</span>
              <span class="progress-percent">{{ comic.progressPercent || 0 }}%</span>
            </div>
            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: progressWidth }" />
            </div>
          </div>
        </template>
      </HeroBanner>

      <!-- Information -->
      <section class="information-section">
        <div class="section-inner">
          <div class="info-section-header">
            <h2 class="section-title">信息</h2>
            <div class="more-menu">
              <button
                class="more-btn"
                @click="menuOpen = !menuOpen"
              >
                <el-icon :size="16"><MoreFilled /></el-icon>
                <span>More</span>
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
          <div class="info-grid">
            <div class="info-item">
              <span class="info-label">作者</span>
              <span class="info-value">{{ comic.author || '未知作者' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">页数</span>
              <span class="info-value">{{ comic.pageCount }} 页</span>
            </div>
            <div class="info-item">
              <span class="info-label">分类</span>
              <span class="info-value">{{ comic.category || '-' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">大小</span>
              <span class="info-value">{{ formatBytes(comic.fileSize) }}</span>
            </div>
            <div class="info-item info-item--wide">
              <span class="info-label">标签</span>
              <span class="info-value">
                <template v-if="comic.tags && comic.tags.length">
                  <span v-for="tag in comic.tags" :key="tag.name" class="tag-chip">
                    {{ tag.name }}
                  </span>
                </template>
                <span v-else class="info-placeholder">-</span>
              </span>
            </div>
            <div class="info-item">
              <span class="info-label">导入时间</span>
              <span class="info-value">{{ formatDate(comic.createdAt) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">来源类型</span>
              <span class="info-value">{{ comic.sourceType || '-' }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Catalog -->
      <section class="catalog-section">
        <div class="section-inner">
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
import HeroBanner from '@/components/layout/HeroBanner.vue'

const route = useRoute()
const router = useRouter()

const comic = ref<ComicDetailVO | null>(null)
const catalogTree = ref<CatalogNode[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const lqGenerating = ref(false)
const menuOpen = ref(false)

const lastReadChapter = computed<ChapterRef | null>(() => {
  if (!comic.value?.lastReadChapterId) return null
  return findChapterById(catalogTree.value, comic.value.lastReadChapterId)
})

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

const progressSubtitle = computed(() => {
  if (!comic.value) return ''
  const ch = lastReadChapter.value
  const pageText = `第 ${comic.value.lastReadPage || 1} / ${comic.value.pageCount || 0} 页`
  if (ch) {
    const chapterLabel = ch.title || `第${ch.chapterNo}话`
    return `阅读至 ${chapterLabel} · ${pageText}`
  }
  return pageText
})

const progressMetaText = computed(() => {
  if (!comic.value) return ''
  const ch = lastReadChapter.value
  const pageText = `第 ${comic.value.lastReadPage || 1} / ${comic.value.pageCount || 0} 页`
  if (ch) {
    const chapterLabel = ch.title || `第${ch.chapterNo}话`
    return `${chapterLabel} · ${pageText}`
  }
  return pageText
})

const progressWidth = computed(
  () => `${Math.min(100, Math.max(0, comic.value?.progressPercent || 0))}%`
)

const primaryAction = computed(() => {
  if (!comic.value?.lastReadChapterId) return undefined
  return {
    label: '▶ 继续阅读',
    onClick: continueRead,
  }
})

const secondaryAction = computed(() => {
  if (!firstChapter.value || !comic.value) return undefined
  return {
    label: '开始阅读',
    onClick: startRead,
  }
})

function findChapterById(nodes: CatalogNode[], id: number): ChapterRef | null {
  for (const node of nodes) {
    const found = node.chapters?.find((ch) => ch.id === id)
    if (found) return found
    const childFound = findChapterById(node.children || [], id)
    if (childFound) return childFound
  }
  return null
}

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

function formatBytes(bytes: number): string {
  if (bytes == null || bytes === 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(i === 0 ? 0 : 2)} ${units[i]}`
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
  min-height: calc(100vh - var(--nav-height));
  padding-bottom: var(--space-3xl);
  background: var(--bg-primary);
  color: var(--text-primary);
}

/* Hero action buttons (slotted, so styles live here) */
.hero-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 10px 22px;
  border: none;
  border-radius: var(--radius-sm);
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  transition: transform var(--transition-fast), background-color var(--transition-fast);
}

.hero-btn:hover {
  transform: translateY(-1px);
}

.hero-btn--primary {
  background: var(--accent);
  color: var(--text-primary);
}

.hero-btn--primary:hover {
  background: var(--accent-hover);
}

.hero-btn--secondary {
  background: rgba(255, 255, 255, 0.2);
  color: var(--text-primary);
}

.hero-btn--secondary:hover {
  background: rgba(255, 255, 255, 0.3);
}

/* Progress */
.progress-block {
  width: 100%;
  max-width: 520px;
}

.progress-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 var(--space-xs);
}

.progress-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0 0 var(--space-sm);
}

.progress-percent {
  font-weight: 700;
  color: var(--accent);
}

.progress-bar {
  height: 4px;
  background: rgba(255, 255, 255, 0.15);
  border-radius: var(--radius-pill);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--accent);
  border-radius: var(--radius-pill);
  transition: width var(--transition-normal);
}

/* More menu */
.more-menu {
  position: relative;
}

.more-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 8px 14px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.more-btn:hover {
  background: rgba(255, 255, 255, 0.18);
}

.more-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  min-width: 160px;
  background: var(--bg-surface);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--card-radius);
  box-shadow: var(--card-shadow-hover);
  z-index: 30;
  overflow: hidden;
}

.menu-item {
  display: block;
  width: 100%;
  padding: 10px 16px;
  background: transparent;
  border: none;
  color: var(--text-primary);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.menu-item:hover:not(:disabled) {
  background: var(--bg-secondary);
}

.menu-item.danger:hover {
  color: var(--accent);
}

.menu-item:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Information */
.information-section {
  padding: var(--space-2xl) var(--page-padding);
}

.section-inner {
  max-width: var(--page-width);
  margin: 0 auto;
}

.info-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-lg);
}

.section-title {
  font-family: var(--heading);
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-base);
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-base);
  background: var(--bg-surface);
  border-radius: var(--card-radius);
}

.info-item--wide {
  grid-column: 1 / -1;
}

.info-label {
  font-size: 12px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.info-value {
  font-size: 14px;
  color: var(--text-primary);
  line-height: 1.4;
}

.info-placeholder {
  color: var(--text-muted);
}

.tag-chip {
  display: inline-block;
  font-size: 12px;
  color: var(--text-primary);
  background: var(--accent-bg);
  padding: 3px 10px;
  border-radius: var(--radius-sm);
  margin-right: var(--space-xs);
  margin-bottom: var(--space-xs);
}

/* Catalog */
.catalog-section {
  padding: 0 var(--page-padding) var(--space-3xl);
}

.catalog-header {
  display: flex;
  align-items: baseline;
  gap: var(--space-base);
  margin-bottom: var(--space-lg);
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
  color: var(--text-secondary);
}

.state.small {
  padding: var(--space-xl) 0;
}

.state.error {
  color: var(--accent);
}

.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid rgba(255, 255, 255, 0.15);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Responsive */
@media (max-width: 768px) {
  .info-grid {
    grid-template-columns: 1fr;
  }
}
</style>
