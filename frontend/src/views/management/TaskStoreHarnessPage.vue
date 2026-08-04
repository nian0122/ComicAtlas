<template>
  <section class="harness" data-testid="harness">
    <header class="harness-header">
      <h2>管理 Store 契约测试台</h2>
      <div class="controls">
        <button data-testid="stop-polling" type="button" @click="taskStore.stopPolling()">停止轮询</button>
        <button data-testid="batch-preview" type="button" @click="runBatchPreview()">批量预览</button>
        <button data-testid="batch-create" type="button" @click="runBatchCreate()">批量创建</button>
      </div>
      <div
        class="polling"
        data-testid="polling-indicator"
        :data-polling="String(taskStore.polling)"
      >
        {{ taskStore.polling ? 'polling' : 'idle' }}
      </div>
    </header>

    <div v-if="taskStore.error" class="state-box error" data-testid="poll-error">
      {{ taskStore.error }}
    </div>

    <ul class="task-list" data-testid="task-list">
      <li
        v-for="task in taskStore.tasks"
        :key="task.id"
        class="task-item"
        :data-testid="`task-item-${task.id}`"
      >
        <span class="cell" data-testid="id">#{{ task.id }}</span>
        <span
          class="cell badge"
          data-testid="status"
          :data-status="task.status.value"
          :data-known="String(task.status.kind === 'known')"
        >
          {{ statusLabel(task.status) }}
        </span>
        <span v-if="task.status.kind === 'unknown'" class="cell raw" data-testid="raw-status">
          {{ task.status.value }}
        </span>
        <span class="cell" data-testid="task-type">{{ task.taskType.value }}</span>
        <span class="cell" data-testid="progress">{{ task.progress }}%</span>
      </li>
    </ul>

    <div
      v-if="batchError"
      class="state-box error"
      data-testid="batch-error"
      :data-status="batchError.status ?? ''"
      :data-reason-code="batchError.reasonCode ?? ''"
    >
      {{ batchError.message }}
    </div>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useManagementTaskStore } from '@/stores/management/managementTask'
import { useBatchSelectionStore } from '@/stores/management/selection'
import { batchApi } from '@/services/management/batch'
import { ApiError } from '@/services/management/http'
import { TaskType } from '@/types/management/enums'
import type { EnumValue, ManagementTaskStatus } from '@/types/management/enums'
import type { BatchOperationRequest } from '@/types/management/batch'

interface BatchErrorState {
  readonly status: number | null
  readonly reasonCode: string | null
  readonly message: string
}

const taskStore = useManagementTaskStore()
const selectionStore = useBatchSelectionStore()
const batchError = ref<BatchErrorState | null>(null)

function statusLabel(status: EnumValue<ManagementTaskStatus>): string {
  if (status.kind === 'known') return status.value
  return `未知状态 (${status.value})`
}

function buildBatchRequest(): BatchOperationRequest {
  return {
    operation: TaskType.LQ_GENERATE,
    selection: selectionStore.selection,
    previewToken: 'expired-token',
  }
}

function captureError(err: unknown): void {
  if (err instanceof ApiError) {
    batchError.value = { status: err.status, reasonCode: err.reasonCode, message: err.message }
  } else {
    batchError.value = {
      status: null,
      reasonCode: null,
      message: err instanceof Error ? err.message : '未知错误',
    }
  }
}

async function runBatchPreview(): Promise<void> {
  batchError.value = null
  try {
    await batchApi.preview(buildBatchRequest())
  } catch (err: unknown) {
    captureError(err)
  }
}

async function runBatchCreate(): Promise<void> {
  batchError.value = null
  try {
    await batchApi.create(buildBatchRequest())
  } catch (err: unknown) {
    captureError(err)
  }
}

onMounted(() => {
  selectionStore.selectIds([1])
  void taskStore.bootstrap()
})

onBeforeUnmount(() => {
  taskStore.stopPolling()
})
</script>

<style scoped>
.harness {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 720px;
  margin: 0 auto;
  padding: var(--space-6);
  background: var(--bg-primary);
  color: var(--text-primary);
  font-family: var(--font-ui);
}

.harness-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.harness-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
}

.controls {
  display: flex;
  gap: var(--space-2);
}

.controls button {
  padding: 6px 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.polling {
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.task-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  font-size: 13px;
}

.cell.badge {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--accent-bg);
  font-weight: 700;
}

.cell.raw {
  color: var(--danger);
}

.state-box {
  padding: var(--space-3);
  border-radius: var(--radius-md);
  font-size: 13px;
}

.state-box.error {
  color: var(--danger);
  background: var(--bg-surface);
  border: 1px solid var(--danger);
}
</style>
