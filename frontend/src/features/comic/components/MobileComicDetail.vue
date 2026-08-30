<template>
  <div class="mobile-detail">
    <section class="cover-stage" aria-label="漫画封面">
      <div class="cover-backdrop" :style="{ backgroundImage: `url(${comic.coverUrl})` }" />
      <div v-if="!comic.coverUrl" class="cover-placeholder" aria-label="暂无封面">
        <el-icon :size="64"><VideoPlay /></el-icon>
      </div>
      <div class="cover-content">
        <header class="title-block">
          <h1>{{ comic.title }}</h1>
          <div class="metadata">
            <span>{{ year }}</span>
            <span>{{ totalChapters }} 话</span>
            <span>{{ comic.pageCount }} 页</span>
          </div>
          <div v-if="comic.tags && comic.tags.length" class="tags" aria-label="漫画标签">
            <span v-for="tag in comic.tags" :key="tag.name">{{ tag.name }}</span>
          </div>
        </header>
        <button
          type="button"
          class="read-button"
          :disabled="!canRead"
          @click="$emit('read')"
        >
          <el-icon :size="21"><VideoPlay /></el-icon>
          {{ readLabel }}
        </button>
      </div>
    </section>

    <div class="detail-body">
      <section class="progress-panel" aria-labelledby="mobile-progress-title">
        <div class="progress-heading">
          <div>
            <p id="mobile-progress-title">阅读进度</p>
            <span>{{ progressText }}</span>
          </div>
          <strong>{{ comic.progressPercent || 0 }}%</strong>
        </div>
        <div class="progress-track">
          <span :style="{ transform: `scaleX(${progressScale})` }" />
        </div>
      </section>

      <section v-if="comic.description" class="summary">
        <h2>剧情摘要</h2>
        <p>{{ comic.description }}</p>
      </section>

      <section class="facts" aria-label="漫画信息">
        <div>
          <span>作者</span>
          <strong>{{ comic.author || '未知作者' }}</strong>
        </div>
        <div>
          <span>分类</span>
          <strong>{{ comic.categoryName || '未分类' }}</strong>
        </div>
        <div>
          <span>文件大小</span>
          <strong>{{ hqSize }}</strong>
        </div>
      </section>

      <section class="catalog">
        <div class="catalog-heading">
          <h2>目录</h2>
          <span>{{ isSearching ? `找到 ${resultCount} 个章节` : `${totalChapters} 话` }}</span>
        </div>
        <ChapterSearchBox
          :model-value="searchKeyword"
          @update:model-value="$emit('update:searchKeyword', $event)"
        />
        <CatalogTree
          v-if="filteredCatalogTree.length"
          :tree="filteredCatalogTree"
          :active-chapter-id="comic.lastReadChapterId"
          :highlight-keyword="searchKeyword"
          :expanded-node-paths="expandedNodePaths"
          @select="$emit('select', $event)"
        />
        <div v-else-if="isSearching" class="empty-catalog">
          <p>没有找到匹配章节</p>
          <button type="button" class="clear-search-button" @click="$emit('clear-search')">清空搜索</button>
        </div>
        <p v-else class="empty-catalog">暂无章节</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { VideoPlay } from '@element-plus/icons-vue'
import CatalogTree from './CatalogTree.vue'
import { ChapterSearchBox } from '@/features/chapter-search'
import type { CatalogNode, ComicDetailVO } from '@/entities/comic/types'

interface Props {
  comic: ComicDetailVO
  catalogTree: CatalogNode[]
  totalChapters: number
  progressText: string
  progressScale: number
  readLabel: string
  canRead: boolean
  searchKeyword: string
  filteredCatalogTree: CatalogNode[]
  isSearching: boolean
  resultCount: number
  expandedNodePaths: readonly string[]
}

const props = defineProps<Props>()

defineEmits<{
  read: []
  select: [chapterId: number]
  'update:searchKeyword': [keyword: string]
  'clear-search': []
}>()

const year = computed(() => props.comic.createdAt?.slice(0, 4) || '未知年份')

const hqSize = computed(() => {
  if (!props.comic.hqSize) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = props.comic.hqSize
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  return `${value.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`
})
</script>

<style scoped>
.mobile-detail {
  min-height: 100dvh;
  padding-bottom: calc(
    var(--mobile-tabbar-height) + var(--space-8) + env(safe-area-inset-bottom)
  );
  background: var(--mobile-canvas);
  color: var(--text-primary);
}

