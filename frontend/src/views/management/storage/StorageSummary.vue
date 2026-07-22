<script setup lang="ts">
import type { StorageStats } from '@/types'

defineProps<{
  stats: StorageStats | null
}>()

function formatSize(bytes: number | undefined): string {
  if (!bytes || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
}
</script>

<template>
  <section class="stat-grid">
    <div class="stat-card">
      <span class="stat-value">{{ formatSize(stats?.totalBytes) }}</span>
      <span class="stat-label">总大小</span>
    </div>
    <div class="stat-card">
      <span class="stat-value">{{ formatSize(stats?.hqBytes) }}</span>
      <span class="stat-label">HQ 占用</span>
    </div>
    <div class="stat-card">
      <span class="stat-value">{{ formatSize(stats?.lqBytes) }}</span>
      <span class="stat-label">LQ 占用</span>
    </div>
    <div class="stat-card">
      <span class="stat-value">{{ formatSize(stats?.thumbBytes) }}</span>
      <span class="stat-label">缩略图</span>
    </div>
  </section>
</template>

<style scoped>
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

@media (max-width: 768px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
