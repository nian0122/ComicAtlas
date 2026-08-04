<template>
  <div class="danger-tab" data-testid="danger-tab">
    <p v-if="error" class="state error" data-testid="danger-error" :data-state="errorState" :data-reason-code="errorReasonCode">
      {{ error }}
    </p>

    <section class="danger-section">
      <div class="section-head">
        <h3 class="section-title">漫画回收</h3>
        <span class="section-hint">移入回收站，保留 7 天后可永久删除</span>
      </div>
      <div v-if="ready.length === 0" class="state empty">没有可回收的漫画</div>
      <div v-else class="row-list">
        <div v-for="c in ready" :key="c.id" class="danger-row" :data-testid="`danger-row-${c.id}`">
          <span class="row-title">{{ c.title }}</span>
          <span class="row-status">READY</span>
          <button
            class="op-btn is-warning"
            :data-testid="`danger-recycle-${c.id}`"
            :disabled="busyIds.includes(c.id)"
            @click="openRecycle(c.id, c.title)"
          >
            {{ busyIds.includes(c.id) ? '回收中' : '移入回收站' }}
          </button>
        </div>
      </div>
    </section>

    <section class="danger-section">
      <div class="section-head">
        <h3 class="section-title">永久删除</h3>
        <span class="section-hint">需要服务端 preview token 二次确认 + 标题确认</span>
      </div>
      <div v-if="trashed.length === 0" class="state empty">回收站为空</div>
      <div v-else class="row-list">
        <div v-for="c in trashed" :key="c.id" class="danger-row" :data-testid="`danger-row-${c.id}`">
          <span class="row-title">{{ c.title }}</span>
          <span class="row-status is-trashed">TRASHED</span>
          <button
            class="op-btn is-danger"
            :data-testid="`danger-purge-${c.id}`"
            :disabled="busyIds.includes(c.id)"
            @click="openPurge(c.id, c.title)"
          >
            {{ busyIds.includes(c.id) ? '删除中' : '永久删除' }}
          </button>
        </div>
      </div>
    </section>

    <DangerConfirmDialog
      v-model="dialog.visible"
      :title="dialog.title"
      :action-label="dialog.actionLabel"
      :busy="dialog.busy"
      data-testid="danger-dialog"
      @confirm="onConfirm"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from '@/services/management/http'
import { batchApi } from '@/services/management/batch'
import { toErrorMessage } from '@/services/management/http'
import { TaskType } from '@/types/management/enums'
import { ComicLifecycleStatus } from '@/types/management/enums'
import type { BatchOperationRequest } from '@/types/management/batch'
import type { ComicListVO } from '@/types'
import DangerConfirmDialog from './DangerConfirmDialog.vue'

interface DialogState {
  visible: boolean
  comicId: number
  title: string
  actionLabel: string
  mode: 'recycle' | 'purge'
  idempotencyKey: string
  previewToken: string
  busy: boolean
}

const ready = ref<readonly ComicListVO[]>([])
const trashed = ref<readonly ComicListVO[]>([])
const busyIds = ref<readonly number[]>([])
const error = ref('')
const errorReasonCode = ref('')
const errorState = ref('')

const dialog = ref<DialogState>({
  visible: false,
  comicId: 0,
  title: '',
  actionLabel: '',
  mode: 'recycle',
  idempotencyKey: '',
  previewToken: '',
  busy: false,
})

async function loadLists(): Promise<void> {
  error.value = ''
  try {
    const [readyRes, trashedRes] = await Promise.all([
      request<{ records: readonly ComicListVO[] }>({
        method: 'GET',
        url: '/comics',
        params: { status: ComicLifecycleStatus.READY, page: 1, size: 200 },
      }),
      request<{ records: readonly ComicListVO[] }>({
        method: 'GET',
        url: '/comics',
        params: { status: ComicLifecycleStatus.TRASHED, page: 1, size: 200 },
      }),
    ])
    ready.value = readyRes.records ?? []
    trashed.value = trashedRes.records ?? []
  } catch (err: unknown) {
    error.value = toErrorMessage(err, '加载漫画列表失败')
  }
}

function setBusy(id: number, busy: boolean): void {
  if (busy) {
    busyIds.value = busyIds.value.includes(id) ? busyIds.value : [...busyIds.value, id]
  } else {
    busyIds.value = busyIds.value.filter((v) => v !== id)
  }
}

