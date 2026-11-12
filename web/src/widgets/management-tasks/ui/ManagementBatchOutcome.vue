<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import type { Task } from '@/entities/task'
import { metadataApi } from '@/entities/metadata-edit'
import { managementCommands } from '@/features/manage-items'
import { getApiErrorMessage } from '@/shared/api/http'
const props = defineProps<{ task: Task }>()
type Counts = {
  selectedCount: number
  succeededCount: number
  failedCount: number
  blockedCount: number
  cancelledCount: number
  pendingCount: number
  activeCount: number
}
const counts = ref<Counts | null>(null),
  error = ref(''),
  loading = ref(false)
let generation = 0,
  disposed = false
async function load() {
  const current = ++generation,
    task = props.task
  loading.value = true
  error.value = ''
  try {
    if (!task.targetId) throw new Error('任务缺少冻结批次标识')
    const value =
      task.type === 'metadata_batch'
        ? await metadataApi.preview(task.targetId)
        : await managementCommands.previewDetail(task.targetId)
    if (disposed || current !== generation) return
    if (
      value.id !== task.targetId ||
      value.taskId !== task.id ||
      (task.type === 'command_batch' && value.operation !== task.targetType) ||
      [
        'selectedCount',
        'succeededCount',
        'failedCount',
        'blockedCount',
        'cancelledCount',
        'pendingCount',
        'activeCount',
      ].some((key) => !Number.isSafeInteger(value[key as keyof Counts]) || Number(value[key as keyof Counts]) < 0)
    )
      throw new Error('批次结果与当前任务或计数契约不一致')
    counts.value = value
  } catch (e) {
    if (!disposed && current === generation) error.value = getApiErrorMessage(e, '暂未取得批次结果')
  } finally {
    if (!disposed && current === generation) loading.value = false
  }
}
watch(
  () => props.task,
  (task, previous) => {
    if (task.id !== previous?.id) counts.value = null
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  disposed = true
  ++generation
})
</script>
<template>
  <section class="batch-outcome" aria-label="批次内容处理结果">
    <div class="batch-outcome-title">
      <strong>内容处理结果</strong><button class="mw-button" :disabled="loading" @click="load">刷新结果统计</button>
    </div>
    <p v-if="error" class="mw-muted" role="status">{{ error }}。暂不能判断内容是否全部处理成功。</p>
    <p v-else-if="!counts" class="mw-muted">正在读取冻结批次的逐项统计…</p>
    <template v-if="counts">
      <p v-if="counts.failedCount" class="batch-outcome-warning">
        存在 {{ counts.failedCount }} 项失败，请核对下方结果。
      </p>
      <p v-else-if="counts.blockedCount" class="batch-outcome-warning">
        有 {{ counts.blockedCount }} 项被阻止，未执行。
      </p>
      <dl>
        <div>
          <dt>成功</dt>
          <dd>{{ counts.succeededCount }}</dd>
        </div>
        <div>
          <dt>失败</dt>
          <dd>{{ counts.failedCount }}</dd>
        </div>
        <div>
          <dt>被阻止</dt>
          <dd>{{ counts.blockedCount }}</dd>
        </div>
        <div>
          <dt>已取消</dt>
          <dd>{{ counts.cancelledCount }}</dd>
        </div>
      </dl>
      <small class="mw-muted"
        >冻结 {{ counts.selectedCount }} 项 · 待处理 {{ counts.pendingCount }} 项 · 执行中
        {{ counts.activeCount }} 项</small
      >
    </template>
  </section>
</template>
<style scoped>
.batch-outcome {
  margin: 16px 0;
  padding: 14px;
  border: 1px solid var(--atlas-line);
  border-radius: 10px;
  background: var(--atlas-bg);
}
.batch-outcome-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.batch-outcome dl {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 12px 0;
}
.batch-outcome dt {
  color: var(--atlas-muted);
  font-size: 12px;
}
.batch-outcome dd {
  margin: 4px 0;
  font-size: 20px;
}
.batch-outcome-warning {
  color: var(--atlas-warning);
}
@media (max-width: 1000px) {
  .batch-outcome dl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
