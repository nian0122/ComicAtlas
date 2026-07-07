<template>
  <article class="history-card" @click="emit('continue')">
    <div class="cover-area">
      <el-image :src="item.coverUrl" fit="cover" class="cover">
        <template #error>
          <div class="cover-placeholder">
            <el-icon :size="28"><PictureFilled /></el-icon>
          </div>
        </template>
      </el-image>
      <div v-if="item.progressPercent" class="progress-overlay">
        <div class="progress-fill" :style="{ width: `${item.progressPercent}%` }" />
      </div>
    </div>

    <div class="info-area">
      <h3 class="comic-title">{{ item.comicTitle || `漫画 #${item.comicId}` }}</h3>
      <p class="chapter-line">
        第 {{ item.chapterNo }} 话
        <span class="dot">·</span>
        {{ item.pageNumber }} / {{ item.totalPages || '?' }} 页
        <span class="dot">·</span>
        <span class="percent">{{ item.progressPercent }}%</span>
      </p>
      <p class="time-line">{{ formatRelative(item.updatedAt) }}</p>

      <div class="card-actions" @click.stop>
        <button class="continue-btn" @click="emit('continue')">
          继续阅读 ▶
        </button>
        <button class="ghost-btn-sm" @click="emit('detail')">
          详情
        </button>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { PictureFilled } from '@element-plus/icons-vue'
import type { HistoryVO } from '@/types'

defineProps<{
  item: HistoryVO
}>()

const emit = defineEmits<{
  continue: []
  detail: []
}>()

function formatRelative(ts: string): string {
  if (!ts) return ''
  const d = new Date(ts)
  const diffMs = Date.now() - d.getTime()
  const diffMin = Math.floor(diffMs / 60000)

  if (diffMin < 1) return '刚刚阅读'
  if (diffMin < 60) return `${diffMin} 分钟前`
  if (diffMin < 1440) return `${Math.floor(diffMin / 60)} 小时前`
  if (diffMin < 43200) return `${Math.floor(diffMin / 1440)} 天前`
  return d.toLocaleDateString('zh-CN')
}
</script>

<style scoped>
.history-card {
  display: flex;
  gap: var(--space-base);
  padding: var(--space-base);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: transform 150ms ease, border-color 150ms ease, box-shadow 150ms ease;
}

.history-card:hover {
  transform: translateX(4px);
  border-color: var(--border-strong);
  box-shadow: var(--shadow-sm);
}

/* Cover */
.cover-area {
  position: relative;
  flex-shrink: 0;
  width: 96px;
  height: 128px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--bg);
}

.cover {
  width: 100%;
  height: 100%;
  display: block;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}

.progress-overlay {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: rgba(255, 255, 255, 0.15);
}

.progress-fill {
  height: 100%;
  background: var(--accent);
}

/* Info */
.info-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.comic-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-h);
  margin: 0 0 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-line {
  font-size: 13px;
  color: var(--text);
  margin: 0 0 4px;
}

.dot {
  margin: 0 5px;
  color: var(--text-muted);
}

.percent {
  color: var(--accent);
  font-weight: 600;
}

.time-line {
  font-size: 12px;
  color: var(--text-muted);
  margin: 0 0 var(--space-base);
}

.card-actions {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.continue-btn {
  padding: 6px 14px;
  background: var(--accent);
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 150ms ease;
}

.continue-btn:hover {
  background: var(--accent-hover);
}

.ghost-btn-sm {
  padding: 6px 12px;
  background: transparent;
  color: var(--text-h);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 150ms ease;
}

.ghost-btn-sm:hover {
  background: var(--bg);
}

@media (max-width: 640px) {
  .cover-area {
    width: 80px;
    height: 106px;
  }
  .chapter-line {
    font-size: 12px;
  }
}
</style>
