<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import type { Task } from '@/entities/task'
import { ManagementDialog, ManagementImage, taskStatusLabels, taskStageLabel, formatDate } from '@/shared/ui/management'
import { useAnalysis } from '../model/use-analysis'
const props = defineProps<{ comicId: string; taskId: string; writable: boolean }>()
const emit = defineEmits<{ close: [changed: boolean]; accepted: [task: Task]; task: [id: string] }>()
const w = reactive(useAnalysis(props, (task) => emit('accepted', task)))
const labels = { title: '标题', author: '作者', description: '简介', categoryId: '分类' } as const
const reanalysisOpen = ref(false),
  discardReview = ref(false),
  pending = ref<'close' | 'task' | ''>(''),
  discardPanel = ref<HTMLElement>(),
  reviewPanel = ref<HTMLElement>(),
  reanalysisPanel = ref<HTMLElement>(),
  restartTrigger = ref<HTMLButtonElement>()
const unsavedReview = computed(() => !w.saved && (w.fields.length > 0 || w.tagIds.length > 0))
async function leave(action: 'close' | 'task') {
  if (w.busy) return
  if (unsavedReview.value) {
    pending.value = action
    await nextTick()
    discardPanel.value?.focus()
  } else finishLeave(action)
}
function finishLeave(action: 'close' | 'task') {
  pending.value = ''
  if (action === 'close') emit('close', w.writeAttempted)
  else if (w.task) emit('task', w.task.id)
}
async function continueReview() {
  pending.value = ''
  await nextTick()
  reviewPanel.value?.querySelector<HTMLInputElement>('input:checked')?.focus()
}
async function openReanalysis() {
  reanalysisOpen.value = true
  await nextTick()
  reanalysisPanel.value?.querySelector<HTMLInputElement>('input[type="number"]')?.focus()
}
async function closeReanalysis() {
  reanalysisOpen.value = false
  discardReview.value = false
  await nextTick()
  restartTrigger.value?.focus()
}
watch(
  () => w.running,
  (running) => {
    if (running) {
      reanalysisOpen.value = false
      discardReview.value = false
    }
  },
)
</script>
<template>
  <ManagementDialog
    :open="true"
    title="AI 候选审阅"
    :busy="w.busy"
    placement="right"
    size="form"
    @close="leave('close')"
  >
    <section v-if="pending" ref="discardPanel" class="analysis-discard" role="alert" tabindex="-1">
      <p>仍有未保存的候选勾选，离开后这些选择会丢失，漫画资料不会修改。</p>
      <button class="mw-button" @click="finishLeave(pending as 'close' | 'task')">放弃勾选并离开</button>
      <button class="mw-button" @click="continueReview">继续审阅</button>
    </section>
    <p v-if="w.loading" class="mw-muted">正在读取当前资料…</p>
    <div v-if="w.comic" class="analysis-source">
      <ManagementImage :src="w.comic.coverUrl" :alt="w.comic.title" />
      <div>
        <span class="mw-muted">整部漫画 · 人工审阅</span>
        <h3>{{ w.comic.title }}</h3>
      </div>
    </div>
    <p class="mw-muted">按图片抽样生成资料建议，不分析视频。AI 为可选服务；未启用时受理会明确报错，不创建成功占位。</p>
    <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
    <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
    <div v-if="w.task" class="analysis-task">
      <strong>{{ taskStatusLabels[w.task.status] }}</strong
      ><span>{{ taskStageLabel(w.task.stage) }} · 第 {{ w.task.attempt }} 次</span>
      <p v-if="w.task.failure" class="mw-error">{{ w.task.failure.message }} {{ w.task.failure.action }}</p>
      <div class="mw-inline-actions">
        <button class="mw-button" :disabled="w.busy" @click="w.refresh">刷新分析状态</button>
        <button class="mw-button" @click="leave('task')">查看任务记录</button>
        <button v-if="w.task.canCancel" class="mw-button" :disabled="w.busy || !writable" @click="w.action('cancel')">
          取消分析
        </button>
        <button v-if="w.task.canRetry" class="mw-button" :disabled="w.busy || !writable" @click="w.action('retry')">
          保护重试
        </button>
      </div>
    </div>
    <section v-if="w.result" ref="reviewPanel" class="analysis-review">
      <div class="analysis-heading">
        <h3>候选与当前资料</h3>
        <span class="mw-muted">{{ formatDate(w.result.completedAt) }}</span>
      </div>
      <p>仅保存主动勾选的项目。未知值不用于清空资料，标签以追加方式采纳。</p>
      <p v-if="w.receipt" class="mw-notice">新分析的受理结果尚未确认，请先核对原请求，再继续保存候选。</p>
      <p v-if="w.blocker && !w.saved" class="mw-error">{{ w.blocker }}</p>
      <p v-for="warning in w.result.candidate.warnings" :key="warning" class="mw-notice">{{ warning }}</p>
      <fieldset :disabled="w.busy || !writable || !!w.blocker || w.saved || !!w.receipt">
        <label v-for="(label, field) in labels" :key="field" class="analysis-field">
          <input
            v-model="w.fields"
            type="checkbox"
            :value="field"
            :disabled="w.result.candidate[field] === null"
            :aria-label="'采纳' + label"
          />
          <div>
            <strong>{{ label }}</strong
            ><small
              >当前：{{
                field === 'categoryId' ? w.comic?.category?.name || '未分类' : w.comic?.[field] || '未填写'
              }}</small
            >
            <p>
              {{
                field === 'categoryId'
                  ? w.result.request.categories.find((t) => t.id === w.result?.candidate.categoryId)?.name || '未知'
                  : (w.result.candidate[field] ?? '未知')
              }}
            </p>
          </div>
        </label>
        <h4>标签建议</h4>
        <p v-if="!w.result.candidate.tags.length" class="mw-muted">没有可靠的标签候选</p>
        <label v-for="tag in w.result.candidate.tags" :key="tag.name" class="analysis-tag">
          <input
            v-if="tag.existingId"
            v-model="w.tagIds"
            type="checkbox"
            :value="tag.existingId"
            :aria-label="'追加标签' + tag.name"
          />
          <span
            >{{ tag.name }}
            <small class="mw-muted"
              >证据页 {{ tag.evidence.join('、') }} ·
              {{ tag.existingId ? '已有标签' : '新标签建议，需到分类与标签中手动建立后重新分析' }}</small
            ></span
          >
        </label>
      </fieldset>
      <button class="mw-button mw-button-primary" :disabled="!writable || !w.canApply || !!w.receipt" @click="w.apply">
        确认保存所选资料
      </button>
      <details>
        <summary>抽样事实 · {{ w.result.request.samples.length }} 张</summary>
        <p class="mw-muted">分析依据受理时的图片、资料版本和词表，不代表逐页审阅。</p>
        <ul>
          <li v-for="sample in w.result.request.samples" :key="sample.mediaId">
            第 {{ sample.ordinal }} 页 · {{ sample.width }} × {{ sample.height }} · {{ sample.contentType }} ·
            {{ sample.size }} 字节
          </li>
        </ul>
      </details>
    </section>
    <section v-if="w.result && !w.running && !reanalysisOpen && !w.receipt" class="analysis-restart">
      <button ref="restartTrigger" class="mw-button" :disabled="w.busy || !writable" @click="openReanalysis">
        重新分析
      </button>
    </section>
    <section
      v-if="w.comic && !w.running && (!w.result || reanalysisOpen || w.receipt)"
      ref="reanalysisPanel"
      class="analysis-create"
    >
      <div v-if="w.result" class="analysis-heading">
        <h3>开始新的分析</h3>
        <button v-if="!w.receipt" class="mw-button" :disabled="w.busy" @click="closeReanalysis">返回候选审阅</button>
      </div>
      <p v-if="w.result" class="mw-muted">
        新分析受理成功后会切换到新任务，原候选仍可从历史任务查看；未保存的勾选不会自动采纳。
      </p>
      <label v-if="w.result && unsavedReview && !w.receipt" class="analysis-discard-choice">
        <input v-model="discardReview" type="checkbox" :disabled="w.busy" />我确认放弃本次未保存的勾选，开始新的分析
      </label>
      <label
        >抽样张数<input
          v-model.number="w.sampleCount"
          type="number"
          min="1"
          max="24"
          step="1"
          :disabled="w.busy || !!w.receipt"
      /></label>
      <button
        class="mw-button"
        :class="{ 'mw-button-primary': !w.result }"
        :disabled="w.busy || w.loading || !writable || (unsavedReview && !discardReview && !w.receipt)"
        @click="w.submit"
      >
        {{ w.receipt ? '重试原受理' : w.task ? '按当前资料重新分析' : '开始候选分析' }}
      </button>
      <template v-if="w.receipt"
        ><p class="mw-notice">上次受理结果尚未确认，请先重试原请求，避免重复创建分析。</p>
        <details>
          <summary>原请求详情</summary>
          <p class="mw-muted">重试保留原资料版本、结构版本、样本数和幂等键。</p>
        </details>
        <button class="mw-button" :disabled="w.busy" @click="w.resetReceipt">放弃原受理参数，刷新资料</button></template
      >
    </section>
    <details v-if="w.comic" class="analysis-record">
      <summary>资料版本</summary>
      <p class="mw-muted">资料 v{{ w.comic.version }} · 结构 v{{ w.comic.structureVersion }}</p>
    </details>
    <button v-if="w.error && !w.comic" class="mw-button" :disabled="w.busy" @click="w.refresh">重新读取</button>
  </ManagementDialog>