function showError(message: string, reasonCode = ''): void {
  error.value = message
  errorReasonCode.value = reasonCode
  errorState.value = 'red'
}

function clearError(): void {
  error.value = ''
  errorReasonCode.value = ''
  errorState.value = ''
}

function openRecycle(comicId: number, title: string): void {
  clearError()
  dialog.value = {
    visible: true,
    comicId,
    title,
    actionLabel: '移入回收站',
    mode: 'recycle',
    idempotencyKey: crypto.randomUUID(),
    previewToken: '',
    busy: false,
  }
}

async function openPurge(comicId: number, title: string): Promise<void> {
  clearError()
  setBusy(comicId, true)
  try {
    const payload: BatchOperationRequest = {
      operation: TaskType.COMIC_PURGE,
      selection: { type: 'IDS', ids: [comicId] },
    }
    const preview = await batchApi.preview(payload)
    if (!preview.previewToken) {
      showError('该漫画暂不可永久删除：预览未签发确认令牌')
      return
    }
    dialog.value = {
      visible: true,
      comicId,
      title,
      actionLabel: '永久删除',
      mode: 'purge',
      idempotencyKey: crypto.randomUUID(),
      previewToken: preview.previewToken,
      busy: false,
    }
  } catch (err: unknown) {
    const apiErr = err as { reasonCode?: string | null; message?: string }
    showError(toErrorMessage(err, '预览失败'), apiErr.reasonCode ?? '')
  } finally {
    setBusy(comicId, false)
  }
}

async function onConfirm(): Promise<void> {
  dialog.value.busy = true
  try {
    if (dialog.value.mode === 'recycle') {
      await request({
        method: 'DELETE',
        url: `/comics/${dialog.value.comicId}`,
        headers: { 'Idempotency-Key': dialog.value.idempotencyKey },
      })
      ready.value = ready.value.filter((c) => c.id !== dialog.value.comicId)
      ElMessage.success('漫画已移入回收站')
    } else {
      const payload: BatchOperationRequest = {
        operation: TaskType.COMIC_PURGE,
        selection: { type: 'IDS', ids: [dialog.value.comicId] },
        previewToken: dialog.value.previewToken,
      }
      await batchApi.create(payload, dialog.value.idempotencyKey)
      trashed.value = trashed.value.filter((c) => c.id !== dialog.value.comicId)
      ElMessage.success('漫画已永久删除')
    }
    dialog.value.visible = false
  } catch (err: unknown) {
    dialog.value.visible = false
    const apiErr = err as { reasonCode?: string | null }
    showError(toErrorMessage(err, '操作失败'), apiErr.reasonCode ?? '')
  } finally {
    dialog.value.busy = false
  }
}

onMounted(loadLists)
</script>

<style scoped>
.danger-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  min-width: 0;
}

.state.error {
  padding: var(--space-3);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: rgb(240 107 112 / 10%);
  color: var(--danger);
  font-size: var(--text-sm);
}

.state.empty {
  padding: var(--space-6) 0;
  color: var(--text-muted);
  text-align: center;
  font-size: var(--text-sm);
}

.danger-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.section-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.section-title {
  margin: 0;
  font-size: var(--text-md);
  font-weight: 700;
  color: var(--text-primary);
}

.section-hint {
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.row-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.danger-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
}

.row-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-weight: 600;
}

.row-status {
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  background: rgb(102 197 139 / 14%);
  color: var(--success);
  font-size: 11px;
  font-weight: 600;
}

.row-status.is-trashed {
  background: rgb(112 166 216 / 14%);
  color: var(--info);
}

.op-btn {
  min-height: 36px;
  padding-inline: var(--space-4);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  font-family: var(--font-ui);
  cursor: pointer;
  white-space: nowrap;
  transition:
    background-color var(--transition-fast),
    color var(--transition-fast),
    border-color var(--transition-fast);
}

.op-btn:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}

.op-btn.is-warning:hover:not(:disabled) {
  border-color: var(--warning);
  color: var(--warning);
}

.op-btn.is-danger:hover:not(:disabled) {
  border-color: var(--danger);
  color: var(--danger);
  background: rgb(240 107 112 / 10%);
}

.op-btn:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}
</style>
