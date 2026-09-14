<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/status/StatusBadge.vue'
import { managementTaskStatusLabel, managementTaskStatusTone } from '@/features/task/labels'
import type { ManagementTaskStatus } from '@/features/task/types'

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