</template>
<style scoped>
.analysis-source {
  display: flex;
  gap: 16px;
  align-items: center;
  margin: 18px 0;
}
.analysis-source > :first-child {
  width: 72px;
  flex: none;
  aspect-ratio: 2/3;
  object-fit: cover;
  border-radius: var(--atlas-radius-control);
}
.analysis-source h3 {
  margin: 6px 0;
}
.analysis-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.analysis-task,
.analysis-create {
  padding: 18px 0;
  border-block: 1px solid var(--atlas-line);
  margin: 18px 0;
}
.analysis-restart,
.analysis-record {
  margin-top: 24px;
}
.analysis-discard {
  padding: 16px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  border-radius: var(--atlas-radius-control);
}
.analysis-discard button {
  margin-right: 8px;
}
.analysis-discard-choice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 16px 0;
  line-height: 1.7;
}
.analysis-task > span {
  margin-left: 16px;
}
.analysis-task .mw-inline-actions {
  margin-top: 12px;
}
.analysis-review fieldset {
  border: 0;
  padding: 0;
  margin: 18px 0;
  min-width: 0;
}
.analysis-field {
  display: flex;
  gap: 12px;
  padding: 16px 0;
  border-top: 1px solid var(--atlas-line);
  align-items: flex-start;
}
.analysis-field div {
  min-width: 0;
}
.analysis-field small {
  display: block;
  opacity: 0.6;
  margin-top: 6px;
}
.analysis-field p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin-bottom: 0;
}
.analysis-tag {
  display: flex;
  gap: 10px;
  margin: 14px 0;
}
.analysis-tag small {
  display: block;
  margin-top: 4px;
}
.analysis-create input {
  width: 80px;
  margin: 0 12px;
}
.analysis-review details {
  margin-top: 24px;
}
.analysis-review li {
  margin: 8px 0;
  overflow-wrap: anywhere;
}
</style>
