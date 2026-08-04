<template>
  <div class="detail-page" v-loading="loading">
    <!-- 顶部导航 -->
    <header class="detail-header">
      <button class="back-btn" @click="$router.back()">← 返回存储列表</button>
      <div class="header-info">
        <h1 class="comic-title">{{ comic?.title ?? '加载中...' }}</h1>
        <div class="header-meta">
          <span class="meta-item">HQ 总大小: {{ formatSize(comic?.hqSize ?? 0) }}</span>
          <span class="meta-item">LQ 总大小: {{ formatSize(comic?.lqSize ?? 0) }}</span>
          <span class="meta-item">章节数: {{ chapters.length }}</span>
        </div>
      </div>
      <button class="refresh-btn" :disabled="refreshing" @click="onRefreshMetadata">
        {{ refreshing ? '刷新中...' : '刷新状态' }}
      </button>
    </header>

    <!-- 存储概览 -->
    <section class="card">
      <h2 class="section-title">存储概览</h2>
      <div class="overview-grid">
        <div class="overview-item">
          <span class="ov-label">HQ</span>
          <span class="ov-value">{{ formatSize(comic?.hqSize ?? 0) }}</span>
          <StorageStatusTag :status="comic?.hqStatus ?? 'EMPTY'" type="hq" />
        </div>
        <div class="overview-item">
          <span class="ov-label">LQ</span>
          <span class="ov-value">{{ formatSize(comic?.lqSize ?? 0) }}</span>
          <StorageStatusTag :status="comic?.lqStatus ?? 'EMPTY'" type="lq" />
        </div>
        <div class="overview-item">
          <span class="ov-label">总文件数</span>
          <span class="ov-value">{{ comic?.pageCount ?? 0 }}</span>
        </div>
      </div>
    </section>

    <!-- 存储操作 -->
    <section class="card">
      <h2 class="section-title">存储操作</h2>
      <div class="ops-bar">
        <el-button type="danger" plain @click="onDeleteHQ">删除 HQ（保留 LQ）</el-button>
        <el-button type="primary" plain @click="onGenerateLQ">生成 LQ</el-button>
        <el-button type="warning" plain @click="onTranscode">视频转码</el-button>
        <el-button type="success" plain @click="onExportZip">导出 ZIP</el-button>
        <el-button type="danger" @click="onDeleteComic">删除漫画（含本地文件）</el-button>
        <el-button type="danger" plain @click="onDeleteDatabase">删除（移入回收站）</el-button>
      </div>
    </section>

    <!-- 章节列表 -->
    <section class="card">
      <div class="section-header">
        <h2 class="section-title">章节存储</h2>
        <el-input
          v-model="chapterKeyword"
          placeholder="搜索章节"
          clearable
          class="chapter-search"
        />
      </div>
      <el-table :data="filteredChapters" row-key="chapterId" size="small">
        <el-table-column prop="chapterNo" label="编号" width="70" />
        <el-table-column prop="title" label="章节名" min-width="160" show-overflow-tooltip />
        <el-table-column label="媒体数" width="80" align="center">
          <template #default="{ row }">{{ row.pageCount ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="HQ 大小" width="100" align="right">
          <template #default="{ row }">{{ formatSize(row.hqSize) }}</template>
        </el-table-column>
        <el-table-column label="LQ 大小" width="100" align="right">
          <template #default="{ row }">{{ formatSize(row.lqSize) }}</template>
        </el-table-column>
        <el-table-column label="HQ 状态" width="90">
          <template #default="{ row }"><StorageStatusTag :status="row.hqStatus" type="hq" /></template>
        </el-table-column>
        <el-table-column label="LQ 状态" width="90">
          <template #default="{ row }"><StorageStatusTag :status="row.lqStatus" type="lq" /></template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" type="danger" plain @click="onDeleteChapterHQ(row.chapterId)">删HQ</el-button>
            <el-button size="small" type="primary" plain @click="onGenerateChapterLQ(row.chapterId)">生LQ</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { storageService, exportService } from '@/services/storage'
