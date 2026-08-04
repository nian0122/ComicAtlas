<template>
  <div class="trash-page fade-in">
    <header class="trash-header">
      <h1 class="page-title">回收站</h1>
      <p class="page-subtitle">回收的漫画保留 7 天，可恢复；永久删除需要服务端确认令牌</p>
    </header>

    <p v-if="store.error" class="state error" data-testid="trash-error">{{ store.error }}</p>

    <div v-if="!store.loading && store.items.length === 0" class="state empty" data-testid="trash-empty">
      回收站为空
    </div>

    <div v-else class="table-scroll">
      <table class="trash-table">
        <thead>
          <tr>
            <th class="col-title">标题</th>
            <th class="col-category">分类</th>
            <th class="col-page">页数</th>
            <th class="col-trashed">删除时间</th>
            <th class="col-retention">保留期</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="item in store.items" :key="item.id">
            <tr class="trash-row" :data-testid="`trash-row-${item.id}`">
              <td class="col-title">
                <span class="row-title" data-testid="trash-title" :data-id="item.id">{{ item.title }}</span>
                <span
                  v-if="conflictMap[item.id]"
                  class="conflict-badge"
                  :data-testid="`trash-conflict-${item.id}`"
                  title="存在恢复冲突：源与回收站同时存在同名文件"
                >
                  恢复冲突
                </span>
              </td>
              <td class="col-category">{{ item.categoryName || '—' }}</td>
              <td class="col-page">{{ item.pageCount }}</td>
              <td class="col-trashed">{{ formatTime(item.trashedAt) }}</td>
              <td class="col-retention" :data-testid="`trash-retention-${item.id}`">
                {{ retentionText(item.trashedAt) }}
              </td>
              <td class="col-actions">
                <button
                  class="link-btn"
                  :data-testid="`trash-reconcile-${item.id}`"
                  :disabled="reconcilingIds.includes(item.id)"
                  @click="onReconcile(item.id)"
                >
                  {{ reconcilingIds.includes(item.id) ? '对账中' : '资产清单' }}
                </button>
                <button
                  class="ghost-btn small"
                  :data-testid="`trash-restore-${item.id}`"
                  :disabled="store.busyIds[item.id] ?? false"
                  @click="onRestore(item)"
                >
                  {{ store.busyIds[item.id] ? '恢复中' : '恢复' }}
                </button>
                <button
                  class="ghost-btn small danger-hover"
                  :data-testid="`trash-purge-${item.id}`"
                  :disabled="store.busyIds[item.id] ?? false"
                  @click="onPurge(item)"
                >
                  {{ store.busyIds[item.id] ? '删除中' : '永久删除' }}
                </button>
              </td>
            </tr>
            <tr v-if="reconcileOpen === item.id && reconcileReport" class="manifest-row">
              <td colspan="6">
                <div class="manifest-panel" data-testid="trash-manifest">
                  <div class="manifest-head">
                    <span class="manifest-title">资产清单对账</span>
                    <span
                      class="manifest-consistent"
                      :class="reconcileReport.consistent ? 'is-ok' : 'is-conflict'"
                    >
                      {{ reconcileReport.consistent ? '一致' : '不一致' }}
                    </span>
                    <span v-if="reconcileReport.manifestTaskId" class="manifest-task">
                      任务 #{{ reconcileReport.manifestTaskId }}
                    </span>
                  </div>
                  <div v-for="(entry, index) in reconcileReport.entries" :key="index" class="manifest-entry" :data-testid="`trash-manifest-entry-${index}`">
                    <span class="entry-state" :class="entryStateClass(entry.state)">{{ entry.state }}</span>
                    <span class="entry-path">{{ entry.rootKey }} / {{ entry.sourceRelativePath }}</span>
                  </div>
                  <p v-if="reconcileReport.entries.length === 0" class="manifest-empty">无清单条目</p>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <div v-if="store.serverTotal > store.items.length" class="trash-footer">
      共 {{ store.serverTotal }} 项，当前显示 {{ store.items.length }} 项
    </div>

    <DangerConfirmDialog
      v-model="dialog.visible"
      :title="dialog.title"
      :action-label="dialog.actionLabel"
      :busy="dialog.busy"
      data-testid="trash-dialog"
      @confirm="onConfirm"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useTrashStore } from '@/stores/management/trash'
