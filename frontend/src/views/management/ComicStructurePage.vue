<template>
  <div class="structure-page">
    <header class="page-header"><div><h1>目录与媒体结构</h1><p>先输入漫画 ID，加载目录、章节与媒体 ID，再执行维护操作。</p></div><div><el-input-number v-model="comicId" :min="1" :controls="false" /><el-button type="primary" @click="loadTree">加载</el-button></div></header>
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <el-table v-loading="loading" :data="structureRows" row-key="key">
      <el-table-column label="类型" width="90"><template #default="{ row }">{{ row.kind === 'CATALOG' ? '目录' : '章节' }}</template></el-table-column>
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column label="标题" min-width="280"><template #default="{ row }"><span :style="{ paddingLeft: `${row.depth * 20}px` }">{{ row.title }}</span></template></el-table-column>
      <el-table-column prop="order" label="顺序" width="90" />
      <el-table-column label="状态" width="150"><template #default="{ row }">{{ row.status ? `${chapterStatusLabel(row.status)} (${row.status})` : '—' }}</template></el-table-column>
    </el-table>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="目录维护" name="catalog">
        <section class="form-panel">
          <el-select v-model="catalogForm.action"><el-option v-for="item in CATALOG_ACTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select>
          <el-input-number v-if="catalogForm.action !== 'create'" v-model="catalogForm.id" :min="1" :controls="false" placeholder="目录 ID" />
          <el-input v-if="['create', 'rename'].includes(catalogForm.action)" v-model="catalogForm.title" placeholder="目录标题" />
          <el-input-number v-if="['create', 'move'].includes(catalogForm.action)" v-model="catalogForm.parentId" :min="1" :controls="false" placeholder="父目录 ID，留空为根级" clearable />
          <el-input-number v-if="catalogForm.action === 'reorder'" v-model="catalogForm.order" :min="1" :controls="false" placeholder="目标顺序" />
          <el-input-number v-if="catalogForm.action === 'delete'" v-model="catalogForm.reparentTo" :min="1" :controls="false" placeholder="非空目录重挂目标 ID" clearable />
          <el-button type="primary" @click="submitCatalog">执行目录操作</el-button>
        </section>
      </el-tab-pane>
      <el-tab-pane label="章节维护" name="chapter">
        <section class="form-panel">
          <el-select v-model="chapterForm.action"><el-option v-for="item in CHAPTER_ACTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select>
          <el-input-number v-if="chapterForm.action !== 'create'" v-model="chapterForm.id" :min="1" :controls="false" placeholder="章节 ID" />
          <el-input v-if="['create', 'rename'].includes(chapterForm.action)" v-model="chapterForm.title" placeholder="章节标题" />
          <el-input v-if="['create', 'rename'].includes(chapterForm.action)" v-model="chapterForm.chapterNo" placeholder="原始章节编号" />
          <el-input-number v-if="['create', 'move'].includes(chapterForm.action)" v-model="chapterForm.catalogId" :min="1" :controls="false" placeholder="目录 ID，留空为根级" clearable />
          <el-input-number v-if="chapterForm.action === 'reorder'" v-model="chapterForm.order" :min="1" :controls="false" placeholder="全书目标顺序" />
          <el-button :type="chapterForm.action === 'trash' ? 'danger' : 'primary'" @click="submitChapter">执行章节操作</el-button>
        </section>
      </el-tab-pane>
      <el-tab-pane label="媒体维护" name="media">
        <section class="form-panel"><el-input-number v-model="mediaChapterId" :min="1" :controls="false" placeholder="章节 ID" /><el-button @click="loadMedia">加载媒体</el-button><el-input v-model="mediaOrder" placeholder="重排后的完整媒体 ID，逗号分隔" /><el-button type="primary" @click="reorderMedia">提交重排</el-button></section>
        <el-table :data="mediaItems" row-key="id"><el-table-column prop="id" label="媒体 ID" /><el-table-column prop="pageNumber" label="页码" /><el-table-column prop="mediaType" label="类型" /><el-table-column prop="lqStatus" label="LQ 状态" /><el-table-column label="操作"><template #default="{ row }"><el-button link type="danger" @click="trashMedia(row.id)">回收</el-button></template></el-table-column></el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { catalogApi, catalogManagementApi, chapterManagementApi, mediaManagementApi, readerApi } from '@/services/api'
import type { CatalogNode, MediaItemInfo } from '@/types'

