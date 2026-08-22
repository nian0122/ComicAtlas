<template>
  <div class="comic-detail-page fade-in" :class="{ 'is-mobile': mode === 'mobile' }">
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
      <MobileComicDetail
        v-if="mode === 'mobile'"
        :comic="comic"
        :catalog-tree="catalogTree"
        :total-chapters="totalChapters"
        :progress-text="progressMetaText"
        :progress-scale="progressScale"
        :read-label="primaryAction?.label || '开始阅读'"
        :can-read="Boolean(primaryAction)"
        @read="readComic"
        @select="goReader"
      />

      <template v-else>
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
              <div class="progress-fill" :style="{ transform: `scaleX(${progressScale})` }" />
            </div>
          </div>
        </template>
      </HeroBanner>

      <!-- Information -->
      <section class="information-section">
        <div class="section-inner">
          <div class="info-section-header">
            <h2 class="section-title">作品信息</h2>
          </div>
          <div class="info-grid">
            <div class="info-item">
              <span class="info-label">作者</span>
              <span class="info-value">{{ comic.author || '未知作者' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">媒体数</span>
              <span class="info-value">{{ comic.pageCount }} 个</span>
            </div>
            <div v-if="comic.categoryName" class="info-item">
              <span class="info-label">分类</span>
              <span class="info-value">{{ comic.categoryName }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">大小</span>
              <span class="info-value">{{ formatBytes(comic.hqSize) }}</span>
            </div>
          </div>

          <div v-if="comic.description" class="description-block">
            <span class="info-label">简介</span>
            <p>{{ comic.description }}</p>
          </div>

          <div v-if="comic.tags && comic.tags.length" class="tags-block">
            <span class="info-label">标签</span>
            <div class="tag-list">
              <span v-for="tag in comic.tags" :key="tag.name" class="tag-chip">
                {{ tag.name }}
              </span>
            </div>
          </div>

          <details class="secondary-info">
            <summary>更多信息</summary>
            <div class="secondary-info-grid">
              <div class="info-item">
                <span class="info-label">导入时间</span>
                <span class="info-value">{{ formatDate(comic.createdAt) }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">来源类型</span>
                <span class="info-value">{{ sourceTypeLabel(comic.sourceType) }}</span>
              </div>
            </div>
          </details>
        </div>
      </section>

      <!-- Catalog -->
      <section class="catalog-section">
        <div class="section-inner">
          <div class="catalog-header">
            <h2 class="section-title">目录</h2>
            <span v-if="totalChapters > 0" class="section-count">{{ totalChapters }} 个章节</span>
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
import { PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import { comicApi, catalogApi } from '@/services/reading'

import type { ComicDetailVO, CatalogNode, ChapterRef } from '@/types'
import CatalogTree from '@/components/reading/comic/CatalogTree.vue'
import MobileComicDetail from '@/components/reading/comic/MobileComicDetail.vue'
import { sourceTypeLabel } from '@/utils/source-format'
import HeroBanner from '@/components/reading/HeroBanner.vue'
import { useInteractionMode } from '@/views/reading/reader/composables/useInteractionMode'

const route = useRoute()
const router = useRouter()

// 交互模式检测：mobile 时给根容器加 is-mobile 类，驱动下方移动端布局
const { mode } = useInteractionMode()

const comic = ref<ComicDetailVO | null>(null)
const catalogTree = ref<CatalogNode[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const lastReadChapter = computed<ChapterRef | null>(() => {
  if (!comic.value?.lastReadChapterId) return null
  return findChapterById(catalogTree.value, comic.value.lastReadChapterId)
})

const firstChapter = computed<ChapterRef | null>(() => {
  const all = collectChapters(catalogTree.value)
  if (all.length === 0) return null
  return all.reduce((min, ch) => (orderOf(ch) < orderOf(min) ? ch : min))
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
  const progressText = `进度 ${comic.value.lastReadPage || 1} / ${comic.value.pageCount || 0}`
  if (ch) {
    const chapterLabel = ch.title || `第${ch.chapterNo}话`
    return `阅读至 ${chapterLabel} · ${progressText}`
  }
  return progressText
})

const progressMetaText = computed(() => {
  if (!comic.value) return ''
  const ch = lastReadChapter.value
  const progressText = `进度 ${comic.value.lastReadPage || 1} / ${comic.value.pageCount || 0}`
  if (ch) {
    const chapterLabel = ch.title || `第${ch.chapterNo}话`
    return `${chapterLabel} · ${progressText}`
  }
  return progressText
})

const progressScale = computed(
  () => Math.min(100, Math.max(0, comic.value?.progressPercent || 0)) / 100
)

const primaryAction = computed(() => {
  // 有阅读历史 → 继续阅读（桌面端与移动端一致）
  if (comic.value?.lastReadChapterId) {
    return {
      label: '继续阅读',
      onClick: continueRead,
    }
  }
  // 移动端同时只显示一个主按钮：无历史时把"开始阅读"提升为主按钮
  if (mode.value === 'mobile' && firstChapter.value) {
    return {
      label: '开始阅读',
      onClick: startRead,
    }
  }
  return undefined
})

const secondaryAction = computed(() => {
  // 移动端只保留一个主操作按钮，不渲染次按钮
  if (mode.value === 'mobile') return undefined
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

/** 递归收集全部章节（含子目录），用于按全局锚点取首章 */
function collectChapters(nodes: CatalogNode[]): ChapterRef[] {
  const out: ChapterRef[] = []
  for (const node of nodes) {
    out.push(...(node.chapters ?? []))
    out.push(...collectChapters(node.children ?? []))
  }
  return out
}

/** 阅读顺序锚点；null 锚点视为最大（排最后） */
function orderOf(ch: ChapterRef): number {
  return ch.globalOrder ?? Number.MAX_SAFE_INTEGER
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
  if (!comic.value?.lastReadChapterId) return
  router.push(`/reader/${comic.value.lastReadChapterId}?page=${comic.value.lastReadPage || 1}`)
}

function startRead() {
  const ch = firstChapter.value
  if (!ch) return
  router.push(`/reader/${ch.id}?page=1`)
}

function readComic() {
  primaryAction.value?.onClick()
}

function goReader(chapterId: number) {
  router.push(`/reader/${chapterId}?page=1`)
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
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    error.value = msg || '加载漫画详情失败'
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
  background: var(--color-overlay-soft);
  color: var(--text-primary);
}

.hero-btn--secondary:hover {
  background: var(--color-overlay-hover);
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
  background: var(--color-progress-track);
  border-radius: var(--radius-pill);
  overflow: hidden;
}

.progress-fill {
  width: 100%;
  height: 100%;
  background: var(--accent);
  border-radius: var(--radius-pill);
  transform-origin: left center;
  transition: transform var(--transition-normal);
}

/* Information */
.information-section {
  padding: var(--space-xl) var(--page-padding) var(--space-2xl);
}

.section-inner {
  max-width: var(--page-width);
  margin: 0 auto;
}

.info-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
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

.description-block,
.tags-block {
  display: grid;
  gap: var(--space-xs);
  margin-top: var(--space-base);
  padding: var(--space-base);
  border: 1px solid var(--border);
  border-radius: var(--card-radius);
  background: color-mix(in srgb, var(--bg-surface) 72%, transparent);
}

.description-block p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.tag-chip {
  display: inline-block;
  font-size: 12px;
  color: var(--text-primary);
  background: var(--accent-bg);
  padding: 3px 10px;
  border-radius: var(--radius-sm);
  margin: 0;
}

.secondary-info {
  margin-top: var(--space-base);
  border-top: 1px solid var(--border);
}

.secondary-info summary {
  padding: var(--space-base) 0;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  list-style: none;
}

.secondary-info summary::-webkit-details-marker { display: none; }
.secondary-info summary::after { content: '＋'; float: right; color: var(--accent); }
.secondary-info[open] summary::after { content: '－'; }

.secondary-info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-base);
  padding-bottom: var(--space-base);
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

.catalog-header__action {
  margin-left: auto;
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
  border: 3px solid var(--color-progress-track);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Responsive */
@media (max-width: 1024px) {
  .info-grid,
  .secondary-info-grid {
    grid-template-columns: 1fr;
  }
}

/* ==============================
   移动端布局（由 useInteractionMode 驱动；桌面端无 is-mobile 类，完全不受影响）
   ============================== */

/* Hero：PC 左右两栏 → 移动端纵向堆叠（stretch 让 hero-info 撑满，主按钮才能真正全宽） */
.comic-detail-page.is-mobile :deep(.hero-content) {
  flex-direction: column;
  align-items: stretch;
  gap: var(--space-lg);
}

/* 封面居中，宽度不超过 50% 且最大 220px */
.comic-detail-page.is-mobile :deep(.hero-poster) {
  width: min(50%, 220px);
  margin: 0 auto;
}

/* 标题与进度信息居中展示 */
.comic-detail-page.is-mobile :deep(.hero-info) {
  max-width: 100%;
  align-items: center;
  text-align: center;
}

/* 长标题最多两行，超出省略 */
.comic-detail-page.is-mobile :deep(.hero-title) {
  font-size: 24px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

/* 单一主操作按钮：全宽、触控高度 ≥ 48px（次按钮已在 secondaryAction 中按 mode 置空） */
.comic-detail-page.is-mobile :deep(.hero-actions) {
  width: 100%;
}

.comic-detail-page.is-mobile :deep(.hero-btn--primary) {
  width: 100%;
  min-height: 48px;
  font-size: 16px;
}

/* 信息网格单列：与上方平板媒体查询结果一致，两机制不冲突 */
.comic-detail-page.is-mobile .info-grid {
  grid-template-columns: 1fr;
}

/* 目录区块占满全宽（CatalogTree 内部已自带虚拟滚动与高度约束） */
.comic-detail-page.is-mobile .catalog-section .section-inner {
  width: 100%;
  max-width: none;
}
</style>