import { trashApi } from '@/services/management/trash'
import { batchApi } from '@/services/management/batch'
import { toErrorMessage } from '@/services/management/http'
import { TaskType } from '@/types/management/enums'
import type { BatchOperationRequest } from '@/types/management/batch'
import type { TrashComicItem, TrashReconcileReport } from '@/types/management/trash'
import DangerConfirmDialog from './console/DangerConfirmDialog.vue'

const RETENTION_DAYS = 7

const store = useTrashStore()

const reconcileOpen = ref<number | null>(null)
const reconcilingIds = ref<readonly number[]>([])
const reconcileReport = ref<TrashReconcileReport | null>(null)
const conflictMap = ref<Readonly<Record<number, boolean>>>({})

const dialog = ref({
  visible: false,
  comicId: 0,
  title: '',
  actionLabel: '',
  previewToken: '',
  idempotencyKey: '',
  busy: false,
})

function formatTime(value: string): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString('zh-CN', { hour12: false })
}

function retentionText(trashedAt: string): string {
  const d = new Date(trashedAt)
  if (Number.isNaN(d.getTime())) return '—'
  const elapsedDays = Math.floor((Date.now() - d.getTime()) / 86_400_000)
  const remaining = RETENTION_DAYS - elapsedDays
  return remaining > 0 ? `剩余 ${remaining} 天` : '已过保留期'
}

function entryStateClass(state: string): string {
  switch (state) {
    case 'BOTH':
      return 'is-conflict'
    case 'IN_TRASH':
      return 'is-trash'
    case 'AT_SOURCE':
      return 'is-source'
    default:
      return 'is-missing'
  }
}

async function onReconcile(id: number): Promise<void> {
  if (reconcileOpen.value === id) {
    reconcileOpen.value = null
    reconcileReport.value = null
    return
  }
  reconcileOpen.value = id
  reconcilingIds.value = reconcilingIds.value.includes(id)
    ? reconcilingIds.value
    : [...reconcilingIds.value, id]
  try {
    const report = await trashApi.reconcile({ targetType: 'COMIC', targetId: id })
    reconcileReport.value = report
    const hasConflict = !report.consistent || report.entries.some((e) => e.state === 'BOTH')
    conflictMap.value = { ...conflictMap.value, [id]: hasConflict }
  } catch (err: unknown) {
    reconcileReport.value = null
    ElMessage.error(toErrorMessage(err, '对账失败'))
  } finally {
    reconcilingIds.value = reconcilingIds.value.filter((v) => v !== id)
  }
}

async function onRestore(item: TrashComicItem): Promise<void> {
  try {
    await store.restore({ targetType: 'COMIC', targetId: item.id })
    ElMessage.success(`「${item.title}」已恢复`)
  } catch (err: unknown) {
    ElMessage.error(toErrorMessage(err, '恢复失败'))
  }
}

async function onPurge(item: TrashComicItem): Promise<void> {
  try {
    const payload: BatchOperationRequest = {
      operation: TaskType.COMIC_PURGE,
      selection: { type: 'IDS', ids: [item.id] },
    }
    const preview = await batchApi.preview(payload)
    if (!preview.previewToken) {
      ElMessage.error('暂不可永久删除：预览未签发确认令牌')
      return
    }
    dialog.value = {
      visible: true,
      comicId: item.id,
      title: item.title,
      actionLabel: '永久删除',
      previewToken: preview.previewToken,
      idempotencyKey: crypto.randomUUID(),
      busy: false,
    }
  } catch (err: unknown) {
    ElMessage.error(toErrorMessage(err, '预览失败'))
  }
}