import { adminApi, comicApi } from '@/services/api'
import type { ComicStorageItem, ChapterStorageItem } from '@/types'
import StorageStatusTag from './StorageStatusTag.vue'

const route = useRoute()
const router = useRouter()
const comicId = Number(route.params.id)

const loading = ref(false)
const refreshing = ref(false)
const comic = ref<ComicStorageItem | null>(null)
const chapters = ref<ChapterStorageItem[]>([])
const chapterKeyword = ref('')

// --- 转码轮询 ---
const TRANSCODE_POLL_INTERVAL = 5000
const TRANSCODE_MAX_RETRIES = 12 // 60 秒
const transcodePollTimer = ref<ReturnType<typeof setInterval> | null>(null)
const transcodePollRetries = ref(0)

function startTranscodePolling() {
  stopTranscodePolling()
  transcodePollRetries.value = 0
  transcodePollTimer.value = setInterval(async () => {
    transcodePollRetries.value++
    await loadData()
    const status = comic.value?.transcodeStatus
    if (status === 'DONE' || status === 'FAILED' || status === 'NOT_NEEDED') {
      stopTranscodePolling()
      return
    }
    if (transcodePollRetries.value >= TRANSCODE_MAX_RETRIES) {
      stopTranscodePolling()
      ElMessage.warning('部分视频仍在后台处理，可稍后刷新页面查看结果')
    }
  }, TRANSCODE_POLL_INTERVAL)
}

function stopTranscodePolling() {
  if (transcodePollTimer.value !== null) {
    clearInterval(transcodePollTimer.value)
    transcodePollTimer.value = null
  }
}

const filteredChapters = computed(() => {
  if (!chapterKeyword.value) return chapters.value
  const kw = chapterKeyword.value.toLowerCase()
  return chapters.value.filter(c =>
    (c.title ?? '').toLowerCase().includes(kw) ||
    (c.chapterNo ?? '').toLowerCase().includes(kw)
  )
})

