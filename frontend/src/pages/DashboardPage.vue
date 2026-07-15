<template>
  <div class="dashboard-page">
    <header class="page-header">
      <h1 class="page-title">仪表盘</h1>
    </header>

    <div v-loading="loading">
      <template v-if="store.stats">
        <div class="stat-grid">
          <div class="stat-card">
            <div class="stat-icon comic-icon">
              <el-icon :size="24"><Collection /></el-icon>
            </div>
            <div class="stat-info">
              <p class="stat-value">{{ store.stats.comicCount }}</p>
              <p class="stat-label">漫画总数</p>
            </div>
          </div>

          <div class="stat-card">
            <div class="stat-icon page-icon">
              <el-icon :size="24"><Document /></el-icon>
            </div>
            <div class="stat-info">
              <p class="stat-value">{{ store.stats.pageCount }}</p>
              <p class="stat-label">总页数</p>
            </div>
          </div>

          <div class="stat-card">
            <div class="stat-icon tag-icon">
              <el-icon :size="24"><PriceTag /></el-icon>
            </div>
            <div class="stat-info">
              <p class="stat-value">{{ store.stats.tagCount }}</p>
              <p class="stat-label">标签数</p>
            </div>
          </div>

          <div class="stat-card">
            <div class="stat-icon today-icon">
              <el-icon :size="24"><Clock /></el-icon>
            </div>
            <div class="stat-info">
              <p class="stat-value">{{ store.stats.todayImported }}</p>
              <p class="stat-label">今日导入</p>
            </div>
          </div>

          <div class="stat-card">
            <div class="stat-icon storage-icon">
              <el-icon :size="24"><FolderOpened /></el-icon>
            </div>
            <div class="stat-info">
              <p class="stat-value">{{ formatStorage(store.stats.storageUsed) }}</p>
              <p class="stat-label">磁盘占用</p>
            </div>
          </div>

          <div class="stat-card">
            <div class="stat-icon rate-icon">
              <el-icon :size="24"><CircleCheck /></el-icon>
            </div>
            <div class="stat-info">
              <p class="stat-value">{{ store.stats.successRate }}%</p>
              <p class="stat-label">导入成功率</p>
            </div>
          </div>

          <div class="stat-card wide-card">
            <div class="stat-icon import-icon">
              <el-icon :size="24"><Upload /></el-icon>
            </div>
            <div class="stat-info wide-info">
              <p class="stat-label">导入统计</p>
              <div class="import-breakdown">
                <span class="success-count">
                  <el-tag type="success" size="small">成功 {{ store.stats.importSuccessCount }}</el-tag>
                </span>
                <span class="failed-count">
                  <el-tag type="danger" size="small">失败 {{ store.stats.importFailedCount }}</el-tag>
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="recent-section">
          <h2 class="section-title">最近导入</h2>
          <p class="section-hint">前往 <router-link to="/import" class="link">导入管理</router-link> 查看详情</p>
        </div>
      </template>

      <el-empty v-else-if="!loading" description="无法加载统计数据" />
    </div>

    <div class="maintenance-section">
      <h2 class="section-title">存储扫描恢复</h2>
      <div class="maintenance-card">
        <p class="maintenance-desc">
          扫描 HQ 存储目录，与数据库记录比对。缺失 metadata.json 的目录会创建为占位漫画，可从漫画详情页编辑补充信息。
        </p>
        <div class="actions">
          <el-button
            type="primary"
            :loading="scanLoading"
            @click="runScanRecover"
          >
            开始扫描
          </el-button>
        </div>

        <div v-if="scanResult" class="scan-stats">
          <h3 class="stats-title">扫描结果</h3>
          <div class="stats-grid">
            <div class="stats-item"><span class="stats-count">{{ scanResult.scannedComics }}</span><span class="stats-label">扫描目录</span></div>
            <div class="stats-item"><span class="stats-count">{{ scanResult.existingComics }}</span><span class="stats-label">已存在</span></div>
            <div class="stats-item"><span class="stats-count">{{ scanResult.restoredComics }}</span><span class="stats-label">已恢复</span></div>
            <div class="stats-item"><span class="stats-count">{{ scanResult.placeholderComics }}</span><span class="stats-label">占位漫画</span></div>
            <div class="stats-item"><span class="stats-count">{{ scanResult.restoredChapters }}</span><span class="stats-label">恢复章节</span></div>
            <div class="stats-item"><span class="stats-count">{{ scanResult.restoredPages }}</span><span class="stats-label">恢复页面</span></div>
          </div>

          <div v-if="scanResult.placeholders && scanResult.placeholders.length" class="scan-placeholders">
            <h4 class="stats-title">占位漫画</h4>
            <ul class="placeholder-list">
              <li v-for="item in scanResult.placeholders" :key="item" class="placeholder-item">
                <span class="placeholder-name">{{ item }}</span>
                <span class="placeholder-actions">
                  <el-button link type="primary" size="small" @click="editPlaceholder(item)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="deletePlaceholder(item)">删除</el-button>
                </span>
              </li>
            </ul>
          </div>

          <div v-if="scanResult.errors && scanResult.errors.length" class="scan-errors">
            <h4 class="stats-title">错误</h4>
            <ul class="error-list">
              <li v-for="err in scanResult.errors" :key="err">{{ err }}</li>
            </ul>
          </div>
        </div>
      </div>
    </div>

    <div class="maintenance-section">
      <h2 class="section-title">数据库维护</h2>
      <div class="maintenance-card">
        <div class="id-input-row">
          <label class="input-label">Comic ID</label>
          <el-input-number
            v-model="targetComicId"
            :min="1"
            :step="1"
            step-strictly
            placeholder="请输入 Comic ID"
            class="id-input"
            @change="onComicIdChange"
          />
        </div>

        <div v-if="detailLoading" class="state-loading">加载漫画信息中...</div>
        <div v-else-if="detailError" class="state-error">{{ detailError }}</div>

        <div v-else-if="targetComic" class="comic-info">
          <div class="info-row">
            <span class="info-label">标题</span>
            <span class="info-value">{{ targetComic.title }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">作者</span>
            <span class="info-value">{{ targetComic.author || '-' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">状态</span>
            <el-tag :type="statusTagType" size="small">{{ targetComic.status }}</el-tag>
          </div>
        </div>

        <div class="actions">
          <el-button
            type="danger"
            :disabled="!canDelete"
            :loading="deleteLoading"
            @click="confirmDelete"
          >
            删除数据库记录
          </el-button>
          <span v-if="targetComic && isUnfinished" class="unfinished-hint">
            该漫画处于未完成导入状态，暂不可删除
          </span>
        </div>

        <div v-if="deleteStats" class="delete-stats">
          <h3 class="stats-title">删除统计</h3>
          <div class="stats-grid">
            <div class="stats-item"><span class="stats-count">{{ deleteStats.comic }}</span><span class="stats-label">漫画</span></div>
            <div class="stats-item"><span class="stats-count">{{ deleteStats.catalog }}</span><span class="stats-label">目录</span></div>
            <div class="stats-item"><span class="stats-count">{{ deleteStats.chapter }}</span><span class="stats-label">章节</span></div>
            <div class="stats-item"><span class="stats-count">{{ deleteStats.page }}</span><span class="stats-label">页面</span></div>
            <div class="stats-item"><span class="stats-count">{{ deleteStats.tag }}</span><span class="stats-label">标签</span></div>
            <div class="stats-item"><span class="stats-count">{{ deleteStats.history }}</span><span class="stats-label">阅读历史</span></div>
          </div>
        </div>

        <p class="maintenance-hint">
          <el-icon :size="14"><Warning /></el-icon>
          仅删除数据库记录，本地 HQ / LQ / 缩略图文件不会被删除
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import { useRouter } from 'vue-router'
import {
  Collection,
  Document,
  PriceTag,
  Clock,
  FolderOpened,
  CircleCheck,
  Upload,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDashboardStore } from '@/stores/dashboard-store'
import { comicApi, adminApi } from '@/services/api'
import { STATUS_COLOR_MAP } from '@/types'
import type { ComicDetailVO, ComicDeleteStats, ScanRecoverResultDTO } from '@/types'

const store = useDashboardStore()
const router = useRouter()
const loading = ref(true)

function formatStorage(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(1) + ' GB'
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB'
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return bytes + ' B'
}

const UNFINISHED_STATUSES = ['IMPORTING', 'PARSING', 'PENDING', 'PROCESSING', 'RETRYING']

const targetComicId = ref<number | null>(null)
const targetComic = ref<ComicDetailVO | null>(null)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)
const deleteLoading = ref(false)
const deleteStats = ref<ComicDeleteStats | null>(null)
const scanLoading = ref(false)
const scanResult = ref<ScanRecoverResultDTO | null>(null)

const isUnfinished = computed(() =>
  targetComic.value
    ? UNFINISHED_STATUSES.includes(targetComic.value.status)
    : false
)

const canDelete = computed(() => Boolean(targetComic.value && !isUnfinished.value && !deleteLoading.value))

const statusTagType = computed(() => {
  const type = STATUS_COLOR_MAP[targetComic.value?.status ?? '']
  return (type || 'info') as 'success' | 'info' | 'warning' | 'danger'
})

async function onComicIdChange(val: number | null | undefined) {
  targetComic.value = null
  detailError.value = null
  deleteStats.value = null

  if (typeof val !== 'number' || val < 1) return
  await loadComicDetail(val)
}

async function loadComicDetail(id: number) {
  detailLoading.value = true
  detailError.value = null
  try {
    const res = await comicApi.detail(id)
    targetComic.value = res.data as ComicDetailVO
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    detailError.value = msg || '加载漫画信息失败'
  } finally {
    detailLoading.value = false
  }
}

async function confirmDelete() {
  if (!targetComic.value) return

  const messageText =
    '确认删除数据库记录？\n\n' +
    '此操作会彻底删除该漫画在数据库中的业务数据，包括：\n' +
    '- 漫画信息\n' +
    '- 目录、章节、页面\n' +
    '- 标签关联\n' +
    '- 阅读历史\n\n' +
    '本地 HQ / LQ / 缩略图文件不会被删除，\n' +
    '之后可以使用「数据库重建」重新扫描本地文件并恢复漫画数据库记录。\n\n' +
    '注意：手动添加的标签、分类、阅读历史等数据库信息将不会恢复。'

  try {
    await ElMessageBox.confirm(
      h('div', { style: 'white-space: pre-wrap; line-height: 1.6;' }, messageText),
      '确认删除',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
        customStyle: { width: '480px' },
      }
    )

    deleteLoading.value = true
    const res = await adminApi.deleteComic(targetComic.value.id)
    deleteStats.value = res.data as ComicDeleteStats
    ElMessage.success('数据库记录已删除')
  } catch (err: unknown) {
    if (err === 'cancel') return
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    if (msg) ElMessage.error(msg)
  } finally {
    deleteLoading.value = false
  }
}

async function runScanRecover() {
  scanLoading.value = true
  scanResult.value = null
  try {
    const res = await adminApi.scanRecover()
    scanResult.value = res.data as ScanRecoverResultDTO
    ElMessage.success('存储扫描完成')
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '扫描失败')
  } finally {
    scanLoading.value = false
  }
}

