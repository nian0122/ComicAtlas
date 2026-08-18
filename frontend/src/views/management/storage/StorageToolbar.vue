<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElSelect, ElOption, ElInput, ElButton } from 'element-plus'
import type { FilterState, SortState } from '@/composables/storage/useStorageFilter'
import { useCategoryStore } from '@/stores/management/category'
import { useTagStore } from '@/stores/tag-store'

const props = defineProps<{
  filter: FilterState
  sort: SortState
}>()

const router = useRouter()

const emit = defineEmits<{
  'update:filter': [value: FilterState]
  'update:sort': [value: SortState]
}>()

const hasActiveFilters = computed(() =>
  props.filter.hqStatus !== 'ALL' ||
  props.filter.lqStatus !== 'ALL' ||
  props.filter.keyword.trim().length > 0 ||
  props.filter.category.length > 0 ||
  props.filter.tag.length > 0,
)

function goToTaskCenter() {
  router.push('/manage/tasks')
}

function setFilter(patch: Partial<FilterState>) {
  emit('update:filter', { ...props.filter, ...patch })
}

function clearFilters() {
  emit('update:filter', { hqStatus: 'ALL', lqStatus: 'ALL', keyword: '', category: '', tag: '' })
}

function setSort(patch: Partial<SortState>) {
  emit('update:sort', { ...props.sort, ...patch })
}

const categoryStore = useCategoryStore()
const tagStore = useTagStore()
onMounted(() => { void categoryStore.fetchList(); void tagStore.fetchList() })
</script>

<template>
  <div>
    <section class="action-section">
      <h2 class="section-title">操作</h2>
      <div class="action-list">
        <button class="action-btn primary" @click="goToTaskCenter">在任务中心中恢复</button>
        <button class="action-btn danger" disabled>清理未引用文件</button>
      </div>
    </section>

    <section class="action-section">
      <h2 class="section-title">存储优化</h2>
      <div class="filter-bar">
        <el-select :model-value="props.filter.hqStatus" @update:model-value="setFilter({ hqStatus: $event })" placeholder="HQ 状态" class="filter-select">
          <el-option label="全部" value="ALL" />
          <el-option label="还有 HQ" value="HAS_HQ" />
          <el-option label="HQ 已删" value="NO_HQ" />
        </el-select>
        <el-select :model-value="props.filter.lqStatus" @update:model-value="setFilter({ lqStatus: $event })" placeholder="LQ 状态" class="filter-select">
          <el-option label="全部" value="ALL" />
          <el-option label="需要生成" value="NEEDS_LQ" />
          <el-option label="LQ 就绪" value="READY" />
        </el-select>
        <el-select :model-value="props.filter.category" @update:model-value="setFilter({ category: $event })" placeholder="分类" class="filter-select" clearable>
          <el-option label="未分类" value="_NONE" />
          <el-option v-for="category in categoryStore.list" :key="category.id" :label="category.name" :value="category.name" />
        </el-select>
        <el-select :model-value="props.filter.tag" @update:model-value="setFilter({ tag: $event })" placeholder="标签" class="filter-select" clearable>
          <el-option label="无标签" value="_NONE" />
          <el-option v-for="tag in tagStore.list" :key="tag.id" :label="tag.name" :value="tag.name" />
        </el-select>
        <el-select :model-value="props.sort.field" @update:model-value="setSort({ field: $event })" placeholder="排序" class="filter-select">
          <el-option label="HQ 大小" value="hqSize" />
          <el-option label="LQ 大小" value="lqSize" />
          <el-option label="总大小" value="totalSize" />
          <el-option label="标题" value="title" />
        </el-select>
        <el-select :model-value="props.sort.order" @update:model-value="setSort({ order: $event })" class="filter-select--mini">
          <el-option label="降序" value="desc" />
          <el-option label="升序" value="asc" />
        </el-select>
        <el-input :model-value="props.filter.keyword" @update:model-value="setFilter({ keyword: $event })" placeholder="搜索标题" clearable class="filter-input" />
        <el-button v-if="hasActiveFilters" class="filter-reset" text @click="clearFilters">清空筛选</el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.action-section { background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-lg); margin-bottom: var(--space-xl); }
.section-title { font-size: 14px; font-weight: 700; color: var(--text-primary); margin: 0 0 var(--space-base); }
.action-list { display: flex; gap: var(--space-base); flex-wrap: wrap; }
.action-btn { padding: 8px 16px; background: var(--bg-primary); color: var(--text-primary); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); font-size: 13px; font-weight: 600; cursor: pointer; transition: all var(--transition-fast); }
.action-btn:hover:not(:disabled) { background: var(--bg-secondary); }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.danger { color: var(--danger); border-color: var(--danger); }
.action-btn.primary { color: var(--accent); border-color: var(--accent); }
.filter-bar { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-sm); margin-bottom: var(--space-base); align-items: center; }
.filter-select, .filter-select--mini, .filter-input { width: 100%; min-width: 0; }
.filter-input { grid-column: auto; }
.filter-reset { padding-inline: 8px; color: var(--text-secondary); }
.filter-reset:hover { color: var(--accent); background: var(--accent-bg); }
@media (max-width: 768px) { .filter-bar { display: flex; flex-direction: column; align-items: stretch; } .filter-select, .filter-select--mini, .filter-input { width: 100% !important; } }
</style>
