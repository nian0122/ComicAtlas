<script setup lang="ts">
import { nextTick, reactive, ref, watch } from 'vue'
import type { Task } from '@/entities/task'
import { sidecarStateLabels } from '@/entities/sidecar'
import { ManagementDialog, ManagementImage, formatDate, taskStatusLabels, taskStageLabel } from '@/shared/ui/management'
import { useSidecar } from '../model/use-sidecar'
const props = defineProps<{ comicId: string; taskId: string; writable: boolean }>()
const emit = defineEmits<{ close: []; accepted: [task: Task]; task: [id: string] }>()
const w = reactive(useSidecar(props, (task) => emit('accepted', task)))
const resyncOpen = ref(false),
  resyncPanel = ref<HTMLElement>(),
  resyncTrigger = ref<HTMLButtonElement>()
watch(
  () => w.receipt,
  (value, previous) => {
    if (value) resyncOpen.value = true
    else if (previous) resyncOpen.value = false
  },
)
async function openResync() {
  resyncOpen.value = true
  await nextTick()
  resyncPanel.value?.querySelector<HTMLInputElement>('input[type="checkbox"]')?.focus()
}
async function closeResync() {
  resyncOpen.value = false
  w.acknowledged = false
  await nextTick()
  resyncTrigger.value?.focus()
}
</script>
<template>
  <ManagementDialog
    :open="true"
    title="侧车同步"
    :busy="w.busy"
    placement="right"
    class="sidecar-dialog"
    @close="emit('close')"
  >
    <div v-if="w.comic" class="sidecar-source">
      <ManagementImage :src="w.comic.coverUrl" :alt="w.comic.title" />
      <div>
        <span class="mw-muted">整部漫画 · 元数据文件</span>
        <h3>{{ w.comic.title }}</h3>
      </div>
    </div>
    <p v-if="w.comicError" class="mw-muted">{{ w.comicError }}</p>
    <p>资料、章节及媒体登记变化后会自动同步。这里可核对同步进度，或主动重新发布当前资料。</p>
    <p class="mw-muted">
      侧车用于后续媒体恢复，不包含完整阅读记录、标记和设置，不等同于数据库备份；此处不检查原件或副本的文件健康。
    </p>
    <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
    <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
    <section class="sidecar-state">
      <div class="sidecar-heading">
        <h3>当前同步状态</h3>
        <button class="mw-button" :disabled="w.loading || w.busy" @click="w.refresh">刷新同步状态</button>
      </div>
      <p v-if="w.statusError" class="mw-error" role="alert">
        {{ w.statusError }}。{{ w.status ? '保留上次读取结果，本次状态尚未核实。' : '尚未取得当前状态。' }}
      </p>
      <p v-if="w.loading" class="mw-muted">正在读取状态与任务…</p>
      <template v-if="w.status"
        ><strong class="sidecar-badge">{{ sidecarStateLabels[w.status.state] }}</strong>
        <dl class="mw-properties">
          <dt>确认发布时间</dt>
          <dd>{{ formatDate(w.status.publishedAt) }}</dd>
          <template v-if="w.status.retryAt"
            ><dt>后端预计重试</dt>
            <dd>{{ formatDate(w.status.retryAt) }}</dd></template
          >
        </dl>
        <details>
          <summary>同步修订详情</summary>
          <dl class="mw-properties">
            <dt>当前同步修订</dt>
            <dd>{{ w.status.revision }}</dd>
            <dt>已确认发布修订</dt>
            <dd>{{ w.status.publishedRevision || '尚未发布' }}</dd>
            <dt v-if="w.comic">漫画资料版本</dt>
            <dd v-if="w.comic">{{ w.comic.version }}</dd>
          </dl>
        </details>
        <p v-if="w.status.failure" class="mw-error">{{ w.status.failure.message }} {{ w.status.failure.action }}</p>
        <p v-if="w.status.state === 'blocked'" class="mw-muted">
          可能有同漫画的互斥任务，或仍是待补全的恢复占位；编辑标题不会解除占位保护。
        </p>
        <p v-if="w.status.state === 'synced'" class="mw-muted">
          表示该修订曾被确认发布，不表示刚刚检查过磁盘文件是否仍在。
        </p>
        <button
          v-if="w.status.taskId && w.status.taskId !== w.task?.id"
          class="mw-button"
          :disabled="w.busy"
          @click="emit('task', w.status.taskId)"
        >
          查看当前关联任务
        </button>
      </template>
    </section>
    <section v-if="w.task || w.taskError" class="sidecar-result">
      <div class="sidecar-heading">
        <h3>所选任务结果</h3>
        <span v-if="w.task" class="mw-muted">{{ taskStatusLabels[w.task.status] }} · 第 {{ w.task.attempt }} 次</span>
      </div>
      <p v-if="w.taskError" class="mw-error" role="alert">{{ w.taskError }}。未读取到的结果不视为发布成功。</p>
      <p v-if="w.historical" class="mw-notice" role="status">
        这是历史任务，未关联到当前同步状态；其发布结果不代表最新修订。
      </p>
      <template v-if="w.task"
        ><p class="mw-muted">{{ taskStageLabel(w.task.stage) }} · 同步任务不可取消，关闭面板不会终止执行。</p>
        <p v-if="w.task.failure" class="mw-error">{{ w.task.failure.message }} {{ w.task.failure.action }}</p>
        <details>
          <summary>任务标识</summary>
          <code class="sidecar-id">{{ w.task.id }}</code>
        </details></template
      >
      <dl v-if="w.result" class="mw-properties">
        <dt>受理同步修订</dt>
        <dd>{{ w.result.requestedRevision }}</dd>
        <dt>实际快照修订</dt>
        <dd>{{ w.result.observedRevision ?? '尚未读取快照' }}</dd>
        <dt>快照时间</dt>
        <dd>{{ formatDate(w.result.observedAt) }}</dd>
        <dt>发布确认</dt>
        <dd>{{ w.result.published ? '已确认发布' : '尚未确认发布' }}</dd>
      </dl>
      <div v-if="w.task" class="mw-inline-actions">
        <button class="mw-button" :disabled="w.busy" @click="emit('task', w.task.id)">查看完整任务记录</button
        ><button v-if="w.task.canRetry" class="mw-button" :disabled="w.busy || w.loading || !writable" @click="w.retry">
          保护重试原任务
        </button>
      </div>
    </section>
    <section ref="resyncPanel" class="sidecar-confirm">
      <button
        v-if="!resyncOpen"
        ref="resyncTrigger"
        class="mw-button"
        :disabled="w.busy || w.loading || !writable"
        @click="openResync"
      >
        主动重新同步
      </button>
      <template v-else>
        <div class="sidecar-heading">
          <h3>主动重新同步</h3>
          <button v-if="!w.receipt" class="mw-button" :disabled="w.busy" @click="closeResync">返回状态查询</button>
        </div>
        <p class="mw-muted">用于主动重建或重新发布侧车；受理会增加同步修订，按当前登记资料执行，不修改原件内容。</p>
        <p v-if="w.receipt" class="mw-notice">尚未获得上次受理凭据，重试保留原请求，可取回已受理的任务。</p>
        <label v-else
          ><input
            v-model="w.acknowledged"
            type="checkbox"
            :disabled="
              !writable ||
              w.busy ||
              w.loading ||
              !w.fresh ||
              !w.status ||
              ['blocked', 'syncing'].includes(w.status.state)
            "
          />我确认重新发布这部漫画的当前资料</label
        >
        <button class="mw-button mw-button-primary" :disabled="!w.canSubmit" @click="w.submit">
          {{ w.receipt ? '重试原同步受理' : '确认重新同步' }}
        </button>
        <button v-if="w.receipt" class="mw-button" :disabled="w.busy" @click="w.resetReceipt">
          放弃原受理，重新核对
        </button>
      </template>
    </section>
  </ManagementDialog>
</template>
<style scoped>
.sidecar-source {
  display: flex;
  gap: 16px;
  align-items: center;
  margin: 18px 0;
}
.sidecar-source > :first-child {
  width: 68px;
  flex: none;
  aspect-ratio: 2/3;
  object-fit: cover;
  border-radius: var(--atlas-radius-cover);
}
.sidecar-source h3 {
  margin: 6px 0;
}
.sidecar-state,
.sidecar-result,
.sidecar-confirm {
  border-top: 1px solid var(--atlas-line);
  margin-top: 20px;
  padding-top: 12px;
}
.sidecar-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.sidecar-badge {
  display: inline-block;
  margin: 8px 0;
  padding: 6px 10px;
  background: var(--atlas-surface);
  border-radius: 6px;
}
.sidecar-id {
  display: block;
  overflow-wrap: anywhere;
  color: var(--atlas-muted);
  font-size: 11px;
}
.sidecar-confirm label {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  line-height: 1.7;
  margin: 18px 0;
}
.sidecar-confirm button {
  margin: 4px 8px 4px 0;
}
</style>
