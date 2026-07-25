<script setup lang="ts">
import { computed } from 'vue'
import { ElSelect, ElOption, ElInput } from 'element-plus'

interface Filter {
  hqStatus: string
  lqStatus: string
  keyword: string
}

interface Sort {
  field: string
  order: string
}

const props = defineProps<{
  filter: Filter
  sort: Sort
  scanning?: boolean
  rebuilding?: boolean
}>()

const emit = defineEmits<{
  'update:filter': [value: Filter]
  'update:sort': [value: Sort]
  scanRecover: []
  rebuild: []
}>()

const filterModel = computed({
  get: () => props.filter,
  set: (val) => emit('update:filter', val)
})

const sortModel = computed({
  get: () => props.sort,
  set: (val) => emit('update:sort', val)
})
</script>

<template>
  <div>
    <section class="action-section">
      <h2 class="section-title">操作</h2>
      <div class="action-list">
        <button class="action-btn" :disabled="scanning" @click="emit('scanRecover')">{{ scanning ? '扫描中...' : '扫描并恢复' }}</button>
        <button class="action-btn" :disabled="rebuilding" @click="emit('rebuild')">{{ rebuilding ? '重建中...' : '重建元数据' }}</button>
        <button class="action-btn danger" disabled>清理未引用文件</button>
      </div>
    </section>

    <section class="action-section">
      <h2 class="section-title">存储优化</h2>
      <div class="filter-bar">
        <el-select v-model="filterModel.hqStatus" placeholder="HQ 状态" class="filter-select">
          <el-option label="全部" value="ALL" />
          <el-option label="还有 HQ" value="HAS_HQ" />
          <el-option label="HQ 已删" value="NO_HQ" />
        </el-select>
        <el-select v-model="filterModel.lqStatus" placeholder="LQ 状态" class="filter-select">
          <el-option label="全部" value="ALL" />
          <el-option label="需要生成" value="NEEDS_LQ" />
          <el-option label="LQ 就绪" value="READY" />
        </el-select>
        <el-select v-model="sortModel.field" placeholder="排序" class="filter-select">
          <el-option label="HQ 大小" value="hqSize" />
          <el-option label="LQ 大小" value="lqSize" />
          <el-option label="总大小" value="totalSize" />
          <el-option label="标题" value="title" />
        </el-select>
        <el-select v-model="sortModel.order" class="filter-select--mini">
          <el-option label="降序" value="desc" />
          <el-option label="升序" value="asc" />
        </el-select>
        <el-input v-model="filterModel.keyword" placeholder="搜索标题" clearable class="filter-input" />
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
.filter-bar { display: flex; gap: var(--space-sm); margin-bottom: var(--space-base); flex-wrap: wrap; align-items: center; }
.filter-select { width: 120px; }
.filter-select--mini { width: 100px; }
.filter-input { width: 180px; }
@media (max-width: 768px) { .filter-bar { flex-direction: column; align-items: stretch; } .filter-select, .filter-select--mini, .filter-input { width: 100% !important; } }
</style>
