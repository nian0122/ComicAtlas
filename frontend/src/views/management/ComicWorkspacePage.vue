<template>
  <div class="comic-workspace-page">
    <header class="workspace-header">
      <router-link to="/manage/comics" class="back-link">← 漫画列表</router-link>
      <div class="workspace-heading">
        <p class="eyebrow">COMIC / WORKSPACE</p>
        <h1>单本漫画工作区</h1>
        <p>集中处理这一本漫画的信息、目录、媒体和存储。</p>
      </div>
      <span class="comic-id">ID {{ comicId }}</span>
    </header>

    <el-tabs v-model="activeTab" class="workspace-tabs" @tab-change="handleTabChange">
      <el-tab-pane name="operations" label="概览与操作" lazy><ComicOperationsPage /></el-tab-pane>
      <el-tab-pane name="edit" label="信息编辑" lazy><ComicEditPage /></el-tab-pane>
      <el-tab-pane name="content" label="目录与存储" lazy><ComicContentWorkspacePage /></el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ComicOperationsPage from './ComicOperationsPage.vue'
import ComicEditPage from './ComicEditPage.vue'
import ComicContentWorkspacePage from './ComicContentWorkspacePage.vue'

type WorkspaceTab = 'operations' | 'edit' | 'content'
const route = useRoute()
const router = useRouter()
const comicId = Number(route.params.id)
const tabs: readonly WorkspaceTab[] = ['operations', 'edit', 'content']

function normalizeTab(value: unknown): WorkspaceTab {
  if (value === 'structure' || value === 'storage') return 'content'
  return typeof value === 'string' && tabs.includes(value as WorkspaceTab)
    ? value as WorkspaceTab
    : 'operations'
}

const activeTab = ref<WorkspaceTab>(normalizeTab(route.query.tab))

function handleTabChange(value: string | number): void {
  void router.replace({ query: { ...route.query, tab: normalizeTab(value), comicId: String(comicId) } })
  resetManagementScroll()
}

function resetManagementScroll(): void {
  void nextTick(() => {
    const content = document.querySelector<HTMLElement>('.management-content')
    if (content) content.scrollTop = 0
  })
}

watch(() => route.query.tab, (value) => { activeTab.value = normalizeTab(value); resetManagementScroll() })
</script>

<style scoped>
.comic-workspace-page { display: grid; gap: var(--space-6); min-width: 0; }
.workspace-header { display: grid; grid-template-columns: auto 1fr auto; align-items: end; gap: var(--space-6); padding: var(--space-6) 0 var(--space-2); border-bottom: 1px solid var(--border); }
.back-link { align-self: start; color: var(--accent); font-size: var(--text-sm); text-decoration: none; }
.back-link:hover { text-decoration: underline; }
.workspace-heading { display: grid; gap: var(--space-2); }
.eyebrow { margin: 0; color: var(--accent); font-size: 11px; font-weight: 800; letter-spacing: .2em; }
.workspace-heading h1 { margin: 0; color: var(--text-primary); font-family: Georgia, 'Times New Roman', serif; font-size: clamp(2rem, 4vw, 3rem); letter-spacing: -.04em; }
.workspace-heading p:last-child { margin: 0; color: var(--text-muted); }
.comic-id { padding: 6px 9px; border: 1px solid var(--border); color: var(--text-muted); font: 700 11px ui-monospace, SFMono-Regular, Consolas, monospace; }
.workspace-tabs :deep(.el-tabs__header) { margin-bottom: var(--space-6); }
.workspace-tabs :deep(.el-tabs__active-bar) { height: 3px; background: var(--accent); }
.workspace-tabs :deep(.el-tabs__item) { color: var(--text-muted); }
.workspace-tabs :deep(.el-tabs__item.is-active) { color: var(--text-primary); }
@media (max-width: 640px) { .workspace-header { grid-template-columns: 1fr auto; gap: var(--space-3); } .workspace-heading { grid-column: 1 / -1; grid-row: 2; } .workspace-tabs :deep(.el-tabs__item) { padding: 0 var(--space-2); font-size: 12px; } }
</style>
