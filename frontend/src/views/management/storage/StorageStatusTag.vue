<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: string
  type: 'hq' | 'lq' | 'transcode'
}>()

const STATUS_MAP: Record<string, Record<string, { type: string; text: string }>> = {
  hq: {
    READY: { type: 'success', text: 'HQ 就绪' },
    DELETED: { type: 'info', text: 'HQ 已删' },
    MIXED: { type: 'warning', text: '部分已删' },
    PENDING: { type: 'warning', text: '待处理' },
    MISSING: { type: 'danger', text: 'HQ 缺失' },
    EMPTY: { type: '', text: '无数据' },
  },
  lq: {
    READY: { type: 'success', text: 'LQ 就绪' },
    NOT_GENERATED: { type: 'warning', text: '未生成' },
    MIXED: { type: 'danger', text: '部分失败' },
    FAILED: { type: 'danger', text: '生成失败' },
    EMPTY: { type: '', text: '无数据' },
    QUEUED: { type: 'warning', text: '排队中' },
    GENERATING: { type: 'warning', text: '生成中' },
  },
  transcode: {
    NOT_NEEDED: { type: '', text: '' },
    PENDING: { type: 'warning', text: '转码中' },
    PROCESSING: { type: 'warning', text: '转码中' },
    DONE: { type: 'success', text: '已转码' },
    FAILED: { type: 'danger', text: '失败' },
  },
}

const tagType = computed(() => STATUS_MAP[props.type]?.[props.status]?.type ?? '')
const tagText = computed(() => STATUS_MAP[props.type]?.[props.status]?.text ?? props.status)
const tagClass = computed(() => `storage-status-tag--${tagType.value || 'neutral'}`)
</script>

<template>
  <el-tag class="storage-status-tag" :class="tagClass" :type="tagType" size="small">{{ tagText }}</el-tag>
</template>

<style scoped>
.storage-status-tag--success { color: var(--success) !important; border-color: var(--success) !important; background: var(--bg-surface) !important; }
.storage-status-tag--warning { color: var(--warning) !important; border-color: var(--warning) !important; background: var(--bg-surface) !important; }
.storage-status-tag--danger { color: var(--danger) !important; border-color: var(--danger) !important; background: var(--bg-surface) !important; }
.storage-status-tag--info { color: var(--text-secondary) !important; border-color: var(--border-strong) !important; background: var(--bg-surface) !important; }
.storage-status-tag--neutral { color: var(--text-muted) !important; border-color: var(--border) !important; background: var(--bg-surface) !important; }
</style>
