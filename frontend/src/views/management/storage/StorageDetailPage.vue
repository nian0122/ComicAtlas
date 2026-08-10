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
      <button
        class="refresh-btn"
        :class="{ 'refresh-btn--ready': !refreshDisabled }"
        :disabled="refreshDisabled"
        :title="refreshButtonTitle"
        @click="onRefreshMetadata"
      >
        {{ refreshButtonLabel }}
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
        <div class="overview-item">
          <span class="ov-label">video</span>
          <span class="ov-value">{{ formatSize(comic?.videoSize ?? 0) }}</span>
          <StorageStatusTag :status="comic?.transcodeStatus ?? 'NOT_NEEDED'" type="transcode" />
        </div>
      </div>
    </section>

    <!-- 存储操作 -->
    <section class="card">
      <h2 class="section-title">存储操作</h2>
      <div class="ops-bar">
        <el-button type="danger" plain @click="onDeleteHQ">删除 HQ（保留 LQ）</el-button>
        <el-button type="primary" plain @click="onGenerateLQ">生成 LQ</el-button>
        <el-button
          type="warning"
          plain
          :disabled="transcodeControl.disabled"
          :title="transcodeControl.title"
          @click="onTranscode"
        >
          {{ transcodeControl.label }}
        </el-button>
        <el-button type="success" plain @click="onExportZip">导出 ZIP</el-button>
        <el-button type="danger" @click="onTrashComic">移入回收站</el-button>
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
        <el-table-column label="video 大小" width="100" align="right">
          <template #default="{ row }">{{ formatSize(row.videoSize) }}</template>
        </el-table-column>
        <el-table-column label="HQ 状态" width="90">
          <template #default="{ row }"><StorageStatusTag :status="row.hqStatus" type="hq" /></template>
        </el-table-column>
        <el-table-column label="LQ 状态" width="90">
          <template #default="{ row }"><StorageStatusTag :status="row.lqStatus" type="lq" /></template>
        </el-table-column>
        <el-table-column label="video 状态" width="90">
          <template #default="{ row }"><StorageStatusTag :status="row.transcodeStatus ?? 'NOT_NEEDED'" type="transcode" /></template>
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
import { comicApi } from '@/services/api'
import type { ComicStorageItem, ChapterStorageItem } from '@/types'
import { StorageOperationType } from '@/types'
import StorageStatusTag from './StorageStatusTag.vue'

const route = useRoute()
const router = useRouter()
const comicId = Number(route.params.id)

const loading = ref(false)
const comic = ref<ComicStorageItem | null>(null)
const chapters = ref<ChapterStorageItem[]>([])
const chapterKeyword = ref('')

// --- 元数据刷新（异步任务，202 + taskId） ---
const comicStatus = ref('')
const refreshSubmitted = ref(false)
const refreshInFlight = ref(false)

// --- 转码轮询 ---
const TRANSCODE_POLL_INTERVAL = 5000
const TRANSCODE_MAX_RETRIES = 12 // 60 秒
const transcodePollTimer = ref<ReturnType<typeof setInterval> | null>(null)
const transcodePollRetries = ref(0)

/**
 * 转码按钮资格：仅 REQUIRED/FAILED 可点击，QUEUED/TRANSCODING 禁用（进行中），
 * READY/NOT_NEEDED 禁用（无需转码）；未知状态走安全禁用兜底，不崩溃。
 */
const transcodeControl = computed(() => {
  switch (comic.value?.transcodeStatus) {
    case 'REQUIRED':
      return { label: '视频转码', disabled: false, title: '为所有视频发起转码' }
    case 'FAILED':
      return { label: '重试转码', disabled: false, title: '部分视频转码失败，可重新发起' }
    case 'QUEUED':
    case 'TRANSCODING':
      return { label: '转码中', disabled: true, title: '转码任务进行中，请稍候' }
    case 'READY':
      return { label: '已转码', disabled: true, title: '视频均已转码' }
    case 'NOT_NEEDED':
      return { label: '无需转码', disabled: true, title: '该漫画无待转码视频' }
    default:
      return { label: '视频转码', disabled: true, title: '转码状态未知，暂时不可操作' }
  }
})

