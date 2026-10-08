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
    <StatGrid :columns="4" :mobile-columns="2">
      <StatCard
        compact
        label="全库占用"
        :value="isComplete ? formatSize(total ?? 0) : '待完成统计'"
        description="HQ、LQ 与缩略图合计"
      />
      <StatCard
        compact
        label="HQ 原文件"
        :value="stats ? formatSize(stats.hqBytes) : '—'"
        :description="isComplete ? '全库占用的 ' + hqPercent + '%' : '数据库登记的就绪文件'"
      />
      <StatCard
        compact
        label="LQ 阅读副本"
        :value="stats ? formatSize(stats.lqBytes) : '—'"
        :description="isComplete ? '全库占用的 ' + lqPercent + '%' : '数据库登记的就绪图片'"
      />
      <StatCard
        compact
        label="缩略图"
        :value="stats?.snapshotAvailable ? formatSize(stats.thumbBytes) : '尚未统计'"
        :description="stats?.snapshotAvailable ? stats.thumbFileCount + ' 个文件 · 最近成功快照' : '后台核对目录容量'"
      />
    </StatGrid>
    <div class="snapshot-meta">
      <p
        class="snapshot-status"
        :class="{ 'snapshot-status--error': error || stats?.refreshStatus === 'FAILED' }"
        role="status"
      >
        <span class="snapshot-dot" aria-hidden="true" />{{ refreshMessage }}
      </p>
      <span v-if="updatedTime">缩略图更新于 {{ updatedTime }}</span>
    </div>
  </section>
</template>

<style scoped>
.storage-overview {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
}
.snapshot-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2) var(--space-4);
  color: var(--text-secondary);
  font-size: var(--text-xs);
}
.snapshot-status {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  line-height: 1.5;
}
.snapshot-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-muted);
  flex-shrink: 0;
}
.snapshot-status--error {
  color: var(--warning);
}
.snapshot-status--error .snapshot-dot {
  background: currentColor;
}
</style>
