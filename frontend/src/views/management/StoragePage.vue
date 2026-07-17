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
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/services/management'

interface StorageStats {
  totalBytes: number
  hqBytes: number
  lqBytes: number
  thumbBytes: number
  comicCount: number
}

const stats = reactive<StorageStats>({
  totalBytes: 0,
  hqBytes: 0,
  lqBytes: 0,
  thumbBytes: 0,
  comicCount: 0,
})

const scanning = ref(false)
const rebuilding = ref(false)

function formatSize(bytes: number): string {
  if (!bytes) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
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

async function onScanRecover() {
  scanning.value = true
  try {
    const res = await adminApi.scanRecover()
    const data = res.data as { restoredComics: number; restoredChapters: number; restoredPages: number; placeholderComics: number }
    ElMessage.success(`扫描完成：恢复 ${data.restoredComics} 部漫画，${data.restoredChapters} 个章节，${data.restoredPages} 页`)
    await loadStats()
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
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '重建失败')
  } finally {
    rebuilding.value = false
  }
}

onMounted(loadStats)
</script>

<style scoped>
.storage-page {
  max-width: 960px;
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

@media (max-width: 768px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
