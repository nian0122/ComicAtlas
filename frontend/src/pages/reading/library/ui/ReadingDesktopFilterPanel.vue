<script setup lang="ts">
import type { CategoryDTO } from '@/entities/category'
import type { TagDTO } from '@/entities/tag'
import { AppButton } from '@/shared/ui/button'
import { READING_SORT_OPTIONS } from '../model/reading-comic-filters'
import type { ReadingComicFilters } from '../model/reading-comic-filters'

const filters = defineModel<ReadingComicFilters>('filters', { required: true })
defineProps<{
  total: number
  categories: readonly CategoryDTO[]
  tags: readonly TagDTO[]
  summary: readonly string[]
}>()
const emit = defineEmits<{ apply: []; keyword: []; reset: []; clearKeyword: []; order: [] }>()
</script>

<template>
  <section class="reading-desktop-filters" aria-label="阅读漫画筛选">
    <div class="reading-filter-heading">
      <h1>
        漫画库 <small aria-label="筛选结果数量">{{ total }} 本</small>
      </h1>
      <span>找到下一本想读的漫画</span>
    </div>
    <div class="reading-search-row">
      <el-input
        v-model="filters.keyword"
        data-library-search
        placeholder="搜索标题、作者或标签"
        aria-label="搜索漫画"
        clearable
        @input="emit('keyword')"
        @keyup.enter="emit('apply')"
        @clear="emit('clearKeyword')"
      />
      <el-select v-model="filters.sort" aria-label="排序方式" class="sort-select" @change="emit('apply')">
        <el-option v-for="option in READING_SORT_OPTIONS" :key="option.value" v-bind="option" />
      </el-select>
      <AppButton
        class="desktop-sort-order"
        :aria-label="filters.order === 'asc' ? '当前正序，点击切换为倒序' : '当前倒序，点击切换为正序'"
        @click="emit('order')"
        >{{ filters.order === 'asc' ? '正序 ↑' : '倒序 ↓' }}</AppButton
      >
    </div>
    <div class="reading-filter-row">
      <div class="category-select">
        <el-select
          v-model="filters.categoryFilter"
          aria-label="漫画分类"
          placeholder="全部分类"
          @change="emit('apply')"
        >
          <el-option label="全部分类" value="" /><el-option label="未分类" value="_NONE" />
          <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.name" />
        </el-select>
      </div>
      <el-select
        v-model="filters.selectedTags"
        multiple
        filterable
        collapse-tags
        collapse-tags-tooltip
        clearable
        aria-label="阅读标签"
        placeholder="选择标签"
        class="tag-select"
        @change="emit('apply')"
      >
        <el-option label="无标签" value="_NONE" />
        <el-option v-for="tag in tags" :key="tag.id" :label="tag.name" :value="tag.name" />
      </el-select>
      <el-select
        v-if="filters.selectedTags.length && !filters.selectedTags.includes('_NONE')"
        v-model="filters.tagMode"
        aria-label="标签匹配方式"
        class="tag-mode-select"
        @change="emit('apply')"
      >
        <el-option label="任一" value="OR" /><el-option label="全部" value="AND" /><el-option
          label="排除"
          value="NOT"
        />
      </el-select>
      <AppButton v-if="summary.length" variant="text" @click="emit('reset')">清除筛选</AppButton>
    </div>
    <div v-if="summary.length" class="reading-filter-summary" aria-label="当前筛选条件" aria-live="polite">
      <span v-for="item in summary" :key="item">{{ item }}</span>
    </div>
  </section>
</template>

<style scoped>
.reading-desktop-filters {
  padding: var(--space-sm) 0 var(--space-base);
}
.reading-filter-heading {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}
.reading-filter-heading h1 {
  color: var(--text-primary);
  font-size: var(--text-section);
  margin: 0;
}
.reading-filter-heading small {
  color: var(--text-muted);
  font-size: var(--text-sm);
  margin-left: var(--space-sm);
  font-weight: 400;
}
.reading-filter-heading > span {
  color: var(--text-muted);
  font-size: var(--text-sm);
}
.reading-search-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px auto;
  gap: var(--space-sm);
}
.reading-filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}
.category-select {
  width: 160px;
}
.tag-select {
  width: 240px;
}
.tag-mode-select {
  width: 100px;
}
.reading-filter-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
  font-size: var(--text-xs);
}
.reading-filter-summary span {
  color: var(--text-secondary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: var(--space-xs) var(--space-sm);
  overflow-wrap: anywhere;
}
</style>
