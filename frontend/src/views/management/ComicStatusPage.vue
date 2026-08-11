<template>
  <div class="comic-status-page">
    <header class="page-header">
      <div><h1>漫画状态</h1><p>完整展示漫画生命周期；自动观察瞬时状态，方便确认操作是否生效。</p></div>
      <div><el-switch v-model="autoRefresh" active-text="自动刷新" /><el-button :loading="loading" @click="loadComics">刷新</el-button></div>
    </header>

    <section class="summary-grid" aria-label="状态统计">
      <article><span>匹配漫画</span><strong>{{ total }}</strong><small>当前筛选全部结果</small></article>
      <article><span>本页可阅读</span><strong>{{ pageCounts.READY ?? 0 }}</strong><small>状态为 READY</small></article>
      <article><span>本页处理中</span><strong>{{ transientCount }}</strong><small>自动刷新观察中</small></article>
      <article><span>本页异常/需处理</span><strong>{{ attentionCount }}</strong><small>失败、恢复或回收态</small></article>
    </section>

    <div class="filters">
      <el-input v-model="keyword" placeholder="标题或作者" clearable @keyup.enter="applyFilters" />
      <el-select v-model="status" placeholder="全部生命周期" clearable @change="applyFilters">
        <el-option v-for="item in statusOptions" :key="item.status" :label="`${item.meta.label} (${item.status})`" :value="item.status" />
      </el-select>
      <el-button @click="applyFilters">查询</el-button>
      <span>{{ updatedAt ? `更新于 ${updatedAt}` : '尚未更新' }}</span>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <div class="table-scroll"><el-table v-loading="loading" :data="comics" row-key="id">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="title" label="漫画" min-width="220" />
      <el-table-column label="生命周期" min-width="200"><template #default="{ row }"><ComicStatusTag :status="row.status" /></template></el-table-column>
      <el-table-column label="状态说明" min-width="280"><template #default="{ row }">{{ comicStatusMeta(row.status).description }}</template></el-table-column>
      <el-table-column prop="pageCount" label="页数" width="90" />
      <el-table-column label="操作" width="190">
        <template #default="{ row }">
          <router-link :to="`/manage/operations?comicId=${row.id}`">操作与观察</router-link>
          <router-link :to="`/manage/comics/${row.id}/edit`">编辑信息</router-link>
        </template>
      </el-table-column>
    </el-table></div>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" @current-change="loadComics" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import axios from 'axios'
import { comicStatusApi } from '@/services/management-capabilities'
import ComicStatusTag from '@/components/management/ComicStatusTag.vue'
import { COMIC_STATUSES, comicStatusMeta } from '@/utils/comic-status'
import type { ComicListVO, ComicStatus } from '@/types'

const comics = ref<readonly ComicListVO[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const status = ref<ComicStatus | undefined>()
const loading = ref(false)
const error = ref('')
const autoRefresh = ref(true)
const updatedAt = ref('')
let timer: ReturnType<typeof setInterval> | undefined
const statusOptions = COMIC_STATUSES.map((entryStatus) => ({ status: entryStatus, meta: comicStatusMeta(entryStatus) }))
const pageCounts = computed<Partial<Record<ComicStatus, number>>>(() => { const counts: Partial<Record<ComicStatus, number>> = {}; for (const comic of comics.value) counts[comic.status] = (counts[comic.status] ?? 0) + 1; return counts })
const transientCount = computed(() => comics.value.filter((comic) => comicStatusMeta(comic.status).transient).length)
const attentionCount = computed(() => comics.value.filter((comic) => ['IMPORT_FAILED', 'RECOVERY_REQUIRED', 'TRASHED', 'DELETED'].includes(comic.status)).length)
function errorMessage(reason: unknown): string { if (axios.isAxiosError<{ message?: string }>(reason)) return reason.response?.data?.message ?? reason.message; return reason instanceof Error ? reason.message : '未知错误' }
async function loadComics(): Promise<void> { loading.value = true; error.value = ''; try { const response = await comicStatusApi.list({ page: page.value, size: 20, keyword: keyword.value.trim() || undefined, status: status.value }); comics.value = response.data.records; total.value = response.data.total; updatedAt.value = new Date().toLocaleTimeString() } catch (reason: unknown) { error.value = errorMessage(reason) } finally { loading.value = false } }
function applyFilters(): void { page.value = 1; void loadComics() }
onMounted(() => { void loadComics(); timer = setInterval(() => { if (autoRefresh.value && !loading.value) void loadComics() }, 3000) })
onBeforeUnmount(() => { if (timer !== undefined) clearInterval(timer) })
</script>

<style scoped>
.comic-status-page { display: grid; gap: var(--space-6); }
.page-header, .page-header > div, .filters { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; }
.page-header h1 { margin: 0; color: var(--text-primary); font-size: var(--text-page); }
.page-header p, .filters span, .summary-grid span, .summary-grid small { color: var(--text-muted); }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-4); }
.summary-grid article { display: grid; gap: var(--space-2); padding: var(--space-5); border: 1px solid var(--border); background: var(--bg-surface); }
.summary-grid strong { color: var(--text-primary); font-size: 2rem; }
.filters :deep(.el-input), .filters :deep(.el-select) { width: 240px; }
.table-scroll { width: 100%; min-width: 0; overflow-x: auto; }
.table-scroll :deep(.el-table) { min-width: 980px; }
td a { margin-right: var(--space-3); color: var(--accent); }
@media (max-width: 900px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 480px) {
  .summary-grid { grid-template-columns: minmax(0, 1fr); }
  .filters :deep(.el-input), .filters :deep(.el-select) { width: 100%; }
}
</style>
