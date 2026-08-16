<template>
  <div class="comic-workbench-page">
    <header class="workbench-hero">
      <div>
        <p class="eyebrow">COLLECTION CONTROL</p>
        <h1>漫画工作台</h1>
        <p class="hero-copy">从状态巡检到媒体操作，再到磁盘占用，集中处理漫画仓库的日常维护。</p>
      </div>
      <div class="hero-mark" aria-hidden="true"><span>CA</span><i /></div>
    </header>

    <el-tabs v-model="activeTab" class="workbench-tabs" @tab-change="handleTabChange">
      <el-tab-pane name="status">
        <template #label><span class="tab-label"><span class="tab-index">01</span>状态总览</span></template>
        <KeepAlive><ComicStatusPage /></KeepAlive>
      </el-tab-pane>
      <el-tab-pane name="operations">
        <template #label><span class="tab-label"><span class="tab-index">02</span>漫画操作台</span></template>
        <KeepAlive><ComicOperationsPage /></KeepAlive>
      </el-tab-pane>
      <el-tab-pane name="storage">
        <template #label><span class="tab-label"><span class="tab-index">03</span>存储管理</span></template>
        <KeepAlive><StoragePage /></KeepAlive>
      </el-tab-pane>
      <el-tab-pane name="edit">
        <template #label><span class="tab-label"><span class="tab-index">04</span>信息编辑</span></template>
        <KeepAlive><ComicListPage /></KeepAlive>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ComicStatusPage from './ComicStatusPage.vue'
import ComicOperationsPage from './ComicOperationsPage.vue'
import StoragePage from './storage/StoragePage.vue'
import ComicListPage from './ComicListPage.vue'

type WorkbenchTab = 'status' | 'operations' | 'storage' | 'edit'
const route = useRoute()
const router = useRouter()
const validTabs: readonly WorkbenchTab[] = ['status', 'operations', 'storage', 'edit']

function normalizeTab(value: unknown): WorkbenchTab {
  return typeof value === 'string' && validTabs.includes(value as WorkbenchTab)
    ? value as WorkbenchTab
    : 'status'
}

const activeTab = ref<WorkbenchTab>(normalizeTab(route.query.tab))

function handleTabChange(value: string | number): void {
  const tab = normalizeTab(value)
  const query = { ...route.query, tab }
  void router.replace({ query })
}

watch(() => route.query.tab, (value) => {
  activeTab.value = normalizeTab(value)
})
</script>

<style scoped>
.comic-workbench-page { display: grid; gap: var(--space-6); }
.workbench-hero {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  min-height: 172px;
  padding: clamp(var(--space-6), 5vw, var(--space-10));
  overflow: hidden;
  color: #f9f4ea;
  background: linear-gradient(115deg, #080808 0%, #171717 58%, #3a0a0d 150%);
  border: 1px solid rgb(229 9 20 / 28%);
  box-shadow: 0 18px 42px rgba(38, 31, 24, .12);
}
.workbench-hero::after { content: ''; position: absolute; inset: 0; opacity: .22; background-image: linear-gradient(135deg, transparent 42%, rgb(229 9 20 / 20%) 42.3%, transparent 43%), linear-gradient(315deg, transparent 66%, rgb(229 9 20 / 12%) 66.3%, transparent 67%); pointer-events: none; }
.eyebrow { position: relative; z-index: 1; margin: 0 0 var(--space-2); color: var(--accent); font-size: 11px; font-weight: 800; letter-spacing: .22em; }
.workbench-hero h1 { position: relative; z-index: 1; margin: 0; font-family: Georgia, 'Times New Roman', serif; font-size: clamp(2rem, 4vw, 3.2rem); letter-spacing: -.04em; }
.hero-copy { position: relative; z-index: 1; max-width: 540px; margin: var(--space-3) 0 0; color: rgba(249, 244, 234, .72); }
.hero-mark { position: relative; z-index: 1; display: grid; place-items: center; width: 112px; height: 112px; transform: rotate(-8deg); border: 1px solid var(--accent-border); color: var(--accent); font: 700 25px Georgia, serif; }
.hero-mark::before, .hero-mark::after { content: ''; position: absolute; width: 70%; height: 1px; background: rgb(229 9 20 / 34%); }
.hero-mark::before { transform: rotate(45deg); } .hero-mark::after { transform: rotate(-45deg); }
.hero-mark i { position: absolute; width: 8px; height: 8px; border: 1px solid #e4bd7b; border-radius: 50%; }
.workbench-tabs { min-width: 0; }
.workbench-tabs :deep(.el-tabs__header) { margin-bottom: var(--space-6); }
.workbench-tabs :deep(.el-tabs__nav-wrap::after) { background-color: var(--border); }
.workbench-tabs :deep(.el-tabs__active-bar) { height: 3px; background: var(--accent); }
.workbench-tabs :deep(.el-tabs__item) { height: 48px; padding: 0 var(--space-5); color: var(--text-muted); }
.workbench-tabs :deep(.el-tabs__item.is-active) { color: var(--text-primary); }
.tab-label { display: inline-flex; align-items: center; gap: var(--space-3); font-weight: 700; }
.tab-index { color: var(--accent); font: 700 11px ui-monospace, SFMono-Regular, Consolas, monospace; letter-spacing: .08em; }
@media (max-width: 640px) { .workbench-hero { min-height: 150px; } .hero-mark { width: 72px; height: 72px; font-size: 18px; } .workbench-tabs :deep(.el-tabs__item) { padding: 0 var(--space-3); } .tab-label { gap: var(--space-2); font-size: 13px; } }
</style>