function formatSize(bytes: number): string {
  if (!bytes || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
}

async function loadData() {
  loading.value = true
  try {
    const [comicData, chaptersData] = await Promise.all([
      storageService.fetchComic(comicId),
      storageService.fetchChapters(comicId),
    ])
    comic.value = comicData
    chapters.value = chaptersData
  } catch {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

async function onRefreshMetadata() {
  refreshing.value = true
  try {
    await storageService.refreshMetadata(comicId)
    ElMessage.success('刷新完成')
    await loadData()
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '刷新失败'
    ElMessage.error(message)
  } finally {
    refreshing.value = false
  }
}

async function onDeleteHQ() {
  try {
    await ElMessageBox.confirm('确定删除该漫画的所有 HQ 原图？LQ 文件保留。', '删除 HQ', { type: 'warning' })
  } catch { return }
  try {
    await storageService.executeOperation({ type: 'DeleteHQ' as any, comicId })
    ElMessage.success('HQ 删除任务已提交')
    await loadData()
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '删除失败'
    ElMessage.error(message)
  }
}

async function onDeleteChapterHQ(chapterId: number) {
  try {
    await ElMessageBox.confirm('确定删除该章节的 HQ？', '删除 HQ', { type: 'warning' })
  } catch { return }
  try {
    await storageService.executeOperation({ type: 'DeleteHQ' as any, comicId, chapterId })
    ElMessage.success('已提交')
    await loadData()
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '操作失败'
    ElMessage.error(message)
  }
}

async function onGenerateLQ() {
  try {
    await ElMessageBox.confirm('确认为该漫画所有章节生成 LQ？', '生成 LQ', { type: 'info' })
  } catch { return }
  try {
    await storageService.executeOperation({ type: 'GenerateLQ' as any, comicId })
    ElMessage.success('LQ 生成任务已提交')
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '操作失败'
    ElMessage.error(message)
  }
}

async function onGenerateChapterLQ(chapterId: number) {
  try {
    await ElMessageBox.confirm('确认为该章节生成 LQ？', '生成 LQ', { type: 'info' })
  } catch { return }
  try {
    await storageService.executeOperation({ type: 'GenerateLQ' as any, comicId, chapterId })
    ElMessage.success('已提交')
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '操作失败'
    ElMessage.error(message)
  }
}

async function onTranscode() {
  try {
    await ElMessageBox.confirm('确认为该漫画的所有视频进行转码？', '视频转码', { type: 'info' })
  } catch { return }
  try {
    const result = await storageService.transcodeVideos(comicId)
    ElMessage.success(`已提交 ${result.submittedCount} 个视频转码任务`)
    await loadData()
    startTranscodePolling()
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '转码失败'
    ElMessage.error(message)
  }
}

async function onExportZip() {
  try {
    await exportService.createExport(comicId)
    ElMessage.success('导出任务已提交')
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '导出失败'
    ElMessage.error(message)
  }
}

async function onDeleteDatabase() {
  const title = comic.value?.title ?? ''
  try {
    await ElMessageBox.confirm(
      `确定删除「${title}」？漫画将移入回收站（保留 7 天），可在回收站恢复或永久删除。`,
      '删除漫画',
      { type: 'warning', confirmButtonText: '确定删除' }
    )
  } catch { return }
  try {
    await ElMessageBox.prompt(
      `请输入漫画标题「${title}」以确认删除：`,
      '二次确认',
      { type: 'warning', confirmButtonText: '确认删除', inputValidator: (val) => val === title || '标题不匹配' }
    )
  } catch { return }
  try {
    await comicApi.delete(comicId)
    ElMessage.success('已移入回收站，可在回收站恢复或永久删除')
    router.push('/manage/storage')
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '删除失败')
  }
}

async function onDeleteComic() {
  const title = comic.value?.title ?? ''
  try {
    await ElMessageBox.confirm(
      `确定删除「${title}」的数据库记录和所有本地文件？包括 HQ / LQ / 缩略图。此操作不可恢复！`,
      '删除漫画',
      { type: 'warning', confirmButtonText: '确定删除' }
    )
  } catch { return }
  try {
    await ElMessageBox.prompt(
      `请输入漫画标题「${title}」以确认删除：`,
      '二次确认',
      { type: 'warning', confirmButtonText: '确认删除', inputValidator: (val) => val === title || '标题不匹配' }
    )
  } catch { return }
  try {
    await adminApi.deleteComic(comicId, 'DELETE_FILES')
    ElMessage.success('已删除，文件删除任务已提交')
    router.push('/manage/storage')
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '删除失败')
  }
}

onMounted(loadData)
onBeforeUnmount(stopTranscodePolling)
</script>

<style scoped>
.detail-page {
  max-width: 1000px;
  padding: var(--space-xl) var(--space-lg);
}

.detail-header {
  display: flex;
  align-items: flex-start;
  gap: var(--space-lg);
  margin-bottom: var(--space-2xl);
}

.back-btn {
  background: none;
  border: none;
  color: var(--accent);
  font-size: 13px;
  cursor: pointer;
  padding: 4px 0;
  white-space: nowrap;
}

.header-info { flex: 1; }

.comic-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-sm);
}

.header-meta {
  display: flex;
  gap: var(--space-lg);
  font-size: 13px;
  color: var(--text-secondary);
}

.refresh-btn {
  padding: 8px 16px;
  background: var(--bg-surface);
  color: var(--text-primary);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}

.card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
  margin-bottom: var(--space-xl);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-base);
}

.section-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-base);
}
.section-header .section-title { margin: 0; }

.chapter-search { width: 200px; }

.overview-grid {
  display: flex;
  gap: var(--space-2xl);
}

.overview-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ov-label { font-size: 12px; color: var(--text-muted); }
.ov-value { font-size: 20px; font-weight: 700; color: var(--text-primary); }

.ops-bar {
  display: flex;
  gap: var(--space-base);
}
</style>
