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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import {
  Collection,
  Document,
  PriceTag,
  Clock,
  FolderOpened,
  CircleCheck,
  Upload,
} from '@element-plus/icons-vue'
import { useDashboardStore } from '@/stores/dashboard-store'

const store = useDashboardStore()
const loading = ref(true)

function formatStorage(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(1) + ' GB'
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB'
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return bytes + ' B'
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
</style>
