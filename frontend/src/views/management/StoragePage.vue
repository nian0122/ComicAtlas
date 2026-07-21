<template>
  <div class="storage-page">
    <header class="page-header">
      <h1 class="page-title">存储管理</h1>
    </header>

    <section class="stat-grid">
      <div class="stat-card">
        <span class="stat-value">{{ formatSize(stats.totalBytes) }}</span>
        <span class="stat-label">总大小</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ formatSize(stats.hqBytes) }}</span>
        <span class="stat-label">HQ 占用</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ formatSize(stats.lqBytes) }}</span>
        <span class="stat-label">LQ 占用</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ formatSize(stats.thumbBytes) }}</span>
        <span class="stat-label">缩略图</span>
      </div>
    </section>

    <section class="action-section">
      <h2 class="section-title">操作</h2>
      <div class="action-list">
        <button class="action-btn" :disabled="scanning" @click="onScanRecover">
          {{ scanning ? '扫描中...' : '扫描并恢复' }}
        </button>
        <button class="action-btn" :disabled="rebuilding" @click="onRebuild">
          {{ rebuilding ? '重建中...' : '重建元数据' }}
        </button>
        <button class="action-btn danger" disabled>清理未引用文件</button>
      </div>
    </section>

    <section class="action-section">
      <h2 class="section-title">存储优化</h2>

      <div class="filter-bar">
        <el-select v-model="filters.hqStatus" placeholder="HQ 状态" style="width: 120px" @change="onFilterChange">
          <el-option label="全部" value="ALL" />
          <el-option label="还有 HQ" value="HAS_HQ" />
          <el-option label="HQ 已删" value="NO_HQ" />
        </el-select>
        <el-select v-model="filters.lqStatus" placeholder="LQ 状态" style="width: 120px" @change="onFilterChange">
          <el-option label="全部" value="ALL" />
          <el-option label="需要生成" value="NEEDS_LQ" />
          <el-option label="LQ 就绪" value="READY" />
        </el-select>
        <el-select v-model="filters.sort" placeholder="排序" style="width: 120px" @change="onFilterChange">
          <el-option label="HQ 大小" value="hqSize" />
          <el-option label="LQ 大小" value="lqSize" />
          <el-option label="总大小" value="totalSize" />
          <el-option label="标题" value="title" />
        </el-select>
        <el-select v-model="filters.order" style="width: 100px" @change="onFilterChange">
          <el-option label="降序" value="desc" />
          <el-option label="升序" value="asc" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          placeholder="搜索标题"
          clearable
          style="width: 180px"
          @keyup.enter="onFilterChange"
          @clear="onFilterChange"
        />
      </div>

      <el-table
        v-loading="loading"
        :data="comicList"
        row-key="comicId"
        :row-class-name="rowClassName"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="40" />
        <el-table-column label="封面" width="70">
          <template #default="{ row }">
            <img
              :src="row.coverUrl || '/placeholder-cover.png'"
              class="cover-thumb"
              loading="lazy"
              alt=""
            />
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip />
        <el-table-column label="HQ" width="100" align="right">
          <template #default="{ row }">{{ formatSize(row.hqSize) }}</template>
        </el-table-column>
        <el-table-column label="LQ" width="100" align="right">
          <template #default="{ row }">{{ formatSize(row.lqSize) }}</template>
        </el-table-column>
        <el-table-column label="HQ 状态" width="100">
          <template #default="{ row }">
            <el-tag :type="hqTagType(row.hqStatus)" size="small">{{ hqTagText(row.hqStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="LQ 状态" width="100">
          <template #default="{ row }">
            <el-tag :type="lqTagType(row.lqStatus)" size="small">{{ lqTagText(row.lqStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.hqStatus === 'READY' || row.hqStatus === 'MIXED'"
              type="danger"
              text
              size="small"
              @click="deleteComicHq(row.comicId, row.title)"
            >删HQ</el-button>
            <el-button
              v-if="row.lqStatus === 'NOT_GENERATED' || row.lqStatus === 'MIXED'"
              type="primary"
              text
              size="small"
              @click="generateComicLq(row.comicId, row.title)"
            >生LQ</el-button>
            <el-button type="info" text size="small" @click="openDrawer(row.comicId)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="selectedComicIds.length > 0" class="batch-bar">
        <span>已选 {{ selectedComicIds.length }} 部</span>
        <el-button type="danger" @click="batchDeleteHq">删除选中 HQ</el-button>
        <el-button type="primary" @click="batchGenerateLq">生成选中 LQ</el-button>
      </div>

      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        class="pagination-bar"
        @change="loadComicList"
      />
    </section>

    <el-drawer
      v-model="drawerVisible"
      :title="`${drawerComicTitle} — 存储详情`"
      size="520px"
      destroy-on-close
    >
      <div v-if="drawerLoading" class="state loading small">
        <div class="spinner" />
        <span>加载中...</span>
      </div>
      <template v-else>
        <div class="drawer-stats">
          <div class="drawer-stat">
            <span class="drawer-stat-value">{{ formatSize(drawerTotalHq) }}</span>
            <span class="drawer-stat-label">HQ</span>
          </div>
          <div class="drawer-stat">
            <span class="drawer-stat-value">{{ formatSize(drawerTotalLq) }}</span>
            <span class="drawer-stat-label">LQ</span>
          </div>
          <div class="drawer-stat">
            <span class="drawer-stat-value">{{ drawerChapters.length }}</span>
            <span class="drawer-stat-label">章节</span>
          </div>
        </div>

        <el-table
          :data="drawerChapters"
          size="small"
          @selection-change="handleDrawerSelectionChange"
        >
          <el-table-column type="selection" width="40" />
          <el-table-column prop="title" label="章节" min-width="100" show-overflow-tooltip />
          <el-table-column label="HQ" width="80" align="right">
            <template #default="{ row }">{{ formatSize(row.hqSize) }}</template>
          </el-table-column>
          <el-table-column label="LQ" width="80" align="right">
            <template #default="{ row }">{{ formatSize(row.lqSize) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              v-if="row.hqStatus === 'READY' || row.hqStatus === 'MIXED'"
              type="danger"
              text
              size="small"
              @click="deleteChapterHq(row.chapterId, row.title)"
            >删HQ</el-button>
            <el-button
              v-if="row.lqStatus === 'NOT_GENERATED' || row.lqStatus === 'MIXED'"
              type="primary"
              text
              size="small"
              @click="generateChapterLq(row.chapterId, row.title)"
            >生LQ</el-button>
          </template>
          </el-table-column>
        </el-table>

        <div v-if="drawerSelectedIds.length > 0" class="drawer-batch-bar">
          <span>已选 {{ drawerSelectedIds.length }} 章</span>
          <el-button type="danger" size="small" @click="batchDeleteDrawerHq">删除选中 HQ</el-button>
          <el-button type="primary" size="small" @click="batchGenerateDrawerLq">生成选中 LQ</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, hqApi, lqApi } from '@/services/management'

interface StorageStats {
  totalBytes: number
  hqBytes: number
  lqBytes: number
  thumbBytes: number
  comicCount: number
}

interface ComicStorageItem {
  comicId: number
  title: string
  coverUrl: string
  totalSize: number
  hqSize: number
  lqSize: number
  hqStatus: 'READY' | 'DELETED' | 'MIXED' | 'EMPTY'
  lqStatus: 'READY' | 'NOT_GENERATED' | 'MIXED' | 'EMPTY'
  chapterCount: number
  pageCount: number
}

interface ChapterStorageItem {
  chapterId: number
  chapterNo: string
  title: string
  pageCount: number
  hqSize: number
  lqSize: number
  hqStatus: 'READY' | 'DELETED' | 'MIXED' | 'EMPTY'
  lqStatus: 'READY' | 'NOT_GENERATED' | 'MIXED' | 'EMPTY'
}

const route = useRoute()

const stats = reactive<StorageStats>({
  totalBytes: 0,
  hqBytes: 0,
  lqBytes: 0,
  thumbBytes: 0,
  comicCount: 0,
})

const scanning = ref(false)
const rebuilding = ref(false)
const highlightedComicId = ref<number | null>(null)

const comicList = ref<ComicStorageItem[]>([])
const loading = ref(false)
const selectedComicIds = ref<number[]>([])

const filters = reactive({
  hqStatus: 'ALL' as 'ALL' | 'HAS_HQ' | 'NO_HQ',
  lqStatus: 'ALL' as 'ALL' | 'NEEDS_LQ' | 'READY',
  sort: 'hqSize' as 'totalSize' | 'hqSize' | 'lqSize' | 'title',
  order: 'desc' as 'asc' | 'desc',
  keyword: '',
})

const pagination = reactive({
  page: 1,
  size: 20,
  total: 0,
})

const drawerVisible = ref(false)
const drawerComicId = ref<number | null>(null)
const drawerComicTitle = ref('')
const drawerChapters = ref<ChapterStorageItem[]>([])
const drawerLoading = ref(false)
const drawerSelectedIds = ref<number[]>([])

const drawerTotalHq = computed(() => drawerChapters.value.reduce((sum, c) => sum + (c.hqSize || 0), 0))
const drawerTotalLq = computed(() => drawerChapters.value.reduce((sum, c) => sum + (c.lqSize || 0), 0))

function formatSize(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
}

function hqTagType(status: string) {
  switch (status) {
    case 'READY': return 'success'
    case 'DELETED': return 'info'
    case 'MIXED': return 'warning'
    case 'EMPTY': return ''
    default: return ''
  }
}

function hqTagText(status: string) {
  switch (status) {
    case 'READY': return 'HQ 就绪'
    case 'DELETED': return 'HQ 已删'
    case 'MIXED': return '部分已删'
    case 'EMPTY': return '无数据'
    default: return status
  }
}

function lqTagType(status: string) {
  switch (status) {
    case 'READY': return 'success'
    case 'NOT_GENERATED': return 'warning'
    case 'MIXED': return 'danger'
    case 'EMPTY': return ''
    default: return ''
  }
}

function lqTagText(status: string) {
  switch (status) {
    case 'READY': return 'LQ 就绪'
    case 'NOT_GENERATED': return '未生成'
    case 'MIXED': return '部分失败'
    case 'EMPTY': return '无数据'
    default: return status
  }
}

function rowClassName({ row }: { row: ComicStorageItem }) {
  if (highlightedComicId.value && row.comicId === highlightedComicId.value) {
    return 'highlighted-row'
  }
  return ''
}

async function loadStats() {
  try {
    const res = await adminApi.stats()
    const data = res.data as StorageStats
    Object.assign(stats, data)
  } catch {
    // keep default zeros
  }
}

async function loadComicList() {
  loading.value = true
  try {
    const res = await adminApi.storageComics({
      page: pagination.page,
      size: pagination.size,
      ...filters,
    })
    const data = res.data as { records: ComicStorageItem[]; total: number }
    comicList.value = data.records || []
    // 内存筛选可能导致实际返回少于 page size；total 显示实际返回数避免翻页空页
    pagination.total = comicList.value.length
  } catch (err: any) {
    const msg = err.response?.data?.message || '加载存储列表失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  pagination.page = 1
  loadComicList()
}

function handleSelectionChange(selection: ComicStorageItem[]) {
  selectedComicIds.value = selection.map((item) => item.comicId)
}

function handleDrawerSelectionChange(selection: ChapterStorageItem[]) {
  drawerSelectedIds.value = selection.map((item) => item.chapterId)
}

async function openDrawer(comicId: number) {
  const comic = comicList.value.find((c) => c.comicId === comicId)
  drawerComicTitle.value = comic?.title || ''
  drawerComicId.value = comicId
  drawerVisible.value = true
  drawerLoading.value = true
  drawerSelectedIds.value = []
  try {
    const res = await adminApi.storageChapters(comicId)
    drawerChapters.value = (res.data as ChapterStorageItem[]) || []
  } catch (err: any) {
    const msg = err.response?.data?.message || '加载章节列表失败'
    ElMessage.error(msg)
  } finally {
    drawerLoading.value = false
  }
}

async function deleteComicHq(comicId: number, title: string) {
  try {
    await ElMessageBox.confirm(
      `确认删除《${title}》的 HQ 原图？删除后无法恢复，LQ 画质仍可正常阅读。`,
      '删除 HQ',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await hqApi.deleteComic(comicId)
    ElMessage.success('HQ 删除任务已提交')
    await loadComicList()
  } catch (err: any) {
    if (err.response?.status === 409) {
      ElMessage.error(`${title}: LQ 未就绪，无法删除 HQ`)
    } else {
      ElMessage.error(err.response?.data?.message || '删除失败')
    }
  }
}

async function generateComicLq(comicId: number, title: string) {
  try {
    await ElMessageBox.confirm(
      `确认为《${title}》生成 LQ？`,
      '生成 LQ',
      { confirmButtonText: '确认生成', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  try {
    await lqApi.generateComic(comicId)
    ElMessage.success('LQ 生成任务已提交')
    await loadComicList()
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || '生成失败')
  }
}

async function batchDeleteHq() {
  // 过滤掉 EMPTY 状态的漫画
  const validIds = selectedComicIds.value.filter((id) => {
    const comic = comicList.value.find((c) => c.comicId === id)
    return comic && comic.hqStatus !== 'EMPTY'
  })
  if (validIds.length === 0) {
    ElMessage.warning('选中的漫画中没有可操作 HQ 删除的项')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除 ${validIds.length} 部漫画的 HQ 原图？`,
      '批量删除 HQ',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  let success = 0
  let failed = 0
  const errors: string[] = []

  for (const id of validIds) {
    const comic = comicList.value.find((c) => c.comicId === id)
    const name = comic?.title || `ID:${id}`
    try {
      await hqApi.deleteComic(id)
      success++
    } catch (err: any) {
      failed++
      if (err.response?.status === 409) {
        errors.push(`${name}: LQ 未就绪`)
      } else {
        errors.push(`${name}: ${err.response?.data?.message || '删除失败'}`)
      }
    }
  }

  if (failed === 0) {
    ElMessage.success(`${success} 部漫画 HQ 删除任务已提交`)
  } else {
    ElMessage.warning(`${success} 部成功，${failed} 部失败`)
    if (errors.length > 0) {
      console.error('批量删除 HQ 失败详情:', errors)
    }
  }
  selectedComicIds.value = []
  await loadComicList()
}

async function batchGenerateLq() {
  // 过滤掉 EMPTY 和 READY 状态的漫画
  const validIds = selectedComicIds.value.filter((id) => {
    const comic = comicList.value.find((c) => c.comicId === id)
    return comic && comic.lqStatus !== 'EMPTY' && comic.lqStatus !== 'READY'
  })
  if (validIds.length === 0) {
    ElMessage.warning('选中的漫画中没有可操作 LQ 生成的项')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认为 ${validIds.length} 部漫画生成 LQ？`,
      '批量生成 LQ',
      { confirmButtonText: '确认生成', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }

  let success = 0
  let failed = 0
  const errors: string[] = []

  for (const id of validIds) {
    const comic = comicList.value.find((c) => c.comicId === id)
    const name = comic?.title || `ID:${id}`
    try {
      await lqApi.generateComic(id)
      success++
    } catch (err: any) {
      failed++
      errors.push(`${name}: ${err.response?.data?.message || '生成失败'}`)
    }
  }

  if (failed === 0) {
    ElMessage.success(`${success} 部漫画 LQ 生成任务已提交`)
  } else {
    ElMessage.warning(`${success} 部成功，${failed} 部失败`)
    if (errors.length > 0) {
      console.error('批量生成 LQ 失败详情:', errors)
    }
  }
  selectedComicIds.value = []
  await loadComicList()
}

async function deleteChapterHq(chapterId: number, title: string) {
  try {
    await ElMessageBox.confirm(`确认删除《${title}》的 HQ？`, '删除 HQ', { type: 'warning' })
  } catch {
    return
  }
  try {
    await hqApi.deleteChapter(chapterId)
    ElMessage.success('HQ 删除任务已提交')
    if (drawerComicId.value) await openDrawer(drawerComicId.value)
  } catch (err: any) {
    if (err.response?.status === 409) {
      ElMessage.error(`${title}: LQ 未就绪`)
    } else {
      ElMessage.error(err.response?.data?.message || '删除失败')
    }
  }
}

async function generateChapterLq(chapterId: number, title: string) {
  try {
    await ElMessageBox.confirm(`确认为《${title}》生成 LQ？`, '生成 LQ', { type: 'info' })
  } catch {
    return
  }
  try {
    await lqApi.generateChapter(chapterId)
    ElMessage.success('LQ 生成任务已提交')
    if (drawerComicId.value) await openDrawer(drawerComicId.value)
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || '生成失败')
  }
}

async function batchDeleteDrawerHq() {
  // 过滤掉 EMPTY 状态的章节
  const validIds = drawerSelectedIds.value.filter((id) => {
    const chapter = drawerChapters.value.find((c) => c.chapterId === id)
    return chapter && chapter.hqStatus !== 'EMPTY'
  })
  if (validIds.length === 0) {
    ElMessage.warning('选中的章节中没有可操作 HQ 删除的项')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除 ${validIds.length} 章的 HQ？`,
      '批量删除 HQ',
      { type: 'warning' }
    )
  } catch {
    return
  }
  let success = 0
  let failed = 0
  for (const id of validIds) {
    try {
      await hqApi.deleteChapter(id)
      success++
    } catch {
      failed++
    }
  }
  if (failed === 0) {
    ElMessage.success(`${success} 章 HQ 删除任务已提交`)
  } else {
    ElMessage.warning(`${success} 章成功，${failed} 章失败`)
  }
  drawerSelectedIds.value = []
  if (drawerComicId.value) await openDrawer(drawerComicId.value)
}

async function batchGenerateDrawerLq() {
  // 过滤掉 EMPTY 和 READY 状态的章节
  const validIds = drawerSelectedIds.value.filter((id) => {
    const chapter = drawerChapters.value.find((c) => c.chapterId === id)
    return chapter && chapter.lqStatus !== 'EMPTY' && chapter.lqStatus !== 'READY'
  })
  if (validIds.length === 0) {
    ElMessage.warning('选中的章节中没有可操作 LQ 生成的项')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认为 ${validIds.length} 章生成 LQ？`,
      '批量生成 LQ',
      { type: 'info' }
    )
  } catch {
    return
  }
  let success = 0
  let failed = 0
  for (const id of validIds) {
    try {
      await lqApi.generateChapter(id)
      success++
    } catch {
      failed++
    }
  }
  if (failed === 0) {
    ElMessage.success(`${success} 章 LQ 生成任务已提交`)
  } else {
    ElMessage.warning(`${success} 章成功，${failed} 章失败`)
  }
  drawerSelectedIds.value = []
  if (drawerComicId.value) await openDrawer(drawerComicId.value)
}

async function onScanRecover() {
  scanning.value = true
  try {
    const res = await adminApi.scanRecover()
    const data = res.data as { restoredComics: number; restoredChapters: number; restoredPages: number; placeholderComics: number }
    ElMessage.success(`扫描完成：恢复 ${data.restoredComics} 部漫画，${data.restoredChapters} 个章节，${data.restoredPages} 页`)
    await loadStats()
    await loadComicList()
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '扫描失败')
  } finally {
    scanning.value = false
  }
}

async function onRebuild() {
  try {
    await ElMessageBox.confirm('将从 HQ 目录和 metadata 文件重建所有漫画数据。', '重建元数据', { type: 'info', confirmButtonText: '开始重建' })
  } catch { return }

  rebuilding.value = true
  try {
    const res = await adminApi.rebuild()
    const data = res.data as { comics: number; chapters: number; pages: number }
    ElMessage.success(`重建完成：${data.comics} 部漫画，${data.chapters} 个章节，${data.pages} 页`)
    await loadStats()
    await loadComicList()
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '重建失败')
  } finally {
    rebuilding.value = false
  }
}

onMounted(async () => {
  loadStats()
  await loadComicList()
  const highlightId = route.query.highlight
  if (highlightId) {
    highlightedComicId.value = Number(highlightId)
    setTimeout(() => {
      highlightedComicId.value = null
    }, 3000)
  }
})
</script>

<style scoped>
.storage-page {
  max-width: 1200px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-xl);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-base);
  margin-bottom: var(--space-2xl);
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-lg);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.stat-value {
  font-size: 24px;
  font-weight: 800;
  color: var(--text-primary);
}

.stat-label {
  font-size: 12px;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

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

.filter-bar {
  display: flex;
  gap: var(--space-sm);
  margin-bottom: var(--space-base);
  flex-wrap: wrap;
  align-items: center;
}

.cover-thumb {
  width: 48px;
  height: 64px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  background: var(--bg-secondary);
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  padding: var(--space-base) 0;
  border-top: 1px solid var(--border);
  margin-top: var(--space-base);
}

.pagination-bar {
  margin-top: var(--space-base);
  justify-content: flex-end;
}

.drawer-stats {
  display: flex;
  gap: var(--space-xl);
  margin-bottom: var(--space-base);
  padding-bottom: var(--space-base);
  border-bottom: 1px solid var(--border);
}

.drawer-stat {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.drawer-stat-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}

.drawer-stat-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.drawer-batch-bar {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  padding: var(--space-base) 0;
  border-top: 1px solid var(--border);
  margin-top: var(--space-base);
}

:deep(.highlighted-row) {
  background-color: var(--bg-secondary) !important;
  transition: background-color 0.5s ease;
}

@media (max-width: 768px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-bar .el-select,
  .filter-bar .el-input {
    width: 100% !important;
  }
}
</style>
