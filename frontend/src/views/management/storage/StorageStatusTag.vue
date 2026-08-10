<script setup lang="ts">
import { computed } from 'vue'
import type { TranscodeStatus } from '@/types'

const props = defineProps<{
  status: string
  type: 'hq' | 'lq' | 'transcode'
}>()

/** 转码状态标签映射：六状态逐一穷举，随后端 TranscodeStatus 枚举同步维护。 */
const TRANSCODE_STATUS_MAP: Record<TranscodeStatus, { type: string; text: string }> = {
  NOT_NEEDED: { type: 'info', text: '无需转码' },
  REQUIRED: { type: 'warning', text: '需要转码' },
  QUEUED: { type: 'info', text: '排队中' },
  TRANSCODING: { type: 'warning', text: '转码中' },
  READY: { type: 'success', text: '已转码' },
  FAILED: { type: 'danger', text: '转码失败' },
}

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
  transcode: TRANSCODE_STATUS_MAP,
}

const tagType = computed(() => STATUS_MAP[props.type]?.[props.status]?.type ?? '')
const tagText = computed(() => STATUS_MAP[props.type]?.[props.status]?.text ?? props.status)
</script>

<template>
  <el-tag :type="tagType" size="small">{{ tagText }}</el-tag>
</template>
