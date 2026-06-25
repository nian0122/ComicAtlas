<template>
  <div class="operation-log-page">
    <header class="page-header">
      <h1 class="page-title">操作日志</h1>
    </header>

    <div class="filter-bar">
      <el-select
        v-model="filterModule"
        placeholder="模块"
        clearable
        @change="onFilterChange"
      >
        <el-option label="导入" value="import" />
        <el-option label="漫画" value="comic" />
        <el-option label="阅读" value="reader" />
      </el-select>

      <el-select
        v-model="filterAction"
        placeholder="操作"
        clearable
        @change="onFilterChange"
      >
        <el-option label="创建" value="create" />
        <el-option label="删除" value="delete" />
        <el-option label="更新" value="update" />
        <el-option label="读取" value="read" />
      </el-select>

      <el-input
        v-model="filterKeyword"
        placeholder="关键词搜索..."
        clearable
        class="keyword-input"
        @clear="onFilterChange"
        @keyup.enter="onFilterChange"
      />

      <el-button type="primary" @click="onFilterChange" :icon="Search">搜索</el-button>
    </div>

    <el-table
      :data="logs"
      v-loading="loading"
      class="log-table"
      empty-text="暂无日志"
      border
      stripe
    >
      <el-table-column label="时间" width="170" prop="createdAt">
        <template #default="{ row }">
          <span class="cell-text">{{ formatTime(row.createdAt) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="模块" width="80" prop="module">
        <template #default="{ row }">
          <el-tag size="small" type="info" effect="plain">
            {{ row.module }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="80" prop="action">
        <template #default="{ row }">
          <span class="cell-text">{{ row.action }}</span>
        </template>
      </el-table-column>

      <el-table-column label="业务ID" width="100" prop="businessId">
        <template #default="{ row }">
          <span class="cell-text mono">{{ row.businessId || '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="详情" min-width="200" prop="detail">
        <template #default="{ row }">
          <el-tooltip
            :content="row.detail"
            placement="top"
            :show-after="500"
            :disabled="!row.detail || row.detail.length < 50"
          >
            <span class="cell-text detail-cell">{{ row.detail || '-' }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        background
        @current-change="onPageChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { operationApi } from '@/services/api'
import type { OperationLogVO } from '@/types'

const logs = ref<OperationLogVO[]>([])
const loading = ref(false)
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)

const filterModule = ref('')
const filterAction = ref('')
const filterKeyword = ref('')

function formatTime(ts: string): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

async function fetchLogs() {
  loading.value = true
  try {
    const res = await operationApi.list({
      page: currentPage.value,
      size: pageSize.value,
      module: filterModule.value || undefined,
      action: filterAction.value || undefined,
      keyword: filterKeyword.value || undefined,
    })
    const data = res.data as { records: OperationLogVO[]; total: number }
    logs.value = data.records
    total.value = data.total
  } catch {
    logs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  currentPage.value = 1
  fetchLogs()
}

function onPageChange(page: number) {
  currentPage.value = page
  fetchLogs()
}

onMounted(() => {
  fetchLogs()
})
</script>

<style scoped>
.operation-log-page {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 20px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0;
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}

.keyword-input {
  flex: 1;
  min-width: 150px;
}

.log-table {
  border-radius: 8px;
  overflow: hidden;
}

.cell-text {
  font-size: 13px;
  color: var(--text-h);
}

.mono {
  font-family: var(--mono);
  font-size: 12px;
  color: var(--text);
}

.detail-cell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  max-width: 300px;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
