<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { AppSearch } from '@/shared/ui/search'
import { comicCoverUrl } from '@/entities/comic'
import type { Task } from '@/entities/task'
import { ManagementImage, formatDate, taskStatusLabels, taskStageLabel } from '@/shared/ui/management'
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
const step = ref<'scope' | 'fields' | 'review'>(props.previewId ? 'review' : 'scope')
const root = ref<HTMLElement>()
const allActive = computed(() => w.selectionMode === 'filter' && !Object.values(w.filter).some(Boolean))
const scopeLabel = computed(() =>
  w.selectionMode === 'ids'
    ? `当前勾选中的 ${w.chosen.length} 部作品`
    : allActive.value
      ? `全部活动漫画${w.excluded.length ? `，已排除 ${w.excluded.length} 部` : ''}`
      : `符合筛选条件的活动漫画${w.excluded.length ? `，已排除 ${w.excluded.length} 部` : ''}`,
)
const scopeConditions = computed(() => {
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
  ].filter(Boolean)
})
watch(
  () => w.preview,
  (value, previous) => {
    if (value && !previous) step.value = 'review'
    if (!value && previous) step.value = 'scope'
  },
)
watch(step, async (value) => {
  await nextTick()
  root.value?.querySelector<HTMLElement>(`[data-step="${value}"]`)?.focus()
})
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
    <ol v-if="!w.history" class="metadata-steps" aria-label="统一修改步骤">
      <li :aria-current="step === 'scope' ? 'step' : undefined">选择范围</li>
      <li :aria-current="step === 'fields' ? 'step' : undefined">选择修改字段</li>
      <li :aria-current="step === 'review' ? 'step' : undefined">核对修改</li>
    </ol>
    <section v-if="!w.history" class="metadata-scope-summary" aria-label="本次修改范围">
      <strong>{{ w.stale ? '待重新预览范围：' : w.preview ? '预览范围：' : '本次范围：' }}</strong
      >{{ scopeLabel }}
      <ul v-if="scopeConditions.length" class="metadata-scope-conditions" aria-label="本次筛选条件">
        <li v-for="condition in scopeConditions" :key="String(condition)">{{ condition }}</li>
      </ul>
    </section>
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
      <label v-if="targets.length" class="mw-field"
        >选择范围<select v-model="w.selectionMode" aria-label="资料编辑选择范围">
          <option value="ids">当前勾选 {{ targets.length }} 部作品</option>
          <option value="filter">按条件跨页选择</option>
        </select></label
      >
      <div v-if="w.selectionMode === 'ids'" class="metadata-selected">
        <label v-for="target in targets" :key="target.id"
          ><input
            v-model="w.chosen"
            type="checkbox"
            :value="target.id"
            :aria-label="'纳入' + target.title"
          /><ManagementImage :src="target.cover" :alt="target.title" /><span>{{ target.title }}</span></label
        >
      </div>
      <div v-else class="metadata-filter">
        <p class="mw-muted metadata-wide">
          本面板不沿用漫画库的副本与排序筛选。作品数量将在预览时核定，回收站内容不在范围内。
        </p>
        <label class="mw-field"
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
      <p v-if="w.excluded.length" class="mw-muted">
        已排除 {{ w.excluded.length }} 部
        <button type="button" class="mw-button" @click="w.excluded = []">清除排除</button>
      </p>
      <p v-if="allActive" class="metadata-range-warning">
        未设置筛选条件：下一步将为全部活动漫画生成修改预览。回收站内容不在范围内。
      </p>
      <button
        type="button"
        class="mw-button mw-button-primary"
        :disabled="w.selectionMode === 'ids' && !w.chosen.length"
        @click="step = 'fields'"
      >
        继续：选择修改字段
      </button>
    </fieldset>
    <fieldset
      v-if="!w.history && step === 'fields'"
      data-step="fields"
      tabindex="-1"
      :disabled="w.busy || w.loading || w.locked || !writable"
    >
      <legend>选择修改字段</legend>
      <p class="mw-muted">只修改启用的字段，原题及其他资料保持不变。</p>
      <div class="metadata-filter">
        <label class="mw-field"
          ><span><input v-model="w.field.titleOn" type="checkbox" aria-label="统一替换标题" />统一替换标题</span
          ><input v-if="w.field.titleOn" v-model="w.field.title" aria-label="统一标题"
        /></label>
        <label class="mw-field"
          ><span><input v-model="w.field.authorOn" type="checkbox" aria-label="统一替换作者" />统一替换作者</span
          ><input v-if="w.field.authorOn" v-model="w.field.author" aria-label="统一作者" /><small
            v-if="w.field.authorOn"
            >启用后留空表示清空作者。</small
          ></label
        >
        <label class="mw-field metadata-wide"
          ><span><input v-model="w.field.descriptionOn" type="checkbox" aria-label="统一替换简介" />统一替换简介</span
          ><textarea v-if="w.field.descriptionOn" v-model="w.field.description" aria-label="统一简介" rows="3" /><small
            v-if="w.field.descriptionOn"
            >启用后留空表示清空简介。</small
          ></label
        >
        <label class="mw-field"
          >分类操作<select v-model="w.field.categoryMode">
            <option value="keep">不修改分类</option>
            <option value="set">设置分类</option>
            <option value="clear">清空分类</option>
          </select></label
        >
        <label v-if="w.field.categoryMode === 'set'" class="mw-field"
          >设置为分类<select v-model="w.field.category">
            <option value="">请选择分类</option>
            <option v-for="term in w.categories" :key="term.id" :value="term.id">{{ term.name }}</option>
          </select></label
        >
        <details
          class="metadata-tag-fields metadata-wide"
          :open="w.field.addTags.length > 0 || w.field.removeTags.length > 0"
        >
          <summary>
            修改标签<span v-if="w.field.addTags.length || w.field.removeTags.length">
              · 新增 {{ w.field.addTags.length }}、移除 {{ w.field.removeTags.length }}</span
            >
          </summary>
          <div class="metadata-filter">
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
      <p v-if="w.field.categoryMode === 'clear'" class="metadata-range-warning">本次会清空范围内作品的分类。</p>
    </fieldset>
    <div v-if="!w.history && !w.locked && step === 'fields'" class="mw-inline-actions metadata-step-actions">
      <button class="mw-button" :disabled="w.busy || w.loading" @click="step = 'scope'">返回选择范围</button>
      <button class="mw-button mw-button-primary" :disabled="w.busy || w.loading || !writable" @click="createPreview">
        {{ w.preview ? '按新范围重新预览' : '生成资料修改预览' }}
      </button>
    </div>
    <section v-if="w.preview && step === 'review'" class="metadata-review" data-step="review" tabindex="-1">
      <div class="metadata-heading">
        <h3>{{ w.preview.taskId ? '逐本修改结果' : '核对修改' }}</h3>
        <button class="mw-button" :disabled="w.busy || w.loading" @click="w.refresh()">刷新资料批次</button>
      </div>
      <p class="mw-muted">
        {{
          w.history
            ? '原筛选条件未在查询接口中返回；下面展示服务端已冻结的范围与补丁。'
            : '预览后新增的匹配漫画不属于这次冻结范围。'
        }}
      </p>
      <p v-if="w.stale" class="mw-error">范围或修改已变化，当前仅显示旧预览。重新预览后才能确认。</p>
      <p v-if="w.expired && !w.preview.taskId && !w.receipt" class="mw-error">预览已过期，请重新生成。</p>
      <dl class="metadata-counts">
        <div v-for="item in counts" :key="item.label">
          <dt>{{ item.label }}</dt>
          <dd>{{ item.value }}</dd>
        </div>
      </dl>
      <dl class="mw-properties">
        <template v-for="(value, key) in w.preview.patch" :key="key"
          ><dt>{{ patchNames[key] }}</dt>
          <dd class="metadata-value">{{ patchValue(key, value) }}</dd></template
        >
      </dl>
      <details class="metadata-batch-details">
        <summary>版本保护与预览详情</summary>
        <code class="metadata-id">{{ w.preview.id }}</code>
        <p class="mw-muted">
          有效期至
          {{
            formatDate(w.preview.expiresAt)
          }}。预览冻结本次作品范围；执行时仍核对逐本版本和词条引用。未启用字段和原题保持不变，任务结束不代表每部作品都成功。
        </p>
      </details>
      <div v-if="!w.history && !w.locked" class="mw-inline-actions metadata-review-actions">
        <button class="mw-button" :disabled="w.busy || w.loading" @click="step = 'fields'">修改范围或字段</button>
        <button class="mw-button" :disabled="w.busy || w.loading || !writable" @click="createPreview">
          按新范围重新预览
        </button>
      </div>
      <p v-if="w.task">
        任务 {{ taskStatusLabels[w.task.status] }} · 第 {{ w.task.attempt }} 次 · {{ taskStageLabel(w.task.stage) }}
      </p>
      <p v-if="w.task?.failure" class="mw-error">{{ w.task.failure.message }} {{ w.task.failure.action }}</p>
      <div class="metadata-results">
        <article v-for="item in w.rows" :key="item.position">
          <ManagementImage :src="item.status === 'blocked' ? null : comicCoverUrl(item.comicId)" :alt="item.title" />
          <div>
            <strong>{{ item.title }}</strong
            ><small
              >冻结 v{{ item.version
              }}<template v-if="item.updatedVersion !== null"> → v{{ item.updatedVersion }}</template> ·
              {{ itemLabels[item.status] ?? item.status }}</small
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
      <footer v-if="w.total > 20" class="mw-pagination">
        <span>第 {{ w.page }} 页 · 共 {{ w.total }} 部</span
        ><button class="mw-button" :disabled="w.page <= 1 || w.loading || w.busy" @click="w.refresh(w.page - 1)">
          上一页结果</button
        ><button
          class="mw-button"
          :disabled="w.page * 20 >= w.total || w.loading || w.busy"
          @click="w.refresh(w.page + 1)"
        >
          下一页结果
        </button>
      </footer>
      <p v-if="w.receipt" class="mw-notice">
        未能确认上次是否受理。保留原预览和同一幂等键重试，即使原预览已过期也不开始另一批。
      </p>
      <label v-if="!w.preview.taskId && !w.receipt" class="metadata-check"
        ><input
          v-model="w.acknowledged"
          type="checkbox"
          :disabled="w.busy || w.loading || w.stale || w.expired || !w.fresh || !writable"
        />我已核对冻结范围和修改内容，接受逐本版本校验及部分失败</label
      >
      <div class="mw-inline-actions">
        <button
          v-if="!w.preview.taskId || w.receipt"
          class="mw-button mw-button-primary"
          :disabled="!w.canConfirm"
          @click="w.confirm"
        >
          {{ w.receipt ? '重试原批次确认' : '确认执行资料修改' }}</button
        ><button v-if="w.task" class="mw-button" @click="emit('task', w.task.id)">查看资料批量任务</button
        ><button
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
      <button
        v-if="w.preview.taskId"
        class="mw-button metadata-new"
        :disabled="w.busy || w.loading"
        @click="w.newDraft"
      >
        开始另一批（已有任务继续）
      </button>
    </section>
    <details v-if="!w.receipt" class="metadata-restore">
      <summary>打开已有预览编号</summary>
      <label class="mw-field">资料预览编号<input v-model="w.restoreId" /></label
      ><button class="mw-button" :disabled="w.busy || w.loading" @click="w.restore()">读取资料预览</button>
      <button v-if="w.dirty" class="mw-button" :disabled="w.busy || w.loading" @click="w.restore(true)">
        放弃当前编辑并读取预览
      </button>
    </details>
  </section>
</template>
