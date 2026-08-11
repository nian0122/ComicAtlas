<template>
  <div class="management-home-page">
    <header><h1>仓库控制台</h1><p>后端能力入口与运行状态总览。</p></header>
    <section class="summary-grid">
      <article><span>管理任务</span><strong>{{ taskTotal ?? '—' }}</strong><small>全部统一任务</small></article>
      <article><span>Outbox 待发送</span><strong>{{ outbox?.pending ?? '—' }}</strong><small>总计 {{ outbox?.total ?? '—' }}</small></article>
      <article><span>Outbox 失败</span><strong>{{ outbox?.failed ?? '—' }}</strong><small>失败消息</small></article>
      <article><span>存储总量</span><strong>{{ formatBytes(storageTotal) }}</strong><small>HQ + LQ + 缩略图</small></article>
    </section>
    <el-alert v-if="error" :title="error" type="warning" show-icon />
    <section class="entry-grid" aria-label="功能入口">
      <router-link v-for="entry in entries" :key="entry.to" :to="entry.to">
        <strong>{{ entry.title }}</strong><span>{{ entry.description }}</span>
      </router-link>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import axios from 'axios'
import { outboxApi } from '@/services/api'
import { trackedTaskApi } from '@/services/management-capabilities'
import { storageService } from '@/services/storage'
import type { OutboxStats, StorageStats } from '@/types'

const entries = [
  { to: '/manage/status', title: '漫画状态', description: '查看完整生命周期与当前页状态统计' },
  { to: '/manage/operations', title: '漫画操作台', description: '触发存储、导出、回收和恢复并观察状态' },
  { to: '/manage/trash', title: '回收站', description: '查看已回收漫画并恢复或永久清理' },
  { to: '/manage/tasks', title: '任务中心', description: '查看所有异步任务、进度、错误与目标项' },
  { to: '/manage/upload', title: '媒体上传', description: '向章节追加或替换图片和视频' },
  { to: '/manage/structure', title: '目录与媒体结构', description: '维护目录、章节顺序和章节内媒体' },
  { to: '/manage/comics', title: '漫画信息编辑', description: '编辑标题、作者、简介、分类和标签' },
  { to: '/manage/import', title: '导入漫画', description: '单本、批量扫描与导入' },
  { to: '/manage/storage', title: '存储管理', description: '查看 HQ/LQ 使用量与章节资产' },
  { to: '/manage/dlq', title: '死信队列', description: '检查、重放和清理 MQ 死信' },
] as const
const outbox = ref<OutboxStats | null>(null)
const storage = ref<StorageStats | null>(null)
const taskTotal = ref<number | null>(null)
const error = ref('')
const storageTotal = computed(() => storage.value ? storage.value.hqBytes + storage.value.lqBytes + storage.value.thumbBytes : undefined)
function errorMessage(reason: unknown): string { if (axios.isAxiosError<{ message?: string }>(reason)) return reason.response?.data?.message ?? reason.message; return reason instanceof Error ? reason.message : '未知错误' }
function formatBytes(bytes: number | undefined): string { if (bytes === undefined) return '—'; if (bytes >= 1024 ** 4) return `${(bytes / 1024 ** 4).toFixed(1)} TB`; if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`; return `${(bytes / 1024 ** 2).toFixed(1)} MB` }
onMounted(async () => { try { const [tasks, stats, storageStats] = await Promise.all([trackedTaskApi.list({ page: 1, size: 1 }), outboxApi.stats(), storageService.fetchSummary()]); taskTotal.value = tasks.data.total; outbox.value = stats.data; storage.value = storageStats } catch (reason: unknown) { error.value = errorMessage(reason) } })
</script>

<style scoped>
.management-home-page { display: grid; gap: var(--space-8); }
header h1 { margin: 0; color: var(--text-primary); font-size: var(--text-page); }
header p, article span, article small, .entry-grid span { color: var(--text-muted); }
.summary-grid, .entry-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-4); }
article, .entry-grid a { display: grid; gap: var(--space-2); padding: var(--space-5); border: 1px solid var(--border); background: var(--bg-surface); }
article strong { color: var(--text-primary); font-size: 2rem; }
.entry-grid a { min-height: 120px; align-content: center; transition: transform var(--transition-fast), border-color var(--transition-fast); }
.entry-grid a:hover { transform: translateY(-2px); border-color: var(--accent); }
.entry-grid strong { color: var(--text-primary); font-size: var(--text-lg); }
@media (max-width: 1000px) { .summary-grid, .entry-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 480px) { .summary-grid, .entry-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
