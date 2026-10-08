<template>
  <div class="comic-workspace-page">
    <div class="workspace-header">
      <router-link to="/manage/comics" class="back-link">← 漫画列表</router-link>
      <PageHeader title="单本漫画工作区" description="集中处理这一本漫画的信息、目录、媒体和存储。">
        <span class="comic-id">ID {{ comicId }}</span>
      </PageHeader>
    </div>

    <el-tabs v-model="activeTab" class="workspace-tabs" @tab-change="handleTabChange">
      <el-tab-pane name="operations" label="概览与操作" lazy><ComicOperationsPage /></el-tab-pane>
      <el-tab-pane name="edit" label="信息编辑" lazy><ComicEditPage /></el-tab-pane>
      <el-tab-pane name="content" label="目录与存储" lazy><ComicContentWorkspacePage /></el-tab-pane>
      <el-tab-pane name="ai" label="AI 分析" lazy><ComicAiAnalysisPage :comic-id="comicId" /></el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PageHeader } from '@/shared/ui/page-header'
import ComicOperationsPage from './ComicOperationsPage.vue'
import ComicEditPage from './ComicEditPage.vue'
import ComicContentWorkspacePage from './ComicContentWorkspacePage.vue'
import ComicAiAnalysisPage from './ComicAiAnalysisPage.vue'

type WorkspaceTab = 'operations' | 'edit' | 'content' | 'ai'
const route = useRoute()
const router = useRouter()
const comicId = Number(route.params.id)
const tabs: readonly WorkspaceTab[] = ['operations', 'edit', 'content', 'ai']

function normalizeTab(value: unknown): WorkspaceTab {
  if (value === 'structure' || value === 'storage') return 'content'
  return typeof value === 'string' && tabs.includes(value as WorkspaceTab) ? (value as WorkspaceTab) : 'operations'
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

watch(
  () => route.query.tab,
  (value) => {
    activeTab.value = normalizeTab(value)
    resetManagementScroll()
  },
)
</script>

<style scoped>
.comic-workspace-page {
  display: grid;
  gap: var(--space-6);
  min-width: 0;
}
.workspace-header {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
}
.back-link {
  align-self: start;
  color: var(--accent);
  font-size: var(--text-sm);
  text-decoration: none;
}
.back-link:hover {
  text-decoration: underline;
}
.comic-id {
  color: var(--text-secondary);
  font-size: var(--text-sm);
}
.workspace-tabs > :deep(.el-tabs__header) {
  margin-bottom: var(--space-6);
}
.workspace-tabs > :deep(.el-tabs__header > .el-tabs__nav-wrap .el-tabs__active-bar) {
  height: 3px;
}
@media (max-width: 640px) {
  .workspace-tabs > :deep(.el-tabs__header > .el-tabs__nav-wrap .el-tabs__item) {
    padding: 0 var(--space-2);
    font-size: var(--text-xs);
  }
}
</style>
