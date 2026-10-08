<script setup lang="ts">
import { computed } from 'vue'
import { StatusBadge } from '@/shared/ui/status-badge'

const props = defineProps<{
  status: string
  type: 'hq' | 'lq' | 'transcode'
}>()

type StorageTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'
const STATUS_MAP: Record<string, Record<string, { type: StorageTone; text: string }>> = {
  hq: {
    READY: { type: 'success', text: 'HQ 就绪' },
    DELETED: { type: 'info', text: 'HQ 已删' },
    MIXED: { type: 'warning', text: '部分已删' },
    PENDING: { type: 'warning', text: '待处理' },
    MISSING: { type: 'danger', text: 'HQ 缺失' },
    EMPTY: { type: 'neutral', text: '无数据' },
  },
  lq: {
    READY: { type: 'success', text: 'LQ 就绪' },
    NOT_GENERATED: { type: 'warning', text: '未生成' },
    MIXED: { type: 'danger', text: '部分失败' },
    FAILED: { type: 'danger', text: '生成失败' },
    EMPTY: { type: 'neutral', text: '无数据' },
    QUEUED: { type: 'warning', text: '排队中' },
    GENERATING: { type: 'warning', text: '生成中' },
  },
  transcode: {
    NOT_NEEDED: { type: 'neutral', text: '' },
    PENDING: { type: 'warning', text: '转码中' },
    PROCESSING: { type: 'warning', text: '转码中' },
    DONE: { type: 'success', text: '已转码' },
    FAILED: { type: 'danger', text: '失败' },
  },
}

const tagType = computed(() => STATUS_MAP[props.type]?.[props.status]?.type ?? 'neutral')
const tagText = computed(() => STATUS_MAP[props.type]?.[props.status]?.text ?? props.status)
</script>

<template>
  <StatusBadge class="storage-status-tag" :tone="tagType" :label="tagText" size="small" />
</template>
