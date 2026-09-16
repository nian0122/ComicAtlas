<script setup lang="ts">
import StatGrid from '@/components/management/StatGrid.vue'
import StatCard from '@/components/management/StatCard.vue'
import { formatBytes as formatSize } from '@/shared/format/bytes'
import { computed } from 'vue'
import type { StorageStats } from '@/features/storage/types'

const props = defineProps<{
  stats: StorageStats | null
}>()

const total = computed(() => {
  const stats = props.stats
  if (!stats) return 0
  return stats.totalBytes || stats.hqBytes + stats.lqBytes + stats.thumbBytes
})
const hqPercent = computed(() => percent(props.stats?.hqBytes))
const lqPercent = computed(() => percent(props.stats?.lqBytes))
const thumbPercent = computed(() => percent(props.stats?.thumbBytes))
const unknownPercent = computed(() => Math.max(0, 100 - hqPercent.value - lqPercent.value - thumbPercent.value))

function percent(bytes: number | undefined): number {
  if (!bytes || total.value <= 0) return 0
  return Math.round((bytes / total.value) * 100)
}
</script>

<template>
  <section class="storage-overview">
    <div class="total-card">
      <span class="overview-kicker">TOTAL STORAGE</span>
      <strong>{{ formatSize(total) }}</strong>
      <span>当前已统计的漫画文件与缩略图</span>
      <div class="capacity-bar" aria-label="存储占用分布">
        <i class="bar-hq" :style="{ width: `${hqPercent}%` }" />
        <i class="bar-lq" :style="{ width: `${lqPercent}%` }" />
        <i class="bar-thumb" :style="{ width: `${thumbPercent}%` }" />
        <i class="bar-unknown" :style="{ width: `${unknownPercent}%` }" />
      </div>
      <div class="distribution-legend">
        <span><i class="dot dot-hq" />HQ {{ hqPercent }}%</span><span><i class="dot dot-lq" />LQ {{ lqPercent }}%</span
        ><span><i class="dot dot-thumb" />缩略图 {{ thumbPercent }}%</span>
      </div>
    </div>
    <StatGrid class="stat-grid" :columns="3">
      <StatCard
        label="HQ 主文件"
        :value="formatSize(stats?.hqBytes)"
        :description="'原始质量 · ' + hqPercent + '%'"
        tone="primary"
      />
      <StatCard
        label="LQ 衍生文件"
        :value="formatSize(stats?.lqBytes)"
        :description="'阅读优化 · ' + lqPercent + '%'"
        tone="success"
      />
      <StatCard
        label="缩略图"
        :value="formatSize(stats?.thumbBytes)"
        :description="'列表预览 · ' + thumbPercent + '%'"
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
  background: linear-gradient(145deg, var(--bg-elevated), var(--bg-surface));
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
.bar-unknown {
  background: var(--border-strong);
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

@media (max-width: 900px) {
  .storage-overview {
    grid-template-columns: 1fr;
  }
}
</style>
