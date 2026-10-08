<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { ManagementPanel } from '@/shared/ui/management-panel'
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElSelect, ElOption, ElInput } from 'element-plus'
import type { FilterState, SortState } from '@/features/storage'
import { useCategoryStore } from '@/entities/category'
import { useTagStore } from '@/entities/tag'

const props = defineProps<{
  filter: FilterState
  sort: SortState
  total: number
  loading: boolean
}>()
const router = useRouter()
const categoryStore = useCategoryStore()
const tagStore = useTagStore()
const emit = defineEmits<{
  'update:filter': [value: FilterState]
  'update:sort': [value: SortState]
}>()

const hqOptions = [
  { label: '全部 HQ', value: 'ALL' },
  { label: '还有 HQ', value: 'HAS_HQ' },
  { label: '含 HQ 已删', value: 'NO_HQ' },
] as const
const lqOptions = [
  { label: '全部 LQ', value: 'ALL' },
  { label: '需要生成', value: 'NEEDS_LQ' },
  { label: 'LQ 就绪', value: 'READY' },
] as const
const sortOptions = [
  { label: '总大小', value: 'totalSize' },
  { label: 'HQ 大小', value: 'hqSize' },
  { label: 'LQ 大小', value: 'lqSize' },
  { label: '标题', value: 'title' },
] as const

const activeConditions = computed(() => {
  const conditions: { key: keyof FilterState; label: string }[] = []
  if (props.filter.keyword.trim()) conditions.push({ key: 'keyword', label: `标题：${props.filter.keyword.trim()}` })
  if (props.filter.category)
    conditions.push({
      key: 'category',
      label: props.filter.category === '_NONE' ? '未分类' : `分类：${props.filter.category}`,
    })
  if (props.filter.tag)
    conditions.push({ key: 'tag', label: props.filter.tag === '_NONE' ? '无标签' : `标签：${props.filter.tag}` })
  if (props.filter.hqStatus !== 'ALL')
    conditions.push({
      key: 'hqStatus',
      label: hqOptions.find((option) => option.value === props.filter.hqStatus)?.label ?? 'HQ 状态',
    })
  if (props.filter.lqStatus !== 'ALL')
    conditions.push({
      key: 'lqStatus',
      label: lqOptions.find((option) => option.value === props.filter.lqStatus)?.label ?? 'LQ 状态',
    })
  return conditions
})

function setFilter(patch: Partial<FilterState>) {
  emit('update:filter', { ...props.filter, ...patch })
}
function clearCondition(key: keyof FilterState) {
  if (key === 'hqStatus') setFilter({ hqStatus: 'ALL' })
  else if (key === 'lqStatus') setFilter({ lqStatus: 'ALL' })
  else setFilter({ [key]: '' })
}
function clearFilters() {
  emit('update:filter', { hqStatus: 'ALL', lqStatus: 'ALL', keyword: '', category: '', tag: '' })
}
function setSort(patch: Partial<SortState>) {
  emit('update:sort', { ...props.sort, ...patch })
}

onMounted(() => {
  void categoryStore.fetchList()
  void tagStore.fetchList()
})
</script>

<template>
  <ManagementPanel class="storage-filter-panel filter-controls" aria-label="存储记录筛选">
    <div class="filter-heading">
      <div class="filter-heading-copy">
        <h2>漫画存储记录</h2>
        <span class="filter-result" aria-live="polite">
          <span v-if="loading">正在筛选…</span>
          <span v-else
            >匹配 <strong>{{ total }}</strong> 本漫画</span
          >
        </span>
      </div>
      <AppButton variant="text" size="sm" @click="router.push('/manage/tasks')">前往任务中心</AppButton>
    </div>
    <div class="filter-grid">
      <label class="filter-field filter-field--keyword">
        <span>标题关键词</span>
        <el-input
          :model-value="filter.keyword"
          placeholder="搜索标题"
          aria-label="存储标题关键词"
          clearable
          @update:model-value="setFilter({ keyword: $event })"
        />
      </label>
      <label class="filter-field">
        <span>分类</span>
        <el-select
          :model-value="filter.category"
          placeholder="全部分类"
          aria-label="存储分类"
          clearable
          filterable
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
      </label>
      <label class="filter-field">
        <span>标签</span>
        <el-select
          :model-value="filter.tag"
          placeholder="全部标签"
          aria-label="存储标签"
          clearable
          filterable
          @update:model-value="setFilter({ tag: $event })"
        >
          <el-option label="无标签" value="_NONE" />
          <el-option v-for="tag in tagStore.list" :key="tag.id" :label="tag.name" :value="tag.name" />
        </el-select>
      </label>

      <label class="filter-field">
        <span>HQ 原文件</span>
        <el-select
          :model-value="filter.hqStatus"
          aria-label="存储 HQ 状态"
          @update:model-value="setFilter({ hqStatus: $event })"
        >
          <el-option v-for="option in hqOptions" :key="option.value" v-bind="option" />
        </el-select>
      </label>
      <label class="filter-field">
        <span>LQ 衍生文件</span>
        <el-select
          :model-value="filter.lqStatus"
          aria-label="存储 LQ 状态"
          @update:model-value="setFilter({ lqStatus: $event })"
        >
          <el-option v-for="option in lqOptions" :key="option.value" v-bind="option" />
        </el-select>
      </label>
      <label class="filter-field filter-field--sort">
        <span>排序依据</span>
        <el-select :model-value="sort.field" aria-label="存储排序依据" @update:model-value="setSort({ field: $event })">
          <el-option v-for="option in sortOptions" :key="option.value" v-bind="option" />
        </el-select>
      </label>
      <label class="filter-field">
        <span>排列顺序</span>
        <el-select :model-value="sort.order" aria-label="存储排列顺序" @update:model-value="setSort({ order: $event })">
          <el-option label="降序" value="desc" /><el-option label="升序" value="asc" />
        </el-select>
      </label>
    </div>
    <div v-if="activeConditions.length" class="filter-conditions" aria-label="当前存储筛选条件">
      <AppButton
        v-for="condition in activeConditions"
        :key="condition.key"
        size="sm"
        class="filter-condition"
        :aria-label="`清除${condition.label}`"
        @click="clearCondition(condition.key)"
      >
        <span class="filter-condition-label">{{ condition.label }}</span
        ><span class="filter-condition-close" aria-hidden="true">×</span>
      </AppButton>
      <AppButton variant="text" size="sm" @click="clearFilters">清空筛选</AppButton>
    </div>
  </ManagementPanel>
</template>
<style scoped>
.storage-filter-panel {
  --filter-label-size: var(--text-sm);
  --filter-label-color: var(--text-secondary);
}
.filter-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}
.filter-heading-copy {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-3);
  min-width: 0;
}
.filter-heading h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: var(--text-md);
}
.filter-result {
  color: var(--text-secondary);
  font-size: var(--text-sm);
}
.filter-result strong {
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-3) var(--space-4);
}
.filter-field--keyword {
  grid-column: span 2;
}
.filter-conditions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}
.filter-condition {
  max-width: 100%;
}
.filter-condition :deep(.app-button__content) {
  min-width: 0;
}
.filter-condition-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.filter-condition-close {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: var(--text-md);
}
@media (max-width: 1100px) {
  .filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .filter-field--keyword {
    grid-column: 1 / -1;
  }
}
@media (max-width: 540px) {
  .filter-heading {
    align-items: flex-start;
  }
  .filter-heading-copy {
    flex-direction: column;
    gap: var(--space-1);
  }
  .filter-grid {
    gap: var(--space-3);
  }
}
</style>
