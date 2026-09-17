<script setup lang="ts">
import { computed } from 'vue'
import { StatusBadge } from '@/shared/ui/status-badge'
import { managementTaskStatusLabel, managementTaskStatusTone } from '@/entities/task/model/labels'
import type { ManagementTaskStatus } from '@/entities/task/model/types'

const props = withDefaults(
  defineProps<{
    status: ManagementTaskStatus
    appearance?: 'tag' | 'dot'
    size?: 'small' | 'default' | 'large'
  }>(),
  { appearance: 'tag', size: 'default' },
)
const tone = computed(() => managementTaskStatusTone(props.status, props.appearance))
</script>

<template>
  <StatusBadge :label="managementTaskStatusLabel(status)" :tone="tone" :appearance="appearance" :size="size" />
</template>