function parsePlaceholderId(item: string): number | null {
  const match = item.match(/(\d+)$/)
  return match ? parseInt(match[1], 10) : null
}

function editPlaceholder(item: string) {
  const id = parsePlaceholderId(item)
  if (id) {
    router.push(`/comics/${id}/edit`)
  }
}

async function deletePlaceholder(item: string) {
  const id = parsePlaceholderId(item)
  if (!id) return

  try {
    await ElMessageBox.confirm(`确认删除占位漫画 ${id} 的数据库记录？`, '确认删除', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await adminApi.deleteComic(id)
    ElMessage.success('占位漫画已删除')
    if (scanResult.value) {
      scanResult.value.placeholders = scanResult.value.placeholders.filter((p) => p !== item)
      scanResult.value.placeholderComics--
    }
  } catch (err: unknown) {
    if (err === 'cancel') return
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    if (msg) ElMessage.error(msg)
  }
}

onMounted(async () => {
  loading.value = true
  await store.fetch()
  loading.value = false
})
</script>

<style scoped>
.dashboard-page {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 40px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 12px;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.stat-card:hover {
  border-color: var(--accent-border);
  box-shadow: var(--shadow);
}

.wide-card {
  grid-column: span 2;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.comic-icon { background: rgba(99, 102, 241, 0.15); color: #818cf8; }
.page-icon { background: rgba(34, 197, 94, 0.15); color: #4ade80; }
.tag-icon { background: rgba(245, 158, 11, 0.15); color: #fbbf24; }
.today-icon { background: rgba(14, 165, 233, 0.15); color: #38bdf8; }
.storage-icon { background: rgba(168, 85, 247, 0.15); color: #c084fc; }
.rate-icon { background: rgba(236, 72, 153, 0.15); color: #f472b6; }
.import-icon { background: rgba(20, 184, 166, 0.15); color: #2dd4bf; }

.stat-info {
  flex: 1;
  min-width: 0;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0 0 2px;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: var(--text);
  margin: 0;
}

.wide-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.import-breakdown {
  display: flex;
  gap: 8px;
}

.recent-section {
  margin-top: 40px;
}

.section-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-h);
  margin: 0 0 4px;
}

.section-hint {
  font-size: 14px;
  color: var(--text);
  margin: 0;
}

.link {
  color: var(--accent);
  text-decoration: none;
}

.link:hover {
  text-decoration: underline;
}

@media (max-width: 768px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .wide-card {
    grid-column: span 2;
  }
}

@media (max-width: 480px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }

  .wide-card {
    grid-column: span 1;
  }
}

.maintenance-section {
  margin-top: 48px;
}

.maintenance-card {
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
}

.id-input-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.input-label {
  font-size: 14px;
  color: var(--text);
  font-weight: 500;
}

.id-input {
  width: 160px;
}

.state-loading,
.state-error {
  font-size: 14px;
  margin-bottom: 16px;
}

.state-loading {
  color: var(--text);
}

.state-error {
  color: var(--el-color-danger, #ef4444);
}

.comic-info {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.info-label {
  color: var(--text);
  min-width: 48px;
}

.info-value {
  color: var(--text-h);
  font-weight: 500;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.unfinished-hint {
  font-size: 13px;
  color: var(--el-color-warning, #f59e0b);
}

.delete-stats {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}

.stats-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-h);
  margin: 0 0 12px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
  gap: 12px;
}

.stats-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  background: var(--bg-surface);
  border-radius: 8px;
}

.stats-count {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-h);
}

.stats-label {
  font-size: 12px;
  color: var(--text);
}

.maintenance-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--text);
}

.maintenance-desc {
  font-size: 14px;
  color: var(--text);
  margin: 0 0 16px;
  line-height: 1.6;
}

.scan-stats {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}

.scan-placeholders,
.scan-errors {
  margin-top: 12px;
}

.placeholder-list,
.error-list {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  color: var(--text);
}

.error-list {
  color: var(--el-color-danger, #ef4444);
}

.placeholder-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.placeholder-name {
  flex: 1;
}

.placeholder-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
</style>