async function onConfirm(): Promise<void> {
  dialog.value.busy = true
  try {
    const payload: BatchOperationRequest = {
      operation: TaskType.COMIC_PURGE,
      selection: { type: 'IDS', ids: [dialog.value.comicId] },
      previewToken: dialog.value.previewToken,
    }
    await batchApi.create(payload, dialog.value.idempotencyKey)
    ElMessage.success('漫画已永久删除')
    dialog.value.visible = false
    await store.fetchTrash({ page: 1, size: 100 })
  } catch (err: unknown) {
    dialog.value.visible = false
    ElMessage.error(toErrorMessage(err, '永久删除失败'))
  } finally {
    dialog.value.busy = false
  }
}

onMounted(() => {
  void store.fetchTrash({ page: 1, size: 100 })
})
</script>

<style scoped>
.trash-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  min-width: 0;
}

.trash-header {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.page-title {
  margin: 0;
  font-size: var(--text-page);
  font-weight: 700;
  font-family: var(--heading);
  color: var(--text-primary);
}

.page-subtitle {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-muted);
}

.state.error {
  padding: var(--space-4);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: rgb(240 107 112 / 10%);
  color: var(--danger);
  font-size: var(--text-sm);
}

.state.empty {
  padding: var(--space-10) 0;
  color: var(--text-muted);
  text-align: center;
  font-size: var(--text-sm);
}

.table-scroll {
  overflow-x: auto;
  min-block-size: 0;
}

.trash-table {
  width: 100%;
  min-width: 760px;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.trash-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border-strong);
  background: var(--bg-secondary);
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-weight: 700;
  letter-spacing: 0.04em;
  text-align: left;
  white-space: nowrap;
}

.trash-table td {
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  vertical-align: middle;
}

.trash-row:hover td {
  background: var(--bg-surface);
}

.row-title {
  display: inline-block;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
  font-weight: 600;
  vertical-align: middle;
}

.conflict-badge {
  display: inline-flex;
  align-items: center;
  margin-left: var(--space-2);
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  background: rgb(240 107 112 / 14%);
  color: var(--danger);
  font-size: 11px;
  font-weight: 600;
  vertical-align: middle;
}

.col-page,
.col-retention {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.col-actions {
  white-space: nowrap;
  text-align: right;
}

.link-btn {
  min-height: var(--control-min-size);
  padding-inline: var(--space-2);
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 600;
  font-family: var(--font-ui);
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.link-btn:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}

.ghost-btn {
  min-height: 32px;
  padding-inline: var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--text-xs);
  font-weight: 600;
  font-family: var(--font-ui);
  cursor: pointer;
  transition:
    background-color var(--transition-fast),
    color var(--transition-fast);
}

.ghost-btn:hover:not(:disabled) {
  background: var(--bg-surface);
  color: var(--text-primary);
}

.ghost-btn:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}

.ghost-btn.danger-hover:hover:not(:disabled) {
  border-color: var(--danger);
  color: var(--danger);
}

.manifest-row td {
  padding: 0;
  border-bottom: none;
}

.manifest-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-3) var(--space-4);
  background: var(--bg-surface);
  border-inline: 1px solid var(--border);
}

.manifest-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-1);
}

.manifest-title {
  font-size: var(--text-xs);
  font-weight: 700;
  color: var(--text-primary);
}

.manifest-consistent {
  padding: 1px var(--space-2);
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 600;
}

.manifest-consistent.is-ok {
  background: rgb(102 197 139 / 14%);
  color: var(--success);
}

.manifest-consistent.is-conflict {
  background: rgb(240 107 112 / 14%);
  color: var(--danger);
}

.manifest-task {
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.manifest-entry {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-xs);
  font-size: var(--text-xs);
}

.entry-state {
  min-width: 72px;
  font-weight: 600;
}

.entry-state.is-conflict {
  color: var(--danger);
}

.entry-state.is-trash {
  color: var(--info);
}

.entry-state.is-source {
  color: var(--success);
}

.entry-state.is-missing {
  color: var(--text-muted);
}

.entry-path {
  color: var(--text-muted);
  font-family: var(--mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.manifest-empty {
  margin: 0;
  padding: var(--space-2);
  color: var(--text-muted);
  font-size: var(--text-xs);
}

.trash-footer {
  font-size: var(--text-xs);
  color: var(--text-muted);
}
</style>