/** 转码轮询：仅 QUEUED/TRANSCODING 持续轮询，其余终态（含 REQUIRED/FAILED/READY/NOT_NEEDED）停止。 */
function startTranscodePolling() {
  stopTranscodePolling()
  transcodePollRetries.value = 0
  transcodePollTimer.value = setInterval(async () => {
    transcodePollRetries.value++
    await loadData()
    const status = comic.value?.transcodeStatus
    if (status === 'QUEUED' || status === 'TRANSCODING') {
      if (transcodePollRetries.value >= TRANSCODE_MAX_RETRIES) {
        stopTranscodePolling()
        ElMessage.warning('部分视频仍在后台处理，可稍后刷新页面查看结果')
      }
      return
    }
    stopTranscodePolling()
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
  void loadComicStatus()
}

/** 读取漫画生命周期状态（READY/REFRESHING 等），决定刷新按钮可用性；失败时保持禁用不阻断主体。 */
async function loadComicStatus() {
  try {
    const res = await comicApi.detail(comicId)
    comicStatus.value = res.data.status ?? ''
  } catch {
    comicStatus.value = ''
  }
}

const refreshDisabled = computed(() =>
  refreshInFlight.value || refreshSubmitted.value || comicStatus.value !== 'READY'
)

const refreshButtonLabel = computed(() => {
  if (refreshInFlight.value) return '提交中...'
  if (refreshSubmitted.value) return '已提交'
  if (comicStatus.value === 'REFRESHING') return '刷新中...'
  if (comicStatus.value && comicStatus.value !== 'READY') return '仅 READY 可刷新'
  return '刷新元数据'
})

const refreshButtonTitle = computed(() => {
  if (refreshInFlight.value) return '正在提交刷新请求，请稍候'
  if (refreshSubmitted.value) return '本次刷新已提交，等待后台扫描与合并完成'
  if (comicStatus.value === 'REFRESHING') return '元数据刷新任务正在后台执行'
  if (comicStatus.value && comicStatus.value !== 'READY') return `漫画状态 ${comicStatus.value}，仅 READY 可刷新`
  return '重读 HQ 目录生成快照并与数据库合并（异步任务）'
})

async function onRefreshMetadata() {
  if (refreshDisabled.value) return
  try {
    await ElMessageBox.confirm(
      '将重读该漫画的 HQ 目录并按章节生成快照，与数据库安全合并。提交后进入后台异步执行，期间不可重复刷新。是否继续？',
      '刷新元数据',
      { type: 'warning', confirmButtonText: '刷新', cancelButtonText: '取消' }
    )
  } catch { return }
  refreshInFlight.value = true
  try {
    const result = await storageService.requestMetadataRefresh(comicId)
    refreshSubmitted.value = true
    const taskIdText = result.taskId != null ? `，任务 #${result.taskId}` : ''
    try {
      await ElMessageBox.alert(
        `元数据刷新任务已提交${taskIdText}，正在后台扫描并合并。可在任务中心查看进度。`,
        '刷新已提交',
        { confirmButtonText: '前往任务中心', type: 'success' }
      )
      router.push('/manage/import/tasks')
    } catch {
      // 用户选择留在本页
    }
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '刷新失败'
    ElMessage.error(message)
  } finally {
    refreshInFlight.value = false
  }
}

async function onDeleteHQ() {
  try {
    await ElMessageBox.confirm('确定删除该漫画的所有 HQ 原图？LQ 文件保留。', '删除 HQ', { type: 'warning' })
  } catch { return }
  try {
    await storageService.executeOperation({ type: StorageOperationType.DeleteHQ, comicId })
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
    await storageService.executeOperation({ type: StorageOperationType.DeleteHQ, comicId, chapterId })
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
    await storageService.executeOperation({ type: StorageOperationType.GenerateLQ, comicId })
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
    await storageService.executeOperation({ type: StorageOperationType.GenerateLQ, comicId, chapterId })
    ElMessage.success('已提交')
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '操作失败'
    ElMessage.error(message)
  }
}

async function onTranscode() {
  try {
    await ElMessageBox.confirm('确认为该漫画的所有视频进行转码？', '视频转码', {
      type: 'info',
      confirmButtonText: '开始转码',
      cancelButtonText: '取消',
    })
  } catch { return }
  try {
    const result = await storageService.transcodeVideos(comicId)
    ElMessage.success(`已提交 ${result.itemCount} 个视频转码任务`)
    await loadData()
    startTranscodePolling()
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '转码失败'
    ElMessage.error(message)
  }
}

async function onExportZip() {
  try {
    const task = await exportService.createExport(comicId)
    ElMessage.success('导出任务已提交，正在跳转任务中心')
    router.push({ path: '/manage/import/tasks', query: { comicId: String(task.comicId) } })
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '导出失败'
    ElMessage.error(message)
  }
}

async function onTrashComic() {
  const title = comic.value?.title ?? ''
  try {
    await ElMessageBox.confirm(
      `确定将「${title}」移入回收站？数据库记录与本地文件将进入 7 天保留期，期间可恢复或永久清理。`,
      '移入回收站',
      { type: 'warning', confirmButtonText: '移入回收站' }
    )
  } catch { return }
  try {
    await comicApi.delete(comicId)
    ElMessage.success('已移入回收站，回收任务已提交')
    router.push('/manage/storage')
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '操作失败')
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
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 13px;
  cursor: not-allowed;
  white-space: nowrap;
}

.refresh-btn--ready {
  color: var(--accent);
  border-color: var(--accent);
  cursor: pointer;
}

.refresh-btn--ready:hover {
  background: color-mix(in srgb, var(--accent) 8%, transparent);
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
