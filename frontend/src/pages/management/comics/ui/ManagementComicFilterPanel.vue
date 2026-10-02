<script setup lang="ts">
import type { CategoryDTO } from '@/entities/category'
import type { TagDTO } from '@/entities/tag'
import { AppButton } from '@/shared/ui/button'
import { HQ_OPTIONS, LQ_OPTIONS, SORT_OPTIONS, SOURCE_OPTIONS, STATUS_OPTIONS } from '../model/management-comic-filters'
import type { ManagementComicFilters } from '../model/management-comic-filters'

defineProps<{
  categories: readonly CategoryDTO[]
  tags: readonly TagDTO[]
  activeConditions: readonly string[]
}>()
const filters = defineModel<ManagementComicFilters>('filters', { required: true })
const emit = defineEmits<{ apply: []; keyword: []; reset: [] }>()
</script>

<template>
  <section class="comic-filter-panel filter-controls" aria-label="管理漫画筛选">
    <div class="filter-panel-heading">
      <div><strong>筛选漫画</strong><span>定位需要管理的内容</span></div>
      <AppButton variant="text" size="sm" @click="emit('reset')">重置筛选</AppButton>
    </div>
    <div class="filter-toolbar filter-primary">
      <label class="filter-field filter-field--keyword">
        <span>关键词</span>
        <el-input
          v-model="filters.keyword"
          placeholder="标题、作者或标签"
          aria-label="管理搜索关键词"
          clearable
          class="filter-input"
          @input="emit('keyword')"
          @keyup.enter="emit('apply')"
          @clear="emit('apply')"
        />
      </label>
      <label class="filter-field">
        <span>分类</span>
        <el-select
          v-model="filters.category"
          placeholder="全部分类"
          aria-label="管理分类"
          clearable
          class="filter-select"
          @change="emit('apply')"
        >
          <el-option label="未分类" value="_NONE" />
          <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.name" />
        </el-select>
      </label>
      <label class="filter-field">
        <span>生命周期</span>
        <el-select
          v-model="filters.status"
          placeholder="全部状态"
          aria-label="管理生命周期"
          clearable
          class="filter-select"
          @change="emit('apply')"
        >
          <el-option v-for="option in STATUS_OPTIONS" :key="option.value" v-bind="option" />
        </el-select>
      </label>
      <label class="filter-field">
        <span>来源</span>
        <el-select
          v-model="filters.sourceType"
          placeholder="全部来源"
          aria-label="管理来源"
          clearable
          class="filter-select"
          @change="emit('apply')"
        >
          <el-option v-for="option in SOURCE_OPTIONS" :key="option.value" v-bind="option" />
        </el-select>
      </label>
    </div>
    <div class="filter-toolbar filter-secondary">
      <label class="filter-field">
        <span>HQ 原文件</span>
        <el-select
          v-model="filters.hqStatus"
          placeholder="不限 HQ"
          aria-label="HQ 文件状态"
          clearable
          class="filter-select"
          @change="emit('apply')"
        >
          <el-option v-for="option in HQ_OPTIONS" :key="option.value" v-bind="option" />
        </el-select>
      </label>
      <label class="filter-field">
        <span>LQ 图片</span>
        <el-select
          v-model="filters.lqStatus"
          placeholder="不限 LQ"
          aria-label="LQ 图片状态"
          clearable
          class="filter-select"
          @change="emit('apply')"
        >
          <el-option v-for="option in LQ_OPTIONS" :key="option.value" v-bind="option" />
        </el-select>
      </label>
      <label class="filter-field filter-field--tags">
        <span>标签</span>
        <el-select
          v-model="filters.tags"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          placeholder="不限标签"
          aria-label="管理标签"
          clearable
          class="filter-select--wide"
          @change="emit('apply')"
        >
          <el-option label="无标签" value="_NONE" />
          <el-option v-for="tag in tags" :key="tag.id" :label="tag.name" :value="tag.name" />
        </el-select>
      </label>
      <label v-if="filters.tags.length && !filters.tags.includes('_NONE')" class="filter-field">
        <span>标签匹配</span>
        <el-select
          v-model="filters.tagMode"
          aria-label="管理标签匹配"
          class="filter-select--mini"
          @change="emit('apply')"
        >
          <el-option label="任一" value="OR" /><el-option label="全部" value="AND" /><el-option
            label="排除"
            value="NOT"
          />
        </el-select>
      </label>
      <label class="filter-field">
        <span>排序</span>
        <el-select v-model="filters.sort" aria-label="管理排序" class="filter-select" @change="emit('apply')">
          <el-option v-for="option in SORT_OPTIONS" :key="option.value" v-bind="option" />
        </el-select>
      </label>
      <label class="filter-field">
        <span>顺序</span>
        <el-select
          v-model="filters.order"
          aria-label="管理排序顺序"
          class="filter-select--mini"
          @change="emit('apply')"
        >
          <el-option label="倒序" value="desc" /><el-option label="正序" value="asc" />
        </el-select>
      </label>
    </div>
    <p class="filter-help">
      条件之间同时匹配。HQ 统计活动媒体，LQ
      只统计活动图片；“全部可用”要求至少有一个适用文件，“部分可用”要求同时存在可用与不可用文件。
    </p>
    <div class="filter-summary" aria-live="polite">
      <span v-if="!activeConditions.length" class="filter-summary-empty">全部漫画 · 全部生命周期</span>
      <template v-else
        ><span class="filter-summary-count">{{ activeConditions.length }} 项条件</span>
        <span v-for="condition in activeConditions" :key="condition" class="filter-condition">{{ condition }}</span>
      </template>
    </div>
  </section>
</template>

<style scoped>
.comic-filter-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  margin-bottom: var(--space-lg);
  padding: var(--space-base);
}
.filter-panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  margin-bottom: var(--space-base);
}
.filter-panel-heading > div {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--space-md);
}
.filter-panel-heading strong {
  color: var(--text-primary);
  font-size: var(--text-md);
}
.filter-panel-heading span,
.filter-field > span {
  color: var(--text-muted);
  font-size: var(--text-xs);
}
.filter-toolbar {
  display: grid;
  gap: var(--space-md);
}
.filter-primary {
  grid-template-columns: minmax(220px, 2fr) repeat(3, minmax(140px, 1fr));
}
.filter-secondary {
  grid-template-columns: repeat(2, minmax(170px, 1fr)) minmax(180px, 1.5fr) repeat(3, minmax(100px, 1fr));
  margin-top: var(--space-md);
}
.filter-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  min-width: 0;
}
.filter-field :deep(.el-select) {
  width: 100%;
}
.filter-help {
  color: var(--text-muted);
  font-size: var(--text-xs);
  line-height: 1.6;
  margin: var(--space-base) 0 var(--space-sm);
}
.filter-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm);
  border-top: 1px solid var(--border);
  padding-top: var(--space-sm);
  font-size: var(--text-xs);
}
.filter-summary-empty,
.filter-summary-count {
  color: var(--text-muted);
}
.filter-condition {
  color: var(--text-primary);
  background: var(--control-bg-hover);
  border: 1px solid var(--control-border);
  border-radius: var(--control-radius);
  padding: var(--space-xs) var(--space-sm);
  overflow-wrap: anywhere;
}
@media (max-width: 1200px) {
  .filter-secondary {
    grid-template-columns: repeat(3, minmax(150px, 1fr));
  }
}
@media (max-width: 760px) {
  .filter-primary,
  .filter-secondary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .filter-field--keyword,
  .filter-field--tags {
    grid-column: 1 / -1;
  }
}
</style>
