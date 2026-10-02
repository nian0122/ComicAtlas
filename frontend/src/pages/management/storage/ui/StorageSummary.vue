<script setup lang="ts">
import { StatGrid } from '@/shared/ui/management-panel'
import { StatCard } from '@/shared/ui/management-panel'
import { formatBytes as formatSize } from '@/shared/lib/format/bytes'
import { computed } from 'vue'
import type { StorageStats } from '@/entities/storage'

const props = defineProps<{
  stats: StorageStats | null
  loading: boolean
  error: string
}>()

const total = computed(() => props.stats?.totalBytes ?? null)
const isComplete = computed(() => props.stats?.snapshotAvailable === true && total.value !== null)
const hqPercent = computed(() => percent(props.stats?.hqBytes))
const lqPercent = computed(() => percent(props.stats?.lqBytes))
const thumbPercent = computed(() => percent(props.stats?.thumbBytes))
const refreshMessage = computed(() => {
  if (props.error) return props.error
  if (!props.stats) return props.loading ? '正在读取统计…' : '统计尚不可用'
  if (props.stats.refreshStatus === 'FAILED')
    return props.stats.snapshotAvailable ? '容量核对失败，保留上次成功结果' : '容量核对失败，尚无可用快照'
  if (props.stats.refreshStatus === 'RUNNING' || props.stats.refreshStatus === 'PENDING')
    return props.stats.snapshotAvailable ? '正在后台核对缩略图容量，暂显示上次结果' : '首次容量统计中…'
  return '缩略图容量已核对'
})
const updatedTime = computed(() => {
  const value = props.stats?.thumbUpdatedAt
  if (!value) return ''
  // 后端快照时间为 UTC，无偏移的数据库时间需要明确按 UTC 解释。
  const date = new Date(/Z$|[+-]\d{2}:\d{2}$/.test(value) ? value : `${value}Z`)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false })
})

function percent(bytes: number | undefined): number {
  if (!bytes || total.value === null || total.value <= 0) return 0
  return Math.round((bytes / total.value) * 100)
}
</script>

<template>
  <section class="storage-overview" aria-label="全库容量统计">
    <div class="total-card">
      <span class="overview-kicker">LIBRARY CAPACITY</span>
      <strong>{{ isComplete ? formatSize(total ?? 0) : '待完成统计' }}</strong>
      <span>活动媒体登记容量 + 缩略图目录容量</span>
      <div v-if="isComplete" class="capacity-bar" aria-label="存储占用分布">
        <i class="bar-hq" :style="{ width: `${hqPercent}%` }" />
        <i class="bar-lq" :style="{ width: `${lqPercent}%` }" />
        <i class="bar-thumb" :style="{ width: `${thumbPercent}%` }" />
      </div>
      <div v-if="isComplete" class="distribution-legend">
        <span><i class="dot dot-hq" />HQ {{ hqPercent }}%</span><span><i class="dot dot-lq" />LQ {{ lqPercent }}%</span
        ><span><i class="dot dot-thumb" />缩略图 {{ thumbPercent }}%</span>
      </div>
      <p
        class="snapshot-status"
        :class="{ 'snapshot-status--error': error || stats?.refreshStatus === 'FAILED' }"
        role="status"
      >
        {{ refreshMessage }}
      </p>
      <span v-if="updatedTime">缩略图更新于 {{ updatedTime }}</span>
    </div>
    <StatGrid class="stat-grid" :columns="3">
      <StatCard
        label="HQ 就绪文件"
        :value="stats ? formatSize(stats.hqBytes) : '—'"
        :description="isComplete ? '原始质量 · ' + hqPercent + '%' : '数据库登记的就绪文件'"
        tone="primary"
      />
      <StatCard
        label="LQ 就绪图片"
        :value="stats ? formatSize(stats.lqBytes) : '—'"
        :description="isComplete ? '阅读优化 · ' + lqPercent + '%' : '数据库登记的就绪图片'"
        tone="success"
      />
      <StatCard
        label="缩略图"
        :value="stats?.snapshotAvailable ? formatSize(stats.thumbBytes) : '尚未统计'"
        :description="stats?.snapshotAvailable ? stats.thumbFileCount + ' 个文件 · 最近成功快照' : '后台核对目录容量'"
        tone="warning"
      />
    </StatGrid>
  </section>
</template>

<style scoped>
.storage-overview {
  display: grid;
  grid-template-columns: minmax(300px, 1.1fr) minmax(0, 1.9fr);
  gap: var(--space-base);
  margin-bottom: var(--space-xl);
}
.total-card {
  display: grid;
  align-content: center;
  gap: var(--space-sm);
  min-height: 190px;
  padding: var(--space-xl);
  border: 1px solid var(--border-strong);
  background: linear-gradient(145deg, var(--surface-highlight), var(--bg-surface));
}
.overview-kicker {
  color: var(--accent);
  font: 800 10px var(--mono);
  letter-spacing: 0.16em;
}
.total-card strong {
  color: var(--text-primary);
  font-size: clamp(2rem, 4vw, 3rem);
  letter-spacing: -0.04em;
}
.total-card > span:not(.overview-kicker) {
  color: var(--text-muted);
  font-size: 11px;
}
.capacity-bar {
  display: flex;
  height: 8px;
  overflow: hidden;
  margin-top: var(--space-sm);
  background: var(--bg-primary);
}
.capacity-bar i {
  display: block;
  min-width: 0;
  transition: width 300ms ease;
}
.bar-hq,
.dot-hq {
  background: var(--accent);
}
.bar-lq,
.dot-lq {
  background: var(--success);
}
.bar-thumb,
.dot-thumb {
  background: var(--warning);
}
.distribution-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  color: var(--text-secondary);
  font-size: 10px;
}
.distribution-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.snapshot-status {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--text-xs);
  line-height: 1.5;
}
.snapshot-status--error {
  color: var(--warning);
}

@media (max-width: 900px) {
  .storage-overview {
    grid-template-columns: 1fr;
  }
}
</style>
