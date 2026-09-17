<script setup lang="ts">
import { computed } from 'vue'
import { StatusBadge } from '@/shared/ui/status-badge'
import type { ComicStatus } from '@/entities/comic/model/types'
import { comicStatusMeta } from '@/entities/comic/model/status'

const props = withDefaults(
  defineProps<{
    status: ComicStatus
    appearance?: 'tag' | 'dot'
  }>(),
  { appearance: 'tag' },
)
const meta = computed(() => comicStatusMeta(props.status))
</script>

<template>
  <el-tooltip :content="meta.description" placement="top">
    <StatusBadge
      class="comic-status-tag"
      :label="meta.label"
      :tone="meta.tone"
      :code="appearance === 'tag' ? status : undefined"
      :appearance="appearance"
    />
  </el-tooltip>
</template>
