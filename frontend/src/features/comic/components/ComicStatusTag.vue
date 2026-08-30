<template>
  <el-tooltip :content="meta.description" placement="top">
    <el-tag :type="meta.tone" effect="dark" class="comic-status-tag">
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

code {
  font-size: 10px;
  opacity: 0.72;
}
</style>
