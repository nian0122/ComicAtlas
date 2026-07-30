<template>
  <div class="mobile-detail">
    <section class="cover-stage" aria-label="漫画封面">
      <div class="cover-backdrop" :style="{ backgroundImage: `url(${comic.coverUrl})` }" />
      <img class="cover-poster" :src="comic.coverUrl" :alt="comic.title">
    </section>

    <div class="detail-body">
      <header class="title-block">
        <h1>{{ comic.title }}</h1>
        <div class="metadata">
          <span>{{ year }}</span>
          <span>{{ totalChapters }} 话</span>
          <span>{{ comic.pageCount }} 页</span>
        </div>
        <div v-if="comic.tags.length" class="tags" aria-label="漫画标签">
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

      <section class="summary">
        <h2>剧情摘要</h2>
        <p>{{ comic.description || '暂无简介' }}</p>
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
          <strong>{{ fileSize }}</strong>
        </div>
        <div>
          <span>来源</span>
          <strong>{{ comic.sourceType || '-' }}</strong>
        </div>
      </section>

      <section class="catalog">
        <div class="catalog-heading">
          <h2>目录</h2>
          <span>{{ totalChapters }} 话</span>
        </div>
        <CatalogTree
          v-if="catalogTree.length"
          :tree="catalogTree"
          :active-chapter-id="comic.lastReadChapterId"
          @select="$emit('select', $event)"
        />
        <p v-else class="empty-catalog">暂无章节</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { VideoPlay } from '@element-plus/icons-vue'
import CatalogTree from '@/components/reading/comic/CatalogTree.vue'
import type { CatalogNode, ComicDetailVO } from '@/types'

interface Props {
  comic: ComicDetailVO
  catalogTree: CatalogNode[]
  totalChapters: number
  progressText: string
  progressScale: number
  readLabel: string
  canRead: boolean
}

const props = defineProps<Props>()

defineEmits<{
  read: []
  select: [chapterId: number]
}>()

const year = computed(() => props.comic.createdAt?.slice(0, 4) || '未知年份')

const fileSize = computed(() => {
  if (!props.comic.fileSize) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = props.comic.fileSize
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
  padding-bottom: var(--space-10);
  background: var(--mobile-canvas);
  color: var(--text-primary);
}

.cover-stage {
  position: relative;
  display: grid;
  place-items: end center;
  min-height: var(--mobile-detail-stage-height);
  padding: calc(var(--mobile-nav-height) + var(--space-5)) var(--space-5) var(--space-6);
  overflow: hidden;
}

.cover-backdrop {
  position: absolute;
  inset: 0;
  background-position: center 20%;
  background-size: cover;
  filter: blur(var(--mobile-detail-backdrop-blur)) brightness(0.42) saturate(0.85);
  transform: scale(var(--mobile-detail-backdrop-scale));
}

.cover-stage::after {
  position: absolute;
  inset: 0;
  content: "";
  background: var(--mobile-detail-stage-scrim);
}

.cover-poster {
  position: relative;
  z-index: 1;
  width: var(--mobile-detail-poster-width);
  aspect-ratio: 2 / 3;
  border-radius: var(--radius-md);
  object-fit: cover;
  box-shadow: var(--shadow-lg);
}

.detail-body {
  position: relative;
  z-index: 2;
  padding: 0 var(--mobile-page-gutter);
}

.title-block h1 {
  margin: 0;
  color: var(--accent);
  font-size: var(--mobile-detail-title-size);
  font-weight: 800;
  letter-spacing: -0.04em;
  line-height: 1.08;
  text-align: center;
}

.metadata {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-4);
  margin-top: var(--space-3);
  color: var(--text-secondary);
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
  border: 1px solid var(--color-line-strong);
  border-radius: var(--radius-pill);
  color: var(--text-secondary);
  font-size: 12px;
}

.read-button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  width: 100%;
  min-height: var(--mobile-detail-action-height);
  margin-top: var(--mobile-detail-action-gap);
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: var(--color-on-brand);
  font: inherit;
  font-weight: 800;
}

.read-button:disabled {
  opacity: var(--disabled-opacity);
}

.progress-panel,
.facts,
.catalog {
  margin-top: var(--mobile-detail-section-gap);
}

.summary {
  min-height: var(--mobile-detail-summary-min-height);
  margin-top: var(--mobile-detail-summary-gap);
}

.progress-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
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
  margin-top: var(--space-1);
  color: var(--text-muted);
  font-size: 12px;
}

.progress-heading strong {
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
  gap: var(--space-5) var(--space-4);
  padding-block: var(--space-6);
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
</style>
