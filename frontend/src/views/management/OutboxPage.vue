<template>
  <div class="management-list-page">
    <header class="page-header">
      <div>
        <p class="page-eyebrow">SYSTEM / OUTBOX</p>
        <h1 class="page-title">Outbox 监控</h1>
        <p class="page-subtitle">查看待投递、失败和历史消息数量。</p>
      </div>
      <button class="ghost-btn" :disabled="loading" @click="load">刷新</button>
    </header>
    <div v-if="error" class="state error">{{ error }}</div>
    <div v-else-if="loading && !stats" class="state loading">加载中...</div>
    <section v-else-if="stats" class="stats-grid">
      <article><span>待投递</span><strong>{{ stats.pending }}</strong></article>
      <article><span>失败</span><strong>{{ stats.failed }}</strong></article>
      <article><span>总消息</span><strong>{{ stats.total }}</strong></article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { outboxApi } from '@/services/api'
import type { OutboxStats } from '@/types'

const stats = ref<OutboxStats | null>(null)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    stats.value = (await outboxApi.stats()).data
  } catch (cause: unknown) {
    error.value = cause instanceof Error ? cause.message : '加载 Outbox 统计失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.management-list-page { max-width: 1100px; }
.page-header { display: flex; justify-content: space-between; gap: 24px; margin-bottom: 24px; }
.page-eyebrow { color: var(--accent); font-size: 11px; letter-spacing: .14em; }
.page-title { margin: 6px 0; color: var(--text-primary); }
.page-subtitle { margin: 0; color: var(--text-secondary); }
.stats-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.stats-grid article { display: grid; gap: 10px; padding: 20px; border: 1px solid var(--border); background: var(--bg-surface); }
.stats-grid span { color: var(--text-secondary); }
.stats-grid strong { color: var(--text-primary); font-size: 28px; }
</style>
