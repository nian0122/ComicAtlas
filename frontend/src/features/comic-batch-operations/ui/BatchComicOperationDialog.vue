<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="min(540px, calc(100vw - 32px))"
    :close-on-click-modal="false"
  >
    <div v-if="loading" class="batch-preview-state" role="status">正在检查所选漫画…</div>
    <div v-else-if="error" class="batch-preview-error" role="alert">
      <span>{{ error }}</span>
      <AppButton size="sm" @click="loadPreview">重试</AppButton>
    </div>
    <template v-else-if="preview">
      <div class="batch-counts" aria-label="批量操作预览">
        <div>
          <strong>{{ preview.selectedCount }}</strong
          ><span>所选漫画</span>
        </div>
        <div>
          <strong>{{ preview.eligibleCount }}</strong
          ><span>可以执行</span>
        </div>
        <div>
          <strong>{{ preview.blocked.length }}</strong
          ><span>将跳过</span>
        </div>
      </div>

      <p v-if="operation === 'COMIC_DELETE'" class="batch-warning">符合条件的漫画将移入回收站，可在保留期内恢复。</p>

      <div v-if="preview.blocked.length > 0" class="blocked-list">
        <h3>无法执行的漫画</h3>
        <ul>
          <li v-for="item in preview.blocked.slice(0, 8)" :key="item.comicId">
            <span>漫画 #{{ item.comicId }}</span>
            <span>{{ item.reason }}</span>
          </li>
        </ul>
        <p v-if="preview.blocked.length > 8">另有 {{ preview.blocked.length - 8 }} 部漫画将跳过。</p>
      </div>

      <el-checkbox v-if="operation === 'COMIC_DELETE'" v-model="confirmed" class="batch-confirm">
        我确认将可执行的漫画移入回收站
      </el-checkbox>
    </template>

    <template #footer>
      <AppButton variant="secondary" :disabled="submitting" @click="visible = false">取消</AppButton>
      <AppButton variant="primary" :disabled="!canSubmit" :loading="submitting" @click="submitOperation">
        确认执行
      </AppButton>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getApiErrorMessage } from '@/shared/api/http'
import { AppButton } from '@/shared/ui/button'
import { batchApi } from '@/entities/task'
import type { BatchPreviewResult, ManagementTaskType } from '@/entities/task'

type ComicBatchOperation = Extract<ManagementTaskType, 'LQ_GENERATE' | 'METADATA_REFRESH' | 'COMIC_DELETE'>

const props = defineProps<{
  comicIds: readonly number[]
  operation: ComicBatchOperation
}>()

const emit = defineEmits<{
  completed: [taskId: number]
}>()

const visible = defineModel<boolean>('visible', { default: false })
const preview = ref<BatchPreviewResult | null>(null)
const loading = ref(false)
const submitting = ref(false)
const confirmed = ref(false)
const error = ref('')
let requestSequence = 0

const operationLabels: Record<ComicBatchOperation, string> = {
  LQ_GENERATE: '批量生成低清图',
  METADATA_REFRESH: '批量刷新元数据',
  COMIC_DELETE: '批量回收到回收站',
}
const dialogTitle = computed(() => operationLabels[props.operation])
const canSubmit = computed(
  () =>
    preview.value !== null &&
    preview.value.eligibleCount > 0 &&
    !loading.value &&
    !submitting.value &&
    (props.operation !== 'COMIC_DELETE' || confirmed.value),
)

watch(visible, (isVisible) => {
  if (isVisible) {
    confirmed.value = false
    void loadPreview()
  } else {
    requestSequence += 1
    preview.value = null
    error.value = ''
  }
})

async function loadPreview(): Promise<void> {
  const requestId = ++requestSequence
  loading.value = true
  error.value = ''
  preview.value = null
  try {
    const response = await batchApi.preview({
      operation: props.operation,
      selection: { type: 'IDS', ids: [...props.comicIds] },
    })
    if (requestId === requestSequence) preview.value = response.data
  } catch (reason: unknown) {
    if (requestId === requestSequence) error.value = getApiErrorMessage(reason, '批量操作预览失败')
  } finally {
    if (requestId === requestSequence) loading.value = false
  }
}

async function submitOperation(): Promise<void> {
  if (!canSubmit.value || !preview.value) return

  submitting.value = true
  try {
    const response = await batchApi.submit(
      {
        operation: props.operation,
        selection: { type: 'IDS', ids: [...props.comicIds] },
        previewToken: preview.value.previewToken,
      },
      crypto.randomUUID(),
    )
    ElMessage.success(`批量任务 #${response.data.task.id} 已创建`)
    emit('completed', response.data.task.id)
    visible.value = false
  } catch (reason: unknown) {
    ElMessage.error(getApiErrorMessage(reason, '批量操作提交失败'))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.batch-preview-state {
  padding: 30px 0;
  color: var(--text-secondary);
  text-align: center;
}

.batch-preview-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  color: var(--danger);
}

.batch-counts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-sm);
}

.batch-counts > div {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
}

.batch-counts strong {
  color: var(--text-primary);
  font-size: 22px;
  font-variant-numeric: tabular-nums;
}

.batch-counts span,
.blocked-list p {
  color: var(--text-muted);
  font-size: 12px;
}

.batch-warning {
  padding: 11px 13px;
  border-left: 2px solid var(--warning);
  background: var(--warning-bg, var(--bg-surface));
  color: var(--text-secondary);
  font-size: 13px;
}

.blocked-list {
  max-height: 220px;
  overflow: auto;
  margin-top: var(--space-lg);
}

.blocked-list h3 {
  margin: 0 0 var(--space-sm);
  color: var(--text-primary);
  font-size: 13px;
}

.blocked-list ul {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.blocked-list li {
  display: flex;
  justify-content: space-between;
  gap: var(--space-md);
  color: var(--text-secondary);
  font-size: 12px;
}

.blocked-list li span:last-child {
  text-align: right;
}

.batch-confirm {
  margin-top: var(--space-lg);
}
</style>