.cover-stage {
  position: relative;
  display: flex;
  align-items: flex-end;
  min-height: clamp(420px, 118vw, 580px);
  padding: calc(var(--mobile-nav-height) + var(--space-5)) var(--mobile-page-gutter) var(--space-6);
  overflow: hidden;
}

.cover-backdrop {
  position: absolute;
  inset: 0;
  background-position: center top;
  background-color: #050505;
  background-repeat: no-repeat;
  background-size: 100% auto;
  filter: brightness(0.68) saturate(0.92);
  transform: scale(1.02);
}

.cover-stage::after {
  position: absolute;
  inset: 0;
  content: "";
  background:
    linear-gradient(
      180deg,
      rgb(0 0 0 / 0%) 34%,
      rgb(0 0 0 / 8%) 49%,
      rgb(0 0 0 / 58%) 74%,
      var(--mobile-canvas) 100%
    );
}

.cover-poster {
  display: none;
}

.cover-content {
  position: relative;
  z-index: 1;
  width: 100%;
}

.cover-placeholder {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: min(62vw, 280px);
  aspect-ratio: 2 / 3;
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  color: var(--accent);
  box-shadow: var(--shadow-lg);
}

.detail-body {
  position: relative;
  z-index: 2;
  padding: 0 var(--mobile-page-gutter);
}

.title-block h1 {
  margin: 0;
  display: -webkit-box;
  overflow: hidden;
  color: var(--text-primary);
  font-size: clamp(24px, 7vw, 34px);
  font-weight: 800;
  letter-spacing: -0.04em;
  line-height: 1.08;
  text-align: center;
  text-shadow: 0 2px 18px rgb(0 0 0 / 70%);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.metadata {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-4);
  margin-top: var(--space-3);
  color: rgb(255 255 255 / 82%);
  font-size: var(--text-sm);
  font-weight: 600;
}

.tags {
  display: flex;
  justify-content: center;
  gap: var(--space-2);
  margin-top: var(--space-4);
  overflow-x: auto;
  scrollbar-width: none;
}

.tags span {
  flex: 0 0 auto;
  padding: 6px 12px;
  border: 1px solid rgb(255 255 255 / 24%);
  border-radius: var(--radius-pill);
  background: rgb(0 0 0 / 18%);
  color: rgb(255 255 255 / 86%);
  font-size: 12px;
}

.read-button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  width: 100%;
  min-height: 48px;
  margin-top: var(--space-5);
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: var(--color-on-brand);
  font: inherit;
  font-weight: 800;
  box-shadow: 0 10px 24px rgb(0 0 0 / 28%);
}

.read-button:disabled {
  opacity: var(--disabled-opacity);
}

.progress-panel,
.facts,
.catalog {
  margin-top: var(--space-6);
}

.progress-panel {
  padding-bottom: var(--space-5);
  border-bottom: 1px solid var(--color-border-faint);
}

.summary {
  min-height: 0;
  margin-top: var(--space-6);
}

.progress-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
}

.progress-heading > div {
  min-width: 0;
  flex: 1;
}

.progress-heading p,
.summary h2,
.catalog h2 {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: 800;
}

.progress-heading span {
  display: block;
  max-width: 100%;
  margin-top: var(--space-1);
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.progress-heading strong {
  flex: 0 0 auto;
  color: var(--accent);
}

.progress-track {
  height: 4px;
  margin-top: var(--space-3);
  overflow: hidden;
  background: var(--color-progress-track);
}

.progress-track span {
  display: block;
  width: 100%;
  height: 100%;
  background: var(--accent);
  transform-origin: left;
}

.summary p {
  margin: var(--space-3) 0 0;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  line-height: 1.75;
}

.facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4) var(--space-4);
  padding-block: var(--space-5);
  border-block: 1px solid var(--color-border-faint);
}

.facts div {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.facts span,
.catalog-heading span {
  color: var(--text-muted);
  font-size: 12px;
}

.facts strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: var(--text-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.catalog-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.empty-catalog {
  color: var(--text-muted);
  text-align: center;
}

.catalog :deep(.chapter-search-box) {
  width: 100%;
  margin-bottom: var(--space-4);
}

.clear-search-button {
  border: 0;
  padding: 7px 12px;
  border-radius: var(--radius-sm);
  background: var(--accent-bg);
  color: var(--accent);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}
</style>
