<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { RouterLink } from 'vue-router'
import { importScanApi, type ImportBatch } from '@/entities/import-scan'
import { taskApi, type Task } from '@/entities/task'
import { getApiErrorMessage } from '@/shared/api/http'
import { taskStatusLabels } from '@/shared/ui/management'
const props = defineProps<{ batchId: string; initialPage: number }>()
const emit = defineEmits<{ inspect: [id: string, reference: string]; page: [page: number]; ready: [] }>()
const batch = ref<ImportBatch | null>(null),
  error = ref(''),
  loading = ref(false),
  page = ref(props.initialPage),
  children = ref<Record<string, Task>>({})
const visible = computed(() => batch.value?.items.slice((page.value - 1) * 20, page.value * 20) ?? [])
const labels: Record<string, string> = {
  pending: '等待分派',
  dispatched: '已分派',
  succeeded: '导入成功',
  failed: '导入失败',
  cancelled: '已取消',
}
let disposed = false
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const value = await importScanApi.batch(props.batchId)
    if (disposed) return
    if (value.id !== props.batchId) throw new Error('导入批次响应与当前结果不一致')
    batch.value = value
    const safePage = Math.max(1, Math.min(page.value, Math.ceil(value.total / 20)))
    if (safePage !== page.value) {
      page.value = safePage
      emit('page', safePage)
    }
    const details = await Promise.all(
      visible.value
        .filter((item) => item.taskId)
        .map(async (item) => {
          const task = await taskApi.detail(item.taskId)
          if (task.id !== item.taskId) throw new Error('导入子任务响应与当前条目不一致')
          return [item.taskId, task] as const
        }),
    )
    if (!disposed) children.value = { ...children.value, ...Object.fromEntries(details) }
  } catch (e) {
    if (!disposed) error.value = getApiErrorMessage(e, '读取逐本结果失败')
  } finally {
    if (!disposed) loading.value = false
    await nextTick()
    if (!disposed && batch.value) emit('ready')
  }
}
async function paginate(target: number) {
  if (loading.value) return
  page.value = target
  emit('page', target)
  await load()
}
const timer = window.setInterval(() => {
  if (batch.value && ['queued', 'running', 'cancelling'].includes(batch.value.status)) void load()
}, 2000)
onMounted(() => void load())
onBeforeUnmount(() => {
  disposed = true
  window.clearInterval(timer)
})
</script>
<template>
  <section class="import-batch-results" aria-label="逐本导入结果">
    <div class="mw-detail-heading">
      <h3 class="mw-task-results-heading">逐本导入结果</h3>
      <button class="mw-button" :disabled="loading" @click="load">刷新批次</button>
    </div>
    <p v-if="error" class="mw-error" role="alert">{{ error }}</p>
    <p v-if="batch" class="mw-muted">
      批次{{ taskStatusLabels[batch.status] }} · 已结束 {{ batch.completed }} /
      {{ batch.total }} 本。每本结果独立，已导入内容不会因取消而回滚。
    </p>
    <p v-if="batch" class="mw-notice" role="status">
      成功 {{ batch.items.filter((i) => i.status === 'succeeded').length }} 本 · 失败
      {{ batch.items.filter((i) => i.status === 'failed').length }} 本 · 取消
      {{ batch.items.filter((i) => i.status === 'cancelled').length }} 本
    </p>
    <p v-if="!batch" class="mw-muted">正在读取批次…</p>
    <ol v-if="batch" class="mw-result-list">
      <li v-for="(item, index) in visible" :key="item.referenceId">
        <span class="mw-mono">{{ (page - 1) * 20 + index + 1 }}</span>
        <div class="import-batch-item">
          <strong>{{ children[item.taskId]?.title || '候选作品' }}</strong
          ><small class="mw-id">{{ item.referenceId }}</small
          ><span>{{ labels[item.status] }}</span>
          <p v-if="item.errorCode" class="mw-error">{{ item.errorCode }}</p>
          <p v-if="children[item.taskId]?.failure" class="mw-error">{{ children[item.taskId]?.failure?.message }}</p>
          <button
            v-if="item.taskId"
            class="mw-button"
            :data-task-relation="`import:${item.referenceId}`"
            @click="emit('inspect', item.taskId, item.referenceId)"
          >
            查看子任务
          </button>
          <RouterLink
            v-if="children[item.taskId]?.status === 'succeeded' && children[item.taskId]?.comicId"
            class="mw-button"
            :aria-label="'打开作品：' + children[item.taskId]?.title"
            :to="{ path: '/manage', query: { comic: children[item.taskId]?.comicId } }"
            >打开作品</RouterLink
          >
        </div>
      </li>
    </ol>
    <footer v-if="batch && batch.total > 20" class="mw-pagination">
      <button class="mw-button" :disabled="page <= 1 || loading" @click="paginate(page - 1)">上一页</button
      ><span>第 {{ page }} 页</span
      ><button class="mw-button" :disabled="page * 20 >= batch.total || loading" @click="paginate(page + 1)">
        下一页
      </button>
    </footer>
  </section>
</template>
<style scoped>
.import-batch-results {
  margin-top: 24px;
}
.import-batch-item {
  display: grid;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.import-batch-item .mw-button {
  justify-self: start;
}
.import-batch-item strong {
  overflow-wrap: anywhere;
}
</style>
