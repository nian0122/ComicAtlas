<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
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
  <section class="storage-filter-panel filter-controls" aria-label="存储记录筛选">
    <div class="filter-heading">
      <div class="filter-heading-copy">
        <h2>筛选存储记录</h2>
        <p>按漫画范围与文件状态，定位需要处理的内容</p>
      </div>
      <AppButton variant="ghost" size="sm" @click="router.push('/manage/tasks')">前往任务中心</AppButton>
    </div>

    <div class="filter-group">
      <h3>漫画范围</h3>
      <div class="filter-grid filter-grid--scope">
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
      </div>
    </div>

    <div class="filter-group filter-group--storage">
      <h3>文件状态与排序</h3>
      <div class="filter-grid filter-grid--storage">
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
          <el-select
            :model-value="sort.field"
            aria-label="存储排序依据"
            @update:model-value="setSort({ field: $event })"
          >
            <el-option v-for="option in sortOptions" :key="option.value" v-bind="option" />
          </el-select>
        </label>
        <label class="filter-field">
          <span>排列顺序</span>
          <el-select
            :model-value="sort.order"
            aria-label="存储排列顺序"
            @update:model-value="setSort({ order: $event })"
          >
            <el-option label="降序" value="desc" /><el-option label="升序" value="asc" />
          </el-select>
        </label>
      </div>
    </div>

    <div class="filter-feedback">
      <div class="filter-result" aria-live="polite">
        <span v-if="loading">正在筛选…</span>
        <span v-else
          >匹配 <strong>{{ total }}</strong> 本漫画</span
        >
        <span class="filter-summary-hint">{{ activeConditions.length ? '条件同时匹配' : '当前显示全部记录' }}</span>
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
      <p class="filter-note">筛选仅影响下方漫画列表，上方占用统计保持全库汇总。</p>
    </div>
  </section>
</template>

<style scoped>
.storage-filter-panel {
  margin-bottom: var(--space-lg);
  padding: var(--space-lg);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
}
.filter-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-base);
  margin-bottom: var(--space-lg);
}
.filter-heading h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: var(--text-md);
}
.filter-heading p {
  margin: var(--space-xs) 0 0;
  color: var(--text-muted);
  font-size: var(--text-xs);
  line-height: 1.5;
}
.filter-group h3 {
  margin: 0 0 var(--space-sm);
  color: var(--text-secondary);
  font-size: var(--text-xs);
  font-weight: 600;
}
.filter-group--storage {
  margin-top: var(--space-lg);
}
.filter-grid {
  display: grid;
  gap: var(--space-base);
}
.filter-grid--scope {
  grid-template-columns: minmax(0, 2fr) repeat(2, minmax(0, 1fr));
}
.filter-grid--storage {
  grid-template-columns: repeat(3, minmax(0, 1fr)) minmax(0, 0.65fr);
}
.filter-feedback {
  margin-top: var(--space-lg);
  padding-top: var(--space-base);
  border-top: 1px solid var(--border);
}
.filter-result {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-sm);
  color: var(--text-secondary);
  font-size: var(--text-sm);
}
.filter-result strong {
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.filter-summary-hint,
.filter-note {
  color: var(--text-muted);
  font-size: var(--text-xs);
}
.filter-conditions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}
.filter-condition {
  max-width: 100%;
  min-height: var(--button-height-sm);
  background: var(--control-bg);
  border-color: var(--control-border);
  border-radius: var(--control-radius);
  color: var(--text-secondary);
}
.filter-condition :deep(> span) {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: var(--space-sm);
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
.filter-note {
  margin: var(--space-sm) 0 0;
  line-height: 1.5;
}
@media (max-width: 900px) {
  .filter-grid--scope,
  .filter-grid--storage {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .filter-field--keyword {
    grid-column: 1 / -1;
  }
}
@media (max-width: 540px) {
  .storage-filter-panel {
    padding: var(--space-base);
  }
  .filter-heading {
    align-items: flex-start;
    flex-direction: column;
  }
  .filter-grid {
    gap: var(--space-sm);
  }
}
</style>
