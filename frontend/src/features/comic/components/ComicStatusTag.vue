<template>
  <el-tooltip :content="meta.description" placement="top">
    <el-tag :type="meta.tone" effect="dark" class="comic-status-tag" :class="`comic-status-tag--${meta.tone}`">
      <span>{{ meta.label }}</span>
      <code>{{ status }}</code>
    </el-tag>
  </el-tooltip>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ComicStatus } from '@/entities/comic/types'
import { comicStatusMeta } from '@/features/comic/status'

const props = defineProps<{ readonly status: ComicStatus }>()
const meta = computed(() => comicStatusMeta(props.status))
</script>

<style scoped>
.comic-status-tag {
  gap: var(--space-2);
  height: auto;
  min-height: 28px;
}

.comic-status-tag--success {
  color: var(--success) !important;
  background: color-mix(in srgb, var(--success) 12%, var(--bg-surface)) !important;
  border-color: color-mix(in srgb, var(--success) 48%, var(--border)) !important;
}

.comic-status-tag--warning {
  color: var(--warning) !important;
  background: color-mix(in srgb, var(--warning) 12%, var(--bg-surface)) !important;
  border-color: color-mix(in srgb, var(--warning) 48%, var(--border)) !important;
}

.comic-status-tag--danger {
  color: var(--danger) !important;
  background: color-mix(in srgb, var(--danger) 12%, var(--bg-surface)) !important;
  border-color: color-mix(in srgb, var(--danger) 48%, var(--border)) !important;
}

.comic-status-tag--info,
.comic-status-tag--primary {
  color: var(--text-secondary) !important;
  background: var(--bg-surface) !important;
  border-color: var(--border) !important;
}

code {
  font-size: 10px;
  opacity: 0.72;
}
</style>
