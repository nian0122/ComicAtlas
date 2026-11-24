<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import { AppSearch } from '@/shared/ui/search'
import { comicCoverUrl } from '@/entities/comic'
import type { Task } from '@/entities/task'
import { ManagementIcon, ManagementImage, formatDate, taskStatusLabels, taskStageLabel } from '@/shared/ui/management'
import { AppPagination } from '@/shared/ui/pagination'
import type { MetadataTarget } from '../model/types'
import { useMetadataBatch } from '../model/use-metadata-batch'
import MetadataTagPicker from './MetadataTagPicker.vue'
const props = defineProps<{ targets: MetadataTarget[]; previewId: string; writable: boolean }>()
const emit = defineEmits<{
  state: [value: { busy: boolean; dirty: boolean }]
  accepted: [task: Task]
  task: [id: string]
}>()
const w = reactive(useMetadataBatch(props, (task) => emit('accepted', task)))
const steps = [
  { key: 'scope', label: '选择范围' },
  { key: 'fields', label: '修改字段' },
  { key: 'review', label: '核对' },
] as const
const step = ref<'scope' | 'fields' | 'review'>(props.previewId ? 'review' : 'scope')
const stepIndex = computed(() => steps.findIndex((item) => item.key === step.value))
const root = ref<HTMLElement>()
const stage = ref<HTMLElement>()
const selectedBox = ref<HTMLElement>()
const selectedWidth = ref(0)
let selectedObserver: ResizeObserver | undefined
const selectedColumns = computed(() => Math.max(1, Math.floor((selectedWidth.value + 10) / 190)))
const selectedRows = computed(() => {
  const size = selectedColumns.value,
    rows: { key: string; targets: MetadataTarget[] }[] = []
  for (let index = 0; index < props.targets.length; index += size) {
    const targets = props.targets.slice(index, index + size)
    rows.push({ key: targets[0]!.id, targets })
  }
  return rows
})
function measureSelected() {
  const width = selectedBox.value?.clientWidth ?? 0
  if (width > 0) selectedWidth.value = width
}
watch(
  selectedBox,
  (element) => {
    selectedObserver?.disconnect()
    if (!element) return
    measureSelected()
    if (typeof ResizeObserver === 'undefined') return
    selectedObserver = new ResizeObserver(measureSelected)
    selectedObserver.observe(element)
  },
  { flush: 'post' },
)
onBeforeUnmount(() => selectedObserver?.disconnect())
watch(
  () => [w.selectionMode, props.targets.length],
  async () => {
    await nextTick()
    measureSelected()
  },
)
const allActive = computed(() => w.selectionMode === 'filter' && !Object.values(w.filter).some(Boolean))
const hasFilter = computed(() => Object.values(w.filter).some(Boolean))
const scopeLabel = computed(() =>
  w.selectionMode === 'ids'
    ? w.chosen.length
      ? `当前勾选中的 ${w.chosen.length} 部作品`
      : '尚未选择任何作品'
    : allActive.value
      ? `全部活动漫画${w.excluded.length ? `，已排除 ${w.excluded.length} 部` : ''}`
      : `符合筛选条件的活动漫画${w.excluded.length ? `，已排除 ${w.excluded.length} 部` : ''}`,
)
const scopeConditions = computed<string[]>(() => {
  if (w.selectionMode !== 'filter') return []
  const filter = w.filter
  const readStatuses: Record<string, string> = {
    unread: '未读',
    read: '已有阅读位置',
    reading: '阅读中',
    completed: '已读完',
  }
  const reactions: Record<string, string> = { any: '有标记', like: '喜欢', dislike: '不喜欢' }
  return [
    filter.keyword && `搜索：${filter.keyword}`,
    filter.categoryId &&
      `分类：${w.categories.find((term) => term.id === filter.categoryId)?.name ?? filter.categoryId}`,
    filter.tagId && `标签：${w.tags.find((term) => term.id === filter.tagId)?.name ?? filter.tagId}`,
    filter.readStatus && `阅读：${readStatuses[filter.readStatus]}`,
    filter.reaction && `标记：${reactions[filter.reaction]}`,
  ].filter((condition): condition is string => !!condition)
})
const enabledFields = computed(() => {
  const field = w.field,
    labels: string[] = []
  if (field.titleOn) labels.push('标题')
  if (field.authorOn) labels.push('作者')
  if (field.descriptionOn) labels.push('简介')
  if (field.categoryMode === 'set') labels.push('分类')
  if (field.categoryMode === 'clear') labels.push('清空分类')
  if (field.addTags.length) labels.push('新增标签')
  if (field.removeTags.length) labels.push('移除标签')
  return labels
})
const hasPatch = computed(() => enabledFields.value.length > 0)
const fieldIssues = computed(() => {
  const field = w.field,
    issues: { title?: string; category?: string } = {}
  if (field.titleOn && !field.title.trim()) issues.title = '标题不能为空'
  if (field.categoryMode === 'set' && !field.category) issues.category = '请选择分类'
  return issues
})
const canPreview = computed(() => hasPatch.value && !fieldIssues.value.title && !fieldIssues.value.category)
const tagsOpen = ref(false)
watch(
  () => [w.field.addTags.length, w.field.removeTags.length],
  ([add, remove]) => {
    if (add || remove) tagsOpen.value = true
  },
)
function onTagsToggle(event: Event) {
  tagsOpen.value = (event.target as HTMLDetailsElement).open
}
const scopeBlocked = computed(
  () => w.busy || w.loading || w.locked || !props.writable || (w.selectionMode === 'ids' && !w.chosen.length),
)
const footerNote = computed(() => {
  if (w.busy) return '正在处理，请勿关闭'
  if (w.loading) return '正在读取预览…'
  if (step.value === 'scope')
    return w.selectionMode === 'ids'
      ? w.chosen.length
        ? '仅处理勾选作品，可执行性在预览时逐本核定'
        : '请至少勾选一部作品'
      : '作品数量与可执行项在生成预览时核定'
  if (step.value === 'fields') {
    const issue = fieldIssues.value.title || fieldIssues.value.category
    if (issue) return issue
    return enabledFields.value.length ? `已启用：${enabledFields.value.join('、')}` : '至少启用一项修改'
  }
  if (w.stale) return '范围或修改已变化，需重新预览'
  if (w.expired && w.preview && !w.preview.taskId && !w.receipt) return '预览已过期，需重新生成'
  if (w.preview)
    return w.preview.taskId
      ? `任务${taskStatusLabels[w.task?.status ?? 'queued']} · 本批不会被再次提交`
      : `${w.history ? '已恢复冻结预览' : '范围已冻结'} · 有效期至 ${formatDate(w.preview.expiresAt)}`
  return ''
})
function chooseMode(mode: 'ids' | 'filter') {
  if (w.busy || w.loading || w.locked || !props.writable || w.selectionMode === mode) return
  w.selectionMode = mode
}
function clearFilter() {
  w.filter.keyword = ''
  w.filter.categoryId = ''
  w.filter.tagId = ''
  w.filter.readStatus = ''
  w.filter.reaction = ''
}
const scopeSourceNote = computed(() =>
  w.selectionMode === 'ids'
    ? '只处理勾选的作品；版本与可执行性在生成预览时逐本核定。'
    : '按条件跨页选择，不沿用漫画库的副本与排序筛选；回收站内容不在范围内。',
)
watch(
  () => w.preview,
  (value, previous) => {
    if (value && !previous) step.value = 'review'
    if (!value && previous) step.value = 'scope'
  },
)
watch(step, async (value) => {
  await nextTick()
  if (stage.value) stage.value.scrollTop = 0
  root.value?.querySelector<HTMLElement>(`[data-step="${value}"]`)?.focus({ preventScroll: true })
})
watch(
  () => w.error,
  async (value) => {
    if (!value) return
    await nextTick()
    if (stage.value) stage.value.scrollTop = 0
  },
)
async function createPreview() {
  await w.create()
  if (w.preview && w.fresh && !w.stale) step.value = 'review'
}
const counts = computed(() => {
  if (!w.preview) return []
  const value = w.preview
  return [
    { label: '本次作品', value: value.selectedCount },
    { label: value.taskId ? '原可执行' : '可执行', value: value.eligibleCount },
    { label: '已阻止', value: value.blockedCount },
    { label: '待执行', value: value.pendingCount },
    { label: '已修改', value: value.succeededCount },
    { label: '失败', value: value.failedCount },
    { label: '已取消', value: value.cancelledCount },
  ].filter((item, index) => index < 2 || (item.value > 0 && (index !== 3 || !!value.taskId)))
})
watch(
  () => [w.busy, w.dirty],
  () => emit('state', { busy: w.busy, dirty: w.dirty }),
  { immediate: true },
)
const patchNames: Record<string, string> = {
  title: '统一标题',
  author: '作者',
  description: '简介',
  categoryId: '分类',
  clearCategory: '清空分类',
  addTagIds: '新增标签',
  removeTagIds: '移除标签',
}
const itemLabels: Record<string, string> = {
  pending: '待执行',
  eligible: '可执行',
  blocked: '已阻止',
  succeeded: '已修改',
  failed: '修改失败',
  cancelled: '已取消',
}
const errors: Record<string, string> = {
  METADATA_VERSION_CHANGED: '资料版本已变化，未覆盖',
  TARGET_NOT_FOUND: '目标不存在或已回收',
  METADATA_PATCH_REJECTED: '词条引用或资料规则已变化',
  BATCH_CANCELLED: '未执行项已取消',
}
function patchValue(key: string, value: unknown) {
  if (key === 'categoryId') return w.categories.find((term) => term.id === value)?.name ?? String(value)
  if (Array.isArray(value)) return value.map((id) => w.tags.find((term) => term.id === id)?.name ?? id).join('、')
  if (key === 'clearCategory') return '是'
  return value === '' ? '清空此字段' : String(value)
}
</script>
<template>
  <section ref="root" class="metadata-batch">
    <div class="metadata-flow">
      <ol v-if="!w.history" class="metadata-steps" aria-label="统一修改步骤">
        <li
          v-for="(item, index) in steps"
          :key="item.key"
          :class="{ 'is-current': step === item.key, 'is-done': stepIndex > index }"
          :aria-current="step === item.key ? 'step' : undefined"
        >
          <span class="metadata-step-mark" aria-hidden="true"
            ><ManagementIcon v-if="stepIndex > index" name="check" :size="12" /><template v-else>{{
              index + 1
            }}</template></span
          ><span class="metadata-step-label">{{ item.label }}</span>
        </li>
      </ol>
      <section class="metadata-scope-summary" aria-label="本次修改范围" :class="{ 'is-history': w.history }">
        <p class="metadata-scope-line">
          <span class="metadata-scope-label">{{
            w.history
              ? w.preview?.taskId
                ? '历史预览'
                : '已恢复预览'
              : w.stale
                ? '待重新预览'
                : w.preview
                  ? '预览范围'
                  : '本次范围'
          }}</span>
          <strong v-if="!w.history" :title="scopeLabel">{{ scopeLabel }}</strong>
          <strong v-else>{{ w.preview ? `本次作品 ${w.preview.selectedCount} 部` : '正在读取冻结预览' }}</strong>
          <span v-if="allActive && !w.history" class="metadata-scope-flag">未设置条件</span>
        </p>
        <ul v-if="!w.history && scopeConditions.length" class="metadata-scope-conditions" aria-label="本次筛选条件">
          <li v-for="condition in scopeConditions" :key="condition">{{ condition }}</li>
        </ul>
        <p v-if="!w.history && w.excluded.length" class="metadata-scope-exclusion">
          已排除 {{ w.excluded.length }} 部
          <button type="button" class="metadata-text-button" @click="w.excluded = []">清除排除</button>
        </p>
        <p v-if="w.history && !w.preview?.taskId" class="metadata-scope-history">
          预览编号 {{ w.preview?.id ?? '—' }} · 冻结范围与补丁由服务端返回
        </p>
      </section>
    </div>
    <div ref="stage" class="metadata-stage-scroll">
      <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
      <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
      <p v-if="w.termsError" class="mw-error" role="alert">{{ w.termsError }}</p>
      <fieldset
        v-if="!w.history && step === 'scope'"
        data-step="scope"
        tabindex="-1"
        :disabled="w.busy || w.loading || w.locked || !writable"
      >
        <legend>选择要修改的漫画</legend>
        <p class="metadata-stage-note">{{ scopeSourceNote }}</p>
        <div v-if="targets.length" class="metadata-range-mode" role="group" aria-label="资料编辑选择范围">
          <button
            type="button"
            class="metadata-range-option"
            :aria-label="'当前勾选 ' + targets.length + ' 部'"
            :aria-pressed="w.selectionMode === 'ids'"
            :disabled="w.busy || w.loading || w.locked || !writable"
            @click="chooseMode('ids')"
          >
            <strong>当前勾选 {{ targets.length }} 部</strong><small>只处理勾选的作品</small>
          </button>
          <button
            type="button"
            class="metadata-range-option"
            aria-label="按条件跨页选择"
            :aria-pressed="w.selectionMode === 'filter'"
            :disabled="w.busy || w.loading || w.locked || !writable"
            @click="chooseMode('filter')"
          >
            <strong>按条件跨页选择</strong><small>可跨页匹配并逐部排除</small>
          </button>
        </div>
        <div v-if="w.selectionMode === 'ids'" ref="selectedBox" class="metadata-selected">
          <DynamicScroller :items="selectedRows" key-field="key" :min-item-size="80" page-mode>
            <template #default="{ item, index, active }">
              <DynamicScrollerItem :item="item" :active="active" :index="index">
                <div
                  class="metadata-selected-row"
                  :style="{ gridTemplateColumns: `repeat(${selectedColumns}, minmax(0, 1fr))` }"
                >
                  <label
                    v-for="target in item.targets"
                    :key="target.id"
                    class="metadata-target"
                    :class="{ 'is-chosen': w.chosen.includes(target.id) }"
                    ><input
                      v-model="w.chosen"
                      type="checkbox"
                      :value="target.id"
                      :aria-label="'纳入' + target.title"
                    /><ManagementImage :src="target.cover" :alt="target.title" /><span class="metadata-target-title">{{
                      target.title
                    }}</span></label
                  >
                </div>
              </DynamicScrollerItem>
            </template>
          </DynamicScroller>
        </div>
        <div v-else class="metadata-filter-panel">
          <div class="metadata-filter-toolbar">
            <span>筛选条件</span>
            <button v-if="hasFilter" type="button" class="metadata-text-button" @click="clearFilter">清除条件</button>
          </div>
          <div class="metadata-filter">
            <label class="mw-field metadata-wide"
              >标题、原题或作者<AppSearch
                v-model="w.filter.keyword"
                label="批量资料搜索条件"
                placeholder="输入标题、原题或作者"
                :disabled="w.busy || w.loading || w.locked || !writable"
            /></label>
            <label class="mw-field"
              >范围分类<select v-model="w.filter.categoryId">
                <option value="">不限分类</option>
                <option v-for="term in w.categories" :key="term.id" :value="term.id">{{ term.name }}</option>
              </select></label
            >
            <label class="mw-field"
              >范围标签<select v-model="w.filter.tagId">
                <option value="">不限标签</option>
                <option v-for="term in w.tags" :key="term.id" :value="term.id">{{ term.name }}</option>
              </select></label
            >
            <label class="mw-field"
              >范围阅读状态<select v-model="w.filter.readStatus">
                <option value="">不限阅读状态</option>
                <option value="unread">未读</option>
                <option value="read">已有阅读位置</option>
                <option value="reading">阅读中</option>
                <option value="completed">已读完</option>
              </select></label
            >
            <label class="mw-field"
              >范围标记<select v-model="w.filter.reaction">
                <option value="">不限标记</option>
                <option value="any">有标记</option>
                <option value="like">喜欢</option>
                <option value="dislike">不喜欢</option>
              </select></label
            >
          </div>
        </div>
        <p v-if="!targets.length" class="metadata-entry-hint">
          需要为每部作品分别填写？先在漫画库勾选 1—100 部作品，再点工具栏的“编辑资料”。
        </p>
        <details v-if="!w.receipt" class="metadata-restore">
          <summary>恢复已生成的批量预览</summary>
          <p class="mw-muted">
            这里填写的是批量预览编号（在预览页「版本保护与预览详情」中复制，形如 preview_…），不是漫画
            ID。查看或编辑单本作品，请先在漫画库勾选后点工具栏的“编辑资料”。
          </p>
          <label class="mw-field">批量预览编号<input v-model="w.restoreId" placeholder="preview_…" /></label
          ><button class="mw-button" :disabled="w.busy || w.loading" @click="w.restore()">读取资料预览</button>
          <button v-if="w.dirty" class="mw-button" :disabled="w.busy || w.loading" @click="w.restore(true)">
            放弃当前编辑并读取预览
          </button>
        </details>
      </fieldset>
      <fieldset
        v-if="!w.history && step === 'fields'"
        data-step="fields"
        tabindex="-1"
        :disabled="w.busy || w.loading || w.locked || !writable"
      >
        <legend>选择修改字段</legend>
        <p class="metadata-stage-note">只修改启用的字段，原题及其他资料保持不变。</p>
        <div class="metadata-field-list">
          <div class="metadata-field-row" :class="{ 'is-on': w.field.titleOn }">
            <div class="metadata-field-head">
              <label class="metadata-field-toggle"
                ><input v-model="w.field.titleOn" type="checkbox" aria-label="统一替换标题" /><span>标题</span></label
              >
              <small>统一替换所有作品标题</small>
            </div>
            <div class="metadata-field-control">
              <input
                v-if="w.field.titleOn"
                v-model="w.field.title"
                aria-label="统一标题"
                placeholder="输入新的标题"
                :aria-invalid="fieldIssues.title ? true : undefined"
                :aria-describedby="fieldIssues.title ? 'metadata-title-error' : undefined"
              />
              <small v-if="fieldIssues.title" id="metadata-title-error" class="metadata-field-hint is-error">{{
                fieldIssues.title
              }}</small>
            </div>
          </div>
          <div class="metadata-field-row" :class="{ 'is-on': w.field.authorOn }">
            <div class="metadata-field-head">
              <label class="metadata-field-toggle"
                ><input v-model="w.field.authorOn" type="checkbox" aria-label="统一替换作者" /><span>作者</span></label
              >
              <small>统一替换所有作品作者</small>
            </div>
            <div class="metadata-field-control">
              <input
                v-if="w.field.authorOn"
                v-model="w.field.author"
                aria-label="统一作者"
                placeholder="输入新的作者"
              />
              <small v-if="w.field.authorOn" class="metadata-field-hint">启用后留空表示清空作者。</small>
            </div>
          </div>
          <div class="metadata-field-row" :class="{ 'is-on': w.field.descriptionOn }">
            <div class="metadata-field-head">
              <label class="metadata-field-toggle"
                ><input v-model="w.field.descriptionOn" type="checkbox" aria-label="统一替换简介" /><span
                  >简介</span
                ></label
              >
              <small>统一替换所有作品简介</small>
            </div>
            <div class="metadata-field-control">
              <textarea
                v-if="w.field.descriptionOn"
                v-model="w.field.description"
                aria-label="统一简介"
                rows="3"
                placeholder="输入新的简介"
              />
              <small v-if="w.field.descriptionOn" class="metadata-field-hint">启用后留空表示清空简介。</small>
            </div>
          </div>
          <div class="metadata-field-row">
            <div class="metadata-field-head">
              <span class="metadata-field-toggle">分类</span>
              <small>不修改则保留原分类</small>
            </div>
            <div class="metadata-field-control">
              <div class="metadata-choice" role="radiogroup" aria-label="分类操作">
                <label :class="{ 'is-on': w.field.categoryMode === 'keep' }"
                  ><input v-model="w.field.categoryMode" type="radio" value="keep" /><span>不修改</span></label
                >
                <label :class="{ 'is-on': w.field.categoryMode === 'set' }"
                  ><input v-model="w.field.categoryMode" type="radio" value="set" /><span>设置</span></label
                >
                <label :class="{ 'is-on': w.field.categoryMode === 'clear' }"
                  ><input v-model="w.field.categoryMode" type="radio" value="clear" /><span>清空</span></label
                >
              </div>
              <select
                v-if="w.field.categoryMode === 'set'"
                v-model="w.field.category"
                aria-label="设置为分类"
                :aria-invalid="fieldIssues.category ? true : undefined"
                :aria-describedby="fieldIssues.category ? 'metadata-category-error' : undefined"
              >
                <option value="">请选择分类</option>
                <option v-for="term in w.categories" :key="term.id" :value="term.id">{{ term.name }}</option>
              </select>
              <small v-if="fieldIssues.category" id="metadata-category-error" class="metadata-field-hint is-error">{{
                fieldIssues.category
              }}</small>
              <small v-if="w.field.categoryMode === 'clear'" class="metadata-field-hint"
                >本次会清空范围内作品的分类。</small
              >
            </div>
          </div>
          <details class="metadata-tag-fields" :open="tagsOpen" @toggle="onTagsToggle">
            <summary>
              标签<span v-if="w.field.addTags.length || w.field.removeTags.length">
                · 新增 {{ w.field.addTags.length }}、移除 {{ w.field.removeTags.length }}</span
              >
            </summary>
            <div class="metadata-filter metadata-tag-grid">
              <div class="mw-field">
                <span>新增标签</span
                ><MetadataTagPicker
                  v-model="w.field.addTags"
                  :tags="w.tags"
                  label="新增标签"
                  search-label="搜索新增标签"
                  clear-label="清空新增标签选择"
                />
              </div>
              <div class="mw-field">
                <span>移除标签</span
                ><MetadataTagPicker
                  v-model="w.field.removeTags"
                  :tags="w.tags"
                  label="移除标签"
                  search-label="搜索移除标签"
                  clear-label="清空移除标签选择"
                />
              </div>
            </div>
          </details>
        </div>
      </fieldset>
      <section v-if="w.preview && step === 'review'" class="metadata-review-stage" data-step="review" tabindex="-1">
        <div class="metadata-heading">
          <div>
            <h3>{{ w.preview.taskId ? '逐本修改结果' : '核对修改' }}</h3>
            <p class="metadata-stage-note">
              {{
                w.history
                  ? '原筛选条件未在查询接口中返回；下面展示服务端已冻结的范围与补丁。'
                  : '预览后新增的匹配漫画不属于这次冻结范围。'
              }}
            </p>
          </div>
          <button class="mw-button" :disabled="w.busy || w.loading" @click="w.refresh()">刷新资料批次</button>
        </div>
        <p v-if="w.stale" class="mw-error">范围或修改已变化，当前仅显示旧预览。重新预览后才能确认。</p>
        <p v-if="w.expired && !w.preview.taskId && !w.receipt" class="mw-error">预览已过期，请重新生成。</p>
        <dl class="metadata-stats">
          <div v-for="item in counts" :key="item.label">
            <dt>{{ item.label }}</dt>
            <dd>{{ item.value }}</dd>
          </div>
        </dl>
        <section class="metadata-patch" aria-label="修改内容">
          <h4>修改内容</h4>
          <dl class="mw-properties">
            <template v-for="(value, key) in w.preview.patch" :key="key"
              ><dt>{{ patchNames[key] }}</dt>
              <dd class="metadata-value">{{ patchValue(key, value) }}</dd></template
            >
          </dl>
        </section>
        <details class="metadata-batch-details">
          <summary>版本保护与预览详情</summary>
          <p class="mw-muted">预览编号</p>
          <code class="metadata-id">{{ w.preview.id }}</code>
          <p class="mw-muted">
            有效期至
            {{
              formatDate(w.preview.expiresAt)
            }}。预览冻结本次作品范围；执行时仍核对逐本版本和词条引用。未启用字段和原题保持不变，任务结束不代表每部作品都成功。
          </p>
        </details>
        <div v-if="w.task || w.receipt" class="metadata-task">
          <p v-if="w.task">
            任务 {{ taskStatusLabels[w.task.status] }} · 第 {{ w.task.attempt }} 次 ·
            {{ taskStageLabel(w.task.stage) }}
          </p>
          <p v-if="w.task?.failure" class="mw-error">{{ w.task.failure.message }} {{ w.task.failure.action }}</p>
          <div v-if="w.task?.canCancel || w.task?.canRetry" class="mw-inline-actions">
            <button
              v-if="w.task?.canCancel"
              class="mw-button"
              :disabled="w.busy || w.loading || !writable"
              @click="w.action('cancel')"
            >
              取消资料批量任务</button
            ><button
              v-if="w.task?.canRetry"
              class="mw-button"
              :disabled="w.busy || w.loading || !writable"
              @click="w.action('retry')"
            >
              保护重试资料任务
            </button>
          </div>
          <p v-if="w.receipt" class="mw-notice">
            未能确认上次是否受理。保留原预览和同一幂等键重试，即使原预览已过期也不开始另一批。
          </p>
        </div>
        <div class="metadata-results">
          <article v-for="item in w.rows" :key="item.position">
            <ManagementImage :src="item.status === 'blocked' ? null : comicCoverUrl(item.comicId)" :alt="item.title" />
            <div>
              <p class="metadata-result-title">
                <strong>{{ item.title }}</strong
                ><span class="metadata-status" :class="'is-' + item.status">{{
                  itemLabels[item.status] ?? item.status
                }}</span>
              </p>
              <small
                >冻结 v{{ item.version
                }}<template v-if="item.updatedVersion !== null"> → v{{ item.updatedVersion }}</template></small
              >
              <p v-if="item.errorCode" class="mw-error">{{ errors[item.errorCode] ?? item.errorCode }}</p>
            </div>
            <button
              v-if="!w.locked"
              class="mw-button"
              :disabled="w.busy || w.loading"
              :aria-label="'排除' + item.title"
              @click="w.exclude(item.comicId)"
            >
              {{
                w.excluded.includes(item.comicId) || (w.selectionMode === 'ids' && !w.chosen.includes(item.comicId))
                  ? '已排除'
                  : '排除此作品'
              }}
            </button>
          </article>
        </div>
        <p v-if="w.fresh && !w.rows.length" class="mw-muted metadata-results-empty">没有匹配的作品。</p>
        <footer v-if="w.total > 20" class="mw-pagination">
          <span>共 {{ w.total }} 部</span
          ><AppPagination
            :page="w.page"
            :pages="Math.ceil(w.total / 20)"
            :disabled="w.loading || w.busy"
            label="资料结果分页"
            @change="w.refresh"
          />
        </footer>
        <button
          v-if="w.preview.taskId"
          class="mw-button metadata-new"
          :disabled="w.busy || w.loading"
          @click="w.newDraft"
        >
          开始另一批（已有任务继续）
        </button>
      </section>
    </div>
    <footer class="metadata-footer">
      <label
        v-if="step === 'review' && w.preview && !w.preview.taskId && !w.receipt"
        class="metadata-check metadata-check-footer"
        ><input
          v-model="w.acknowledged"
          type="checkbox"
          :disabled="w.busy || w.loading || w.stale || w.expired || !w.fresh || !writable"
        />{{
          w.history
            ? '我已核对预览编号、冻结的作品数量与修改内容，接受逐本版本校验及部分失败'
            : '我已核对冻结范围和修改内容，接受逐本版本校验及部分失败'
        }}</label
      >
      <div class="metadata-footer-bar">
        <p class="metadata-footer-note" aria-live="polite">{{ footerNote }}</p>
        <div class="metadata-footer-actions">
          <template v-if="!w.history && step === 'scope'">
            <button class="mw-button mw-button-primary" :disabled="scopeBlocked" @click="step = 'fields'">
              继续：选择修改字段
            </button>
          </template>
          <template v-else-if="!w.history && step === 'fields'">
            <button class="mw-button" :disabled="w.busy || w.loading" @click="step = 'scope'">返回选择范围</button>
            <button
              class="mw-button mw-button-primary"
              :disabled="!canPreview || w.busy || w.loading || !writable"
              @click="createPreview"
            >
              {{ w.preview ? '按新范围重新预览' : '生成资料修改预览' }}
            </button>
          </template>
          <template v-else>
            <button
              v-if="!w.history && !w.locked"
              class="mw-button"
              :disabled="w.busy || w.loading"
              @click="step = 'fields'"
            >
              修改范围或字段</button
            ><button
              v-if="!w.history && !w.locked"
              class="mw-button"
              :disabled="w.busy || w.loading"
              @click="createPreview"
            >
              按新范围重新预览</button
            ><button
              v-if="w.preview && (!w.preview.taskId || w.receipt)"
              class="mw-button mw-button-primary"
              :disabled="!w.canConfirm"
              @click="w.confirm"
            >
              {{ w.receipt ? '重试原批次确认' : '确认执行资料修改' }}</button
            ><button v-if="w.task" class="mw-button" @click="emit('task', w.task.id)">查看资料批量任务</button>
          </template>
        </div>
      </div>
    </footer>
  </section>
</template>
