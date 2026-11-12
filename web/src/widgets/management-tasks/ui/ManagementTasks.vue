<script setup lang="ts">
import { AppSearch } from '@/shared/ui/search'
import { nextTick, ref, watch } from 'vue'
import {
  taskTypes,
  type Task,
  type TaskSummary,
  type TaskEvent,
  type TaskItem,
  type TaskArtifacts,
  type TaskType,
} from '@/entities/task'
import ManagementImportBatch from './ManagementImportBatch.vue'
import ManagementTaskArtifacts from './ManagementTaskArtifacts.vue'
import ManagementCommandResults from './ManagementCommandResults.vue'
import ManagementBatchOutcome from './ManagementBatchOutcome.vue'
import ManagementTaskResults from './ManagementTaskResults.vue'
import { ManagementIcon, taskStatusLabels, taskStageLabel, taskTypeLabel, formatDate } from '@/shared/ui/management'
import { ManagementPagedFeed } from '@/shared/ui/pagination'
const props = defineProps<{
  tasks: Task[]
  groups: {
    status: string
    tasks: Task[]
    basePage: number
    total: number
    loadingMore: boolean
    appendError: string
  }[]
  nextEvent: number | null
  summary: TaskSummary | null
  selected: Task | null
  events: TaskEvent[]
  items: TaskItem[]
  itemTotal: number
  loading: boolean
  busy: boolean
  total: number
  listLoaded: boolean
  listFailed: boolean
  error: string
  status: string
  type: TaskType | ''
  keyword: string
  oldest: boolean
  artifacts: TaskArtifacts | null
  artifactsLoading: boolean
  parentTask: string
  returnTaskId: string
  importPage: number
}>()
const emit = defineEmits<{
  related: [id: string, position: number, relation: 'child' | 'covered']
  back: []
  importPage: [page: number]
  resultsReady: []
  storage: []
  imported: [id: string]
  metadata: [id: string]
  sidecar: [id: string]
  analysis: [id: string]
  recovery: [id: string]
  repair: [comic: string, id: string]
  directory: [id: string]
  audit: [id: string]
  inspect: [task: Task]
  scan: [id: string]
  upload: [id: string]
  inspectImport: [id: string, reference: string]
  action: [id: string, action: 'cancel' | 'retry']
  filters: [filters: { type: TaskType | ''; keyword: string; oldest: boolean; status: string }]
  refresh: []
  groupPage: [status: string, page: number]
  groupLoadMore: [status: string]
  groupRetry: [status: string]
  moreEvents: []
  moreItems: []
  close: []
}>()
const search = ref(props.keyword)
watch(
  () => props.keyword,
  (value) => {
    search.value = value
  },
)
function filter(change: Partial<{ type: TaskType | ''; oldest: boolean; status: string }> = {}) {
  emit('filters', {
    type: props.type,
    keyword: search.value.trim(),
    oldest: props.oldest,
    status: props.status,
    ...change,
  })
}
async function closeTask() {
  const id = props.returnTaskId || props.selected?.id
  emit('close')
  await nextTick()
  if (id) document.querySelector<HTMLButtonElement>(`[data-task-id="${CSS.escape(id)}"]`)?.focus()
}
function clearFilters() {
  search.value = ''
  emit('filters', { type: '', keyword: '', status: '', oldest: false })
}
</script>
<template>
  <div class="mw-task-summary" role="group" aria-label="当前类型与搜索范围的任务统计">
    <div
      v-for="item in [
        { label: '执行中', value: summary ? summary.running + summary.cancelling : null },
        { label: '等待执行', value: summary?.queued },
        { label: '执行完成', value: summary?.succeeded },
        { label: '失败任务', value: summary?.failed },
      ]"
      :key="item.label"
    >
      <span>{{ item.label }}</span
      ><strong class="mw-mono">{{ item.value ?? '—' }}</strong>
    </div>
  </div>
  <div class="mw-section-tools task-filters" role="search" aria-label="搜索任务列表">
    <AppSearch v-model="search" label="搜索任务" placeholder="搜索任务名称或编号" :disabled="busy" @search="filter()" />
    <select
      aria-label="任务类型筛选"
      :value="type"
      :disabled="busy || loading"
      @change="filter({ type: ($event.target as HTMLSelectElement).value as TaskType | '' })"
    >
      <option value="">全部类型</option>
      <option v-for="value in taskTypes" :key="value" :value="value">{{ taskTypeLabel(value) }}</option>
    </select>
    <select
      aria-label="任务时间排序"
      :value="oldest ? 'oldest' : 'newest'"
      :disabled="busy || loading"
      @change="filter({ oldest: ($event.target as HTMLSelectElement).value === 'oldest' })"
    >
      <option value="newest">最近优先</option>
      <option value="oldest">最早优先</option>
    </select>
    <span class="mw-muted">{{
      loading
        ? '读取中…'
        : listFailed && !listLoaded
          ? '数量暂不可用'
          : total + ' 条任务记录' + (listFailed ? '（上次结果）' : '')
    }}</span
    ><select
      aria-label="任务状态筛选"
      :value="status"
      :disabled="busy || loading"
      @change="filter({ status: ($event.target as HTMLSelectElement).value })"
    >
      <option value="">全部状态</option>
      <option v-for="(label, value) in taskStatusLabels" :key="value" :value="value">{{ label }}</option></select
    ><button class="mw-button" type="button" :disabled="busy || loading" @click="$emit('refresh')">
      <ManagementIcon name="refresh" />刷新
    </button>
    <button
      v-if="type || keyword || status || oldest"
      class="mw-button"
      type="button"
      :disabled="busy || loading"
      @click="clearFilters"
    >
      清除任务筛选
    </button>
  </div>
  <p class="mw-footnote">
    这里统计任务执行状态；批次执行结束不代表每项内容成功，逐项结果在详情中核对。状态筛选只影响下方记录。
  </p>
  <p v-if="error" class="mw-error" role="alert">{{ error }}</p>
  <div v-if="loading && !tasks.length" class="mw-loading">正在读取任务…</div>
  <div v-else-if="listFailed && !tasks.length && !selected" class="mw-empty">
    <ManagementIcon name="warning" :size="40" />
    <h2>暂时无法读取任务</h2>
    <p>请使用上方“刷新”重试，读取失败不代表没有任务。</p>
  </div>
  <div v-else-if="!tasks.length && !selected" class="mw-empty">
    <ManagementIcon name="task" :size="40" />
    <h2>没有匹配的任务</h2>
    <p>导入和维护任务会在这里留下实际执行记录。</p>
  </div>
  <div v-else class="mw-task-layout">
    <div class="mw-task-list">
      <template v-for="group in groups.filter((item) => item.total > 0)" :key="group.status">
        <h2 class="mw-task-group-title">
          {{
            group.status === 'succeeded' ? '执行完成' : taskStatusLabels[group.status as keyof typeof taskStatusLabels]
          }}
          <small>{{ group.total }}</small>
        </h2>
        <ManagementPagedFeed
          :items="group.tasks"
          :page="group.basePage"
          :total="group.total"
          :loading="loading"
          :loading-more="group.loadingMore"
          :append-error="group.appendError"
          :disabled="loading"
          label="任务分组分页"
          @page-change="emit('groupPage', group.status, $event)"
          @load-more="emit('groupLoadMore', group.status)"
          @retry="emit('groupRetry', group.status)"
        >
          <template #default="{ items: pageTasks }">
            <button
              v-for="task in pageTasks"
              :key="task.id"
              :data-task-id="task.id"
              :class="{ active: selected?.id === task.id }"
              @click="$emit('inspect', task)"
            >
              <span class="mw-task-symbol" :class="task.status"
                ><ManagementIcon
                  :name="
                    task.status === 'failed'
                      ? 'warning'
                      : task.status === 'succeeded'
                        ? 'check'
                        : task.status === 'running'
                          ? 'loading'
                          : 'clock'
                  "
                  :class="{ 'mw-spin': task.status === 'running' }" /></span
              ><span class="mw-task-copy"
                ><strong>{{ task.title || taskTypeLabel(task.type) }}</strong
                ><small>{{ taskTypeLabel(task.type) }} · {{ formatDate(task.createdAt) }}</small
                ><progress
                  v-if="task.totalCount !== null && task.totalCount > 0"
                  :aria-label="`${task.title || taskTypeLabel(task.type)}处理进度`"
                  :value="task.completedCount"
                  :max="task.totalCount"
                /><small v-if="task.totalCount !== null"
                  >已处理 {{ task.completedCount }} / {{ task.totalCount }} 项</small
                ><small v-else>已处理 {{ task.completedCount }} 项</small></span
              ><span class="mw-task-status" :class="task.status">{{
                task.status === 'succeeded' && ['command_batch', 'metadata_batch', 'import_batch'].includes(task.type)
                  ? '已结束'
                  : taskStatusLabels[task.status]
              }}</span>
            </button>
          </template>
        </ManagementPagedFeed>
      </template>
    </div>
    <section v-if="selected" class="mw-task-detail">
      <div class="mw-detail-heading">
        <ManagementIcon name="info" />
        <p>执行详情</p>
        <button class="mw-icon-button" aria-label="关闭任务详情" @click="closeTask">
          <ManagementIcon name="close" />
        </button>
        <span class="mw-task-status" :class="selected.status">{{
          selected.status === 'succeeded' && ['command_batch', 'metadata_batch', 'import_batch'].includes(selected.type)
            ? '已结束'
            : taskStatusLabels[selected.status]
        }}</span>
      </div>
      <h2 tabindex="-1">{{ selected.title || taskTypeLabel(selected.type) }}</h2>
      <ManagementBatchOutcome
        v-if="['metadata_batch', 'command_batch'].includes(selected.type)"
        :key="selected.id"
        :task="selected"
      />
      <button v-if="parentTask" class="mw-button" :disabled="busy" @click="emit('back')">
        返回批次：{{ parentTask }}
      </button>
      <dl class="mw-properties">
        <dt>任务类型</dt>
        <dd>{{ taskTypeLabel(selected.type) }}</dd>
        <dt>当前阶段</dt>
        <dd>{{ taskStageLabel(selected.stage) }}</dd>
        <dt>尝试次数</dt>
        <dd>{{ selected.attempt }}</dd>
        <dt>开始时间</dt>
        <dd>{{ formatDate(selected.startedAt) }}</dd>
        <dt>结束时间</dt>
        <dd>{{ formatDate(selected.finishedAt) }}</dd>
      </dl>
      <details>
        <summary>任务标识</summary>
        <p class="mw-id">{{ selected.id }}</p>
      </details>
      <div v-if="selected.failure" class="mw-error">
        <strong>{{ selected.failure.message }}</strong>
        <p>{{ selected.failure.action }}</p>
        <details v-if="selected.errorCode">
          <summary>诊断代码</summary>
          <code>{{ selected.errorCode }}</code>
        </details>
      </div>
      <button v-if="selected.type === 'storage_refresh'" class="mw-button" :disabled="busy" @click="emit('storage')">
        查看当前存储观测
      </button>
      <section v-if="['directory_import', 'archive_import'].includes(selected.type)" aria-label="单次导入结果">
        <button
          v-if="selected.status === 'succeeded' && selected.comicId"
          class="mw-button"
          :disabled="busy"
          @click="emit('imported', selected.id)"
        >
          查看导入作品
        </button>
        <p v-else-if="selected.status === 'succeeded'" class="mw-error" role="alert">
          已完成任务未返回作品标识，请刷新任务后核对。
        </p>
        <p v-else class="mw-muted">导入成功后可从这里打开作品；受理或处理中不代表作品已发布。</p>
      </section>
      <div class="mw-inline-actions">
        <button
          v-if="selected.canCancel"
          class="mw-button"
          :disabled="busy"
          @click="$emit('action', selected.id, 'cancel')"
        >
          取消任务</button
        ><button
          v-if="selected.canRetry"
          class="mw-button mw-button-primary"
          :disabled="busy"
          @click="$emit('action', selected.id, 'retry')"
        >
          <ManagementIcon name="refresh" />重试此任务
        </button>
      </div>
      <button
        v-if="selected.type === 'metadata_batch' && selected.targetType === 'batch_preview' && selected.targetId"
        class="mw-button"
        :disabled="busy"
        @click="emit('metadata', selected.targetId)"
      >
        查看资料修改批次
      </button>
      <button
        v-if="selected.type === 'metadata_sync'"
        class="mw-button"
        :disabled="busy"
        @click="emit('sidecar', selected.id)"
      >
        查看侧车同步与发布结果
      </button>
      <button
        v-if="selected.type === 'ai_analysis'"
        class="mw-button"
        :disabled="busy"
        @click="emit('analysis', selected.id)"
      >
        查看 AI 候选与分析
      </button>
      <button
        v-if="selected.type === 'directory_scan'"
        class="mw-button mw-button-primary"
        :disabled="busy"
        @click="emit('scan', selected.id)"
      >
        查看扫描候选
      </button>
      <button
        v-if="selected.type === 'media_upload' && selected.targetId"
        class="mw-button"
        :disabled="busy"
        @click="emit('upload', selected.targetId)"
      >
        查看上传会话
      </button>
      <button
        v-if="selected.type === 'lifecycle_audit' && selected.targetType === 'trash_entry' && selected.targetId"
        class="mw-button"
        :disabled="busy"
        @click="emit('audit', selected.targetId)"
      >
        查看回收核对报告
      </button>
      <div v-if="selected.type === 'lifecycle'" class="mw-inline-actions">
        <button
          v-for="id in [
            ...new Set(items.map((item) => item.entryId).filter((id): id is string => typeof id === 'string' && !!id)),
          ]"
          :key="id"
          class="mw-button"
          :disabled="busy"
          @click="emit('audit', id)"
        >
          核对历史 {{ id }}
        </button>
      </div>
      <button
        v-if="
          ['directory_preflight', 'directory_move'].includes(selected.type) &&
          selected.targetType === 'directory_preview' &&
          selected.targetId
        "
        class="mw-button"
        :disabled="busy"
        @click="emit('directory', selected.targetId)"
      >
        查看文件夹移出流程
      </button>
      <button
        v-if="selected.type === 'disk_recovery'"
        class="mw-button"
        :disabled="busy"
        @click="emit('recovery', selected.id)"
      >
        查看扫盘恢复结果
      </button>
      <button
        v-if="selected.type === 'recovery_repair' && selected.targetId"
        class="mw-button"
        :disabled="busy"
        @click="emit('repair', selected.targetId, selected.id)"
      >
        查看占位补全结果
      </button>
      <ManagementImportBatch
        v-if="selected.type === 'import_batch' && selected.targetId"
        :key="selected.id"
        :batch-id="selected.targetId"
        :initial-page="importPage"
        @page="emit('importPage', $event)"
        @ready="emit('resultsReady')"
        @inspect="(id, reference) => emit('inspectImport', id, reference)"
      />
      <ManagementTaskArtifacts
        v-if="selected.type === 'archive_export'"
        :key="selected.id"
        :view="artifacts"
        :loading="artifactsLoading"
        @refresh="emit('inspect', selected)"
      />
      <h3 v-if="items.length" class="mw-task-results-heading">
        逐项结果 <span class="mw-muted">{{ items.length }} / {{ itemTotal }}</span>
      </h3>
      <button v-if="itemTotal > items.length" class="mw-button" @click="$emit('moreItems')">
        加载更多结果（{{ items.length }} / {{ itemTotal }}）
      </button>
      <ManagementCommandResults
        v-if="selected.type === 'command_batch' && items.length"
        :items="items"
        :operation="selected.targetType"
        :busy="busy"
        @related="(id, position, relation) => emit('related', id, position, relation)"
      />
      <ManagementTaskResults v-else-if="items.length" :key="selected.id" :task="selected" :items="items" />
      <details v-if="events.length">
        <summary>执行时间线</summary>
        <ol class="mw-event-list">
          <li v-for="event in events" :key="event.id">
            <span>{{ formatDate(event.occurredAt) }}</span
            ><strong>{{ event.stage ? taskStageLabel(event.stage) : event.type }}</strong
            ><small>{{ taskStatusLabels[event.status as keyof typeof taskStatusLabels] ?? event.status }}</small>
          </li>
        </ol>
        <button v-if="nextEvent !== null" class="mw-button" @click="$emit('moreEvents')">加载更多事件</button>
      </details>
    </section>
    <div v-else class="mw-empty">
      <ManagementIcon name="info" :size="32" />
      <p>选择任务查看执行详情</p>
    </div>
  </div>
</template>
<style scoped>
.task-filters {
  flex-wrap: wrap;
}
.task-filters .atlas-search {
  flex: 1 1 250px;
  min-width: 0;
}
.task-filters select {
  min-width: 0;
  max-width: 100%;
}
@media (max-width: 700px) {
  .task-filters .atlas-search {
    flex-basis: 100%;
  }
}
</style>