type CatalogAction = 'create' | 'rename' | 'move' | 'reorder' | 'delete'
type ChapterAction = 'create' | 'rename' | 'move' | 'reorder' | 'trash'
type StructureRow = { readonly key: string; readonly kind: 'CATALOG' | 'CHAPTER'; readonly id: number; readonly title: string; readonly order: number | null; readonly status: string | null; readonly depth: number }
const CATALOG_ACTIONS = [{ value: 'create', label: '新建目录' }, { value: 'rename', label: '重命名目录' }, { value: 'move', label: '移动目录' }, { value: 'reorder', label: '目录重排' }, { value: 'delete', label: '删除目录' }] as const
const CHAPTER_ACTIONS = [{ value: 'create', label: '新建章节' }, { value: 'rename', label: '重命名章节' }, { value: 'move', label: '移动章节' }, { value: 'reorder', label: '章节重排' }, { value: 'trash', label: '回收章节' }] as const
const comicId = ref(1)
const tree = ref<readonly CatalogNode[]>([])
const mediaItems = ref<readonly MediaItemInfo[]>([])
const mediaChapterId = ref(1)
const mediaOrder = ref('')
const loading = ref(false)
const error = ref('')
const activeTab = ref('catalog')
const catalogForm = reactive<{ action: CatalogAction; id?: number; title: string; parentId?: number; order?: number; reparentTo?: number }>({ action: 'create', title: '' })
const chapterForm = reactive<{ action: ChapterAction; id?: number; title: string; chapterNo: string; catalogId?: number; order?: number }>({ action: 'create', title: '', chapterNo: '' })
const structureRows = computed(() => { const rows: StructureRow[] = []; const visit = (nodes: readonly CatalogNode[], depth: number): void => { for (const node of nodes) { if (node.id !== null) rows.push({ key: `catalog-${node.id}`, kind: 'CATALOG', id: node.id, title: node.title ?? '未命名目录', order: node.globalOrder ?? null, status: null, depth }); for (const chapter of node.chapters) rows.push({ key: `chapter-${chapter.id}`, kind: 'CHAPTER', id: chapter.id, title: chapter.title, order: chapter.globalOrder, status: chapter.status ?? null, depth: depth + 1 }); visit(node.children, depth + 1) } }; visit(tree.value, 0); return rows })
function errorMessage(reason: unknown): string { if (axios.isAxiosError<{ message?: string }>(reason)) return reason.response?.data?.message ?? reason.message; return reason instanceof Error ? reason.message : '未知错误' }
function assertNever(value: never): never { throw new TypeError(`未知操作: ${String(value)}`) }
function chapterStatusLabel(status: string): string { const labels: Readonly<Record<string, string>> = { DRAFT: '草稿', READY: '可阅读', DELETING: '删除排队中', TRASHING: '回收中', TRASHED: '已回收', RESTORING: '恢复中', PURGING: '永久清理中', DELETED: '已永久删除' }; return labels[status] ?? '未知状态' }
async function loadTree(): Promise<void> { loading.value = true; error.value = ''; try { tree.value = (await catalogApi.tree(comicId.value)).data } catch (reason: unknown) { error.value = errorMessage(reason) } finally { loading.value = false } }
async function submitCatalog(): Promise<void> { try { const id = catalogForm.id ?? 0; switch (catalogForm.action) { case 'create': await catalogManagementApi.create(comicId.value, { title: catalogForm.title.trim(), parentId: catalogForm.parentId ?? null }); break; case 'rename': await catalogManagementApi.rename(comicId.value, id, { title: catalogForm.title.trim() }); break; case 'move': await catalogManagementApi.move(comicId.value, id, { parentId: catalogForm.parentId ?? null }); break; case 'reorder': await catalogManagementApi.reorder(comicId.value, id, { sortOrder: catalogForm.order }); break; case 'delete': await ElMessageBox.confirm('删除目录前请确认重挂目标。', '确认删除', { type: 'warning' }); await catalogManagementApi.delete(comicId.value, id, catalogForm.reparentTo); break; default: assertNever(catalogForm.action) } ElMessage.success('目录操作完成'); await loadTree() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function submitChapter(): Promise<void> { try { const id = chapterForm.id ?? 0; switch (chapterForm.action) { case 'create': await chapterManagementApi.create(comicId.value, { title: chapterForm.title.trim(), chapterNo: chapterForm.chapterNo.trim(), catalogId: chapterForm.catalogId ?? null }); break; case 'rename': await chapterManagementApi.rename(comicId.value, id, { title: chapterForm.title.trim() || undefined, chapterNo: chapterForm.chapterNo.trim() || undefined }); break; case 'move': await chapterManagementApi.move(comicId.value, id, { catalogId: chapterForm.catalogId ?? null }); break; case 'reorder': await chapterManagementApi.reorder(comicId.value, id, { targetGlobalOrder: chapterForm.order }); break; case 'trash': await ElMessageBox.confirm('章节将进入回收站。', '确认回收', { type: 'warning' }); await chapterManagementApi.trash(comicId.value, id); break; default: assertNever(chapterForm.action) } ElMessage.success('章节操作完成'); await loadTree() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function loadMedia(): Promise<void> { try { mediaItems.value = (await readerApi.chapter(mediaChapterId.value)).data.pages; mediaOrder.value = mediaItems.value.map((item) => item.id).join(',') } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function reorderMedia(): Promise<void> { const mediaIds = mediaOrder.value.split(',').map((value) => Number(value.trim())).filter((id) => Number.isSafeInteger(id) && id > 0); try { await mediaManagementApi.reorder(mediaChapterId.value, { mediaIds }); ElMessage.success('媒体重排完成'); await loadMedia() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function trashMedia(mediaId: number): Promise<void> { try { await ElMessageBox.confirm(`媒体 ${mediaId} 将进入回收站。`, '确认回收', { type: 'warning' }); await mediaManagementApi.trash(mediaId); ElMessage.success('媒体回收任务已提交'); await loadMedia() } catch (reason: unknown) { if (reason !== 'cancel') ElMessage.error(errorMessage(reason)) } }
</script>

<style scoped>
.structure-page { display: grid; gap: var(--space-6); }
.page-header, .page-header > div, .form-panel { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; }
.page-header h1 { margin: 0; color: var(--text-primary); font-size: var(--text-page); }
.page-header p { color: var(--text-muted); }
.form-panel { justify-content: flex-start; padding: var(--space-5); border: 1px solid var(--border); background: var(--bg-surface); }
.form-panel :deep(.el-input), .form-panel :deep(.el-select), .form-panel :deep(.el-input-number) { width: 220px; }
</style>
