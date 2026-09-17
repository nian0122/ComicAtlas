<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElSelect, ElOption, ElInput } from 'element-plus'
import type { FilterState, SortState } from '@/features/storage'
import { useCategoryStore } from '@/features/category'
import { useTagStore } from '@/features/tag'

const props = defineProps<{
  filter: FilterState
  sort: SortState
}>()

const router = useRouter()

const emit = defineEmits<{
  'update:filter': [value: FilterState]
  'update:sort': [value: SortState]
}>()

const hasActiveFilters = computed(
  () =>
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
onMounted(() => {
  void categoryStore.fetchList()
  void tagStore.fetchList()
})
</script>

<template>
  <div>
    <section class="action-section">
      <h2 class="section-title">操作</h2>
      <div class="action-list">
        <AppButton class="action-btn primary" @click="goToTaskCenter">在任务中心中恢复</AppButton>
        <AppButton class="action-btn danger" disabled>清理未引用文件</AppButton>
      </div>
    </section>

    <section class="action-section">
      <h2 class="section-title">存储优化</h2>
      <div class="filter-bar">
        <el-select
          :model-value="props.filter.hqStatus"
          placeholder="HQ 状态"
          class="filter-select"
          @update:model-value="setFilter({ hqStatus: $event })"
        >
          <el-option label="全部 HQ" value="ALL" />
          <el-option label="还有 HQ" value="HAS_HQ" />
          <el-option label="含 HQ 已删" value="NO_HQ" />
        </el-select>
        <el-select
          :model-value="props.filter.lqStatus"
          placeholder="LQ 状态"
          class="filter-select"
          @update:model-value="setFilter({ lqStatus: $event })"
        >
          <el-option label="全部 LQ" value="ALL" />
          <el-option label="需要生成" value="NEEDS_LQ" />
          <el-option label="LQ 就绪" value="READY" />
        </el-select>
        <el-select
          :model-value="props.filter.category"
          placeholder="分类"
          class="filter-select"
          clearable
          @update:model-value="setFilter({ category: $event })"
        >
          <el-option label="未分类" value="_NONE" />
          <el-option
            v-for="category in categoryStore.list"
            :key="category.id"
            :label="category.name"
            :value="category.name"
          />
        </el-select>
        <el-select
          :model-value="props.filter.tag"
          placeholder="标签"
          class="filter-select"
          clearable
          @update:model-value="setFilter({ tag: $event })"
        >
          <el-option label="无标签" value="_NONE" />
          <el-option v-for="tag in tagStore.list" :key="tag.id" :label="tag.name" :value="tag.name" />
        </el-select>
        <el-select
          :model-value="props.sort.field"
          placeholder="排序"
          class="filter-select"
          @update:model-value="setSort({ field: $event })"
        >
          <el-option label="HQ 大小" value="hqSize" />
          <el-option label="LQ 大小" value="lqSize" />
          <el-option label="总大小" value="totalSize" />
          <el-option label="标题" value="title" />
        </el-select>
        <el-select
          :model-value="props.sort.order"
          class="filter-select--mini"
          @update:model-value="setSort({ order: $event })"
        >
          <el-option label="降序" value="desc" />
          <el-option label="升序" value="asc" />
        </el-select>
        <el-input
          :model-value="props.filter.keyword"
          placeholder="搜索标题"
          clearable
          class="filter-input"
          @update:model-value="setFilter({ keyword: $event })"
        />
        <AppButton v-if="hasActiveFilters" class="filter-reset" variant="text" @click="clearFilters"
          >清空筛选</AppButton
        >
      </div>
    </section>
  </div>
</template>

<style scoped>
.action-section {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
  margin-bottom: var(--space-xl);
}
.section-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-base);
}
.action-list {
  display: flex;
  gap: var(--space-base);
  flex-wrap: wrap;
}
.action-btn {
  padding: 8px 16px;
  background: var(--bg-primary);
  color: var(--text-primary);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
}
.action-btn:hover:not(:disabled) {
  background: var(--bg-secondary);
}
.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.action-btn.danger {
  color: var(--danger);
  border-color: var(--danger);
}
.action-btn.primary {
  color: var(--accent);
  border-color: var(--accent);
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-bottom: var(--space-base);
  align-items: center;
}
.filter-select,
.filter-input {
  flex: 1 1 156px;
  min-width: 140px;
}
.filter-select--mini {
  flex: 0 1 112px;
  min-width: 104px;
}
.filter-input {
  flex-basis: 220px;
}
.filter-reset {
  flex: 0 0 auto;
  padding-inline: 8px;
  color: var(--text-secondary);
}
.filter-reset:hover {
  color: var(--accent);
  background: var(--accent-bg);
}
@media (max-width: 768px) {
  .filter-bar {
    display: flex;
    align-items: stretch;
  }
  .filter-bar > .filter-select.el-select,
  .filter-bar > .filter-select--mini.el-select,
  .filter-bar > .filter-input.el-input {
    flex-basis: 100%;
    width: 100%;
  }
}

.filter-bar {
  --el-border-color: var(--border);
  --el-border-color-hover: var(--border-strong);
  --el-border-color-light: var(--border);
  --el-border-color-lighter: var(--border);
}
.filter-bar :deep(.el-select__wrapper),
.filter-bar :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--border) inset;
}
.filter-bar :deep(.el-select__wrapper:hover),
.filter-bar :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--border-strong) inset;
}
.filter-bar :deep(.el-select__wrapper.is-focused),
.filter-bar :deep(.el-input__wrapper.is-focus) {
  box-shadow:
    inset 0 0 0 1px var(--accent),
    var(--shadow-sm) !important;
}
</style>
