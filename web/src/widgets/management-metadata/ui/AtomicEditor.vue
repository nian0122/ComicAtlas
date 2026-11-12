<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ManagementImage } from '@/shared/ui/management'
import MetadataTagPicker from './MetadataTagPicker.vue'
import type { MetadataTarget } from '../model/types'
import { useAtomicEditor } from '../model/use-atomic-editor'
const props = defineProps<{ targets: MetadataTarget[]; writable: boolean }>()
const emit = defineEmits<{ state: [value: { busy: boolean; dirty: boolean }]; review: [value: boolean]; saved: [] }>()
const w = reactive(useAtomicEditor(props, () => emit('saved')))
const reviewing = ref(false)
const reviewHeading = ref<HTMLElement>()
const reviewButton = ref<HTMLButtonElement>()
const errorMessage = ref<HTMLElement>()
const draftTools = ref<HTMLElement>()
const discardPanel = ref<HTMLElement>()
watch(
  () => w.discardAction,
  async (value) => {
    if (!value) return
    await nextTick()
    discardPanel.value?.focus()
  },
)
const editor = ref<HTMLElement>()
const queue = ref<HTMLElement>()
const activeIndex = ref(0)
const activeRow = computed(() => w.rows[activeIndex.value])
const changedIds = computed(() => new Set(w.changes.map((change) => change.id)))
const invalidIds = computed(() => new Set(w.invalidRows.map((row) => row.id)))
const activeIssues = computed(() =>
  w.validationActive ? (w.invalidRows.find((row) => row.id === activeRow.value?.id)?.issues ?? {}) : {},
)
function bookTitle(row: { id: string; title: string }) {
  return (
    (typeof row.title === 'string' && row.title.trim()) ||
    props.targets.find((target) => target.id === row.id)?.title ||
    row.id
  )
}
async function selectBook(index: number, focus: 'editor' | 'queue' | 'issue' = 'editor') {
  if (index < 0 || index >= w.rows.length || w.loading || w.busy) return
  activeIndex.value = index
  await nextTick()
  if (focus === 'queue') queue.value?.querySelector<HTMLElement>(`[data-book-index="${index}"]`)?.focus()
  else if (focus === 'issue')
    (
      editor.value?.querySelector<HTMLElement>('[aria-invalid="true"], .metadata-version-error') ??
      editor.value?.querySelector<HTMLInputElement>('input')
    )?.focus()
  else editor.value?.querySelector<HTMLInputElement>('input')?.focus()
}
function locateInvalidBook() {
  const row = w.invalidRows.find((value) => value.index > activeIndex.value) ?? w.invalidRows[0]
  if (row) void selectBook(row.index, 'issue')
}
watch(reviewing, (value) => emit('review', value), { immediate: true })
const fieldNames = { title: '标题', author: '作者', description: '简介', categoryId: '分类', tagIds: '标签' }
async function review() {
  if (!w.review()) {
    if (w.invalidRows[0]) await selectBook(w.invalidRows[0].index, 'issue')
    else {
      await nextTick()
      errorMessage.value?.focus()
    }
    return
  }
  reviewing.value = true
  await nextTick()
  reviewHeading.value?.focus()
}
async function edit() {
  reviewing.value = false
  await nextTick()
  reviewButton.value?.focus()
}
async function continueEditing() {
  await w.load()
  if (!w.fresh) return
  reviewing.value = false
  await nextTick()
  editor.value?.querySelector<HTMLInputElement>('input')?.focus()
}
async function discardDraft() {
  if (!w.discardDraft()) return
  reviewing.value = false
  await nextTick()
  editor.value?.querySelector<HTMLInputElement>('input')?.focus()
}
async function keepDraft() {
  w.discardAction = ''
  await nextTick()
  draftTools.value?.querySelector<HTMLElement>('summary')?.focus()
}
function displayValue(key: string, value: unknown, changed = false) {
  if (key === 'categoryId')
    return w.categories.find((term) => term.id === value)?.name ?? (value ? String(value) : '无分类')
  if (Array.isArray(value))
    return value.map((id) => w.tags.find((term) => term.id === id)?.name ?? id).join('、') || '无标签'
  return value === '' ? (changed ? '清空' : '未填写') : String(value)
}
watch(
  () => w.error,
  (value) => {
    if (value) reviewing.value = false
  },
)
watch(
  () => [w.busy, w.dirty],
  () => emit('state', { busy: w.busy, dirty: w.dirty }),
  { immediate: true },
)
</script>
<template>
  <section class="metadata-atomic">
    <p v-if="!reviewing" class="metadata-editor-summary">
      已选 {{ targets.length }} 部 · 已修改 {{ w.changes.length }} 部 · 核对后整批保存，原题保持不变
    </p>
    <p v-if="w.error" ref="errorMessage" class="mw-error" role="alert" tabindex="-1">
      {{ w.error }}。<template v-if="w.versions">已保存的结果仍保留；重新读取成功后才能继续编辑。</template>
      <template v-else
        >草稿已保留。<template v-if="!w.fresh">请重新读取核对后再保存，不会自动重发。</template></template
      >
    </p>
    <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
    <p v-if="w.loading" class="mw-muted">正在分组读取所选作品的当前资料…</p>
    <div v-if="w.validationActive && w.invalidRows.length" class="metadata-validation-summary">
      <p role="status">还有 {{ w.invalidRows.length }} 部作品需要修正，其他草稿已保留。</p>
      <button class="mw-button" :disabled="w.loading || w.busy" @click="locateInvalidBook">定位待修正作品</button>
    </div>
    <div v-show="!reviewing" class="metadata-workspace" :class="{ 'metadata-single': w.rows.length === 1 }">
      <nav v-if="w.rows.length > 1" ref="queue" class="metadata-queue" aria-label="逐本编辑作品">
        <ol>
          <li v-for="(row, index) in w.rows" :key="row.id">
            <button
              class="metadata-queue-item"
              :aria-label="'编辑' + (targets.find((target) => target.id === row.id)?.title ?? row.title)"
              :aria-current="activeIndex === index ? 'true' : undefined"
              :tabindex="activeIndex === index ? 0 : -1"
              :data-book-index="index"
              :disabled="w.busy || w.loading"
              @click="selectBook(index)"
              @keydown.up.prevent="selectBook(Math.max(0, index - 1), 'queue')"
              @keydown.down.prevent="selectBook(Math.min(w.rows.length - 1, index + 1), 'queue')"
              @keydown.home.prevent="selectBook(0, 'queue')"
              @keydown.end.prevent="selectBook(w.rows.length - 1, 'queue')"
            >
              <ManagementImage :src="targets.find((target) => target.id === row.id)?.cover ?? null" :alt="''" />
              <span
                ><strong>{{ bookTitle(row) }}</strong
                ><small :class="{ 'metadata-field-error': w.validationActive && invalidIds.has(row.id) }">{{
                  w.validationActive && invalidIds.has(row.id) ? '待修正' : changedIds.has(row.id) ? '已修改' : '未修改'
                }}</small></span
              >
            </button>
          </li>
        </ol>
      </nav>
      <section v-if="activeRow" ref="editor" class="metadata-current" aria-label="当前作品资料">
        <div class="metadata-current-heading">
          <h3>{{ bookTitle(activeRow) }}</h3>
          <span>{{ activeIndex + 1 }} / {{ w.rows.length }}</span>
        </div>
        <p v-if="activeIssues.version" class="metadata-field-error metadata-version-error" tabindex="-1">
          {{ activeIssues.version }}
        </p>
        <fieldset :disabled="w.busy || w.loading || !writable || !!w.versions" class="metadata-current-fields">
          <label class="mw-field"
            >标题<input
              v-model="activeRow.title"
              :aria-label="'作品' + (activeIndex + 1) + '标题'"
              :aria-invalid="activeIssues.title ? true : undefined"
              :aria-describedby="activeIssues.title ? 'atomic-title-error' : undefined"
            /><small v-if="activeIssues.title" id="atomic-title-error" class="metadata-field-error">{{
              activeIssues.title
            }}</small></label
          >
          <label class="mw-field"
            >作者<input
              v-model="activeRow.author"
              :aria-label="'作品' + (activeIndex + 1) + '作者'"
              :aria-invalid="activeIssues.author ? true : undefined"
              :aria-describedby="activeIssues.author ? 'atomic-author-error' : undefined"
            /><small v-if="activeIssues.author" id="atomic-author-error" class="metadata-field-error">{{
              activeIssues.author
            }}</small></label
          >
          <label class="mw-field metadata-wide"
            >简介<textarea
              v-model="activeRow.description"
              rows="3"
              :aria-label="'作品' + (activeIndex + 1) + '简介'"
              :aria-invalid="activeIssues.description ? true : undefined"
              :aria-describedby="activeIssues.description ? 'atomic-description-error' : undefined"
            />
            <small v-if="activeIssues.description" id="atomic-description-error" class="metadata-field-error">{{
              activeIssues.description
            }}</small>
          </label>
          <label class="mw-field"
            >分类<select
              v-model="activeRow.categoryId"
              :aria-label="'作品' + (activeIndex + 1) + '分类'"
              :aria-invalid="activeIssues.categoryId ? true : undefined"
              :aria-describedby="activeIssues.categoryId ? 'atomic-category-error' : undefined"
            >
              <option :value="null">无分类</option>
              <option v-for="term in w.categories" :key="term.id" :value="term.id">{{ term.name }}</option></select
            ><small v-if="activeIssues.categoryId" id="atomic-category-error" class="metadata-field-error">{{
              activeIssues.categoryId
            }}</small></label
          >
          <div class="mw-field metadata-wide">
            <span>标签</span>
            <MetadataTagPicker
              v-model="activeRow.tagIds"
              :tags="w.tags"
              :label="'作品' + (activeIndex + 1) + '标签'"
              search-label="搜索作品标签"
              clear-label="清空当前作品标签"
              :error="activeIssues.tagIds"
            />
          </div>
        </fieldset>
        <div v-if="w.rows.length > 1" class="mw-inline-actions metadata-book-navigation">
          <button
            class="mw-button"
            :disabled="activeIndex === 0 || w.busy || w.loading"
            @click="selectBook(activeIndex - 1)"
          >
            上一部
          </button>
          <button
            class="mw-button"
            :disabled="activeIndex === w.rows.length - 1 || w.busy || w.loading"
            @click="selectBook(activeIndex + 1)"
          >
            下一部
          </button>
          <span class="mw-muted">切换作品保留草稿</span>
        </div>
        <details class="metadata-original">
          <summary>原题与资料版本</summary>
          <p>
            原题：{{ activeRow.originalTitle ?? '未记录' }} · 资料 v{{ activeRow.version
            }}<template v-if="w.versions">
              → v{{ w.versions.find((value) => value.id === activeRow.id)?.version }}</template
            >
          </p>
        </details>
      </section>
    </div>
    <section v-if="reviewing" class="metadata-atomic-review">
      <h3 ref="reviewHeading" tabindex="-1">核对逐本修改</h3>
      <p class="mw-muted">
        共 {{ w.rows.length }} 部，{{ w.changes.length }} 部有修改。任一版本冲突或资料无效，整批都不会写入。
      </p>
      <article v-for="change in w.changes" :key="change.id" class="metadata-atomic-change">
        <ManagementImage :src="targets.find((target) => target.id === change.id)?.cover ?? null" :alt="change.title" />
        <div>
          <h4>{{ change.title }}</h4>
          <dl>
            <template v-for="field in change.fields" :key="field.key">
              <dt>{{ fieldNames[field.key] }}</dt>
              <dd>
                <span class="metadata-before">原值：{{ displayValue(field.key, field.before) }}</span
                ><span>改为：{{ displayValue(field.key, field.after, true) }}</span>
              </dd>
            </template>
          </dl>
        </div>
      </article>
    </section>
    <label v-if="reviewing && w.dirty" class="metadata-check"
      ><input
        v-model="w.acknowledged"
        type="checkbox"
        :disabled="!w.fresh || w.busy || w.loading || !writable"
      />我已核对 {{ w.rows.length }} 部作品，将按加载的资料版本整批保存</label
    >
    <div class="mw-inline-actions metadata-primary-actions">
      <button
        v-if="w.versions"
        class="mw-button mw-button-primary"
        :disabled="w.busy || w.loading || !writable"
        @click="continueEditing"
      >
        继续编辑这批作品
      </button>
      <button
        v-if="!reviewing && !w.versions"
        ref="reviewButton"
        class="mw-button mw-button-primary"
        :disabled="!writable || !w.fresh || !w.dirty || w.busy || w.loading"
        @click="review"
      >
        核对逐本修改
      </button>
      <button
        v-if="reviewing && !w.versions"
        class="mw-button mw-button-primary"
        :disabled="!writable || !w.fresh || !w.dirty || !w.acknowledged || w.busy || w.loading"
        @click="w.save"
      >
        整批保存资料</button
      ><button v-if="reviewing && !w.versions" class="mw-button" :disabled="w.busy || w.loading" @click="edit">
        返回逐本编辑
      </button>
    </div>
    <details ref="draftTools" class="metadata-draft-tools" :open="(!w.fresh && !!w.error) || !!w.discardAction">
      <summary>草稿与重新读取</summary>
      <p class="mw-muted">重新读取将使用最新资料；放弃全部草稿会还原整批作品的未保存修改，不修改已保存的资料。</p>
      <div class="mw-inline-actions">
        <button class="mw-button" :disabled="w.busy || w.loading" @click="w.load()">重新读取所选资料</button
        ><button class="mw-button" :disabled="!w.dirty || w.busy || w.loading" @click="w.discardAction = 'draft'">
          放弃全部草稿
        </button>
      </div>
      <div v-if="w.discardAction" ref="discardPanel" class="metadata-review" role="alert" tabindex="-1">
        <template v-if="w.discardAction === 'reload'">
          <p>重新读取会放弃当前所有逐本草稿，使用服务端最新资料。</p>
          <button class="mw-button" :disabled="w.loading || w.busy" @click="w.load(true)">
            确认放弃并重新读取全部资料
          </button>
        </template>
        <template v-else>
          <p>
            将放弃 {{ w.changes.length }} 部作品的全部未保存修改，还原为本次读取的资料。此操作不会修改已保存的资料。
          </p>
          <button class="mw-button mw-button-danger" :disabled="w.loading || w.busy" @click="discardDraft">
            确认放弃全部草稿
          </button>
        </template>
        <button class="mw-button" :disabled="w.loading || w.busy" @click="keepDraft">保留草稿</button>
      </div>
    </details>
  </section>
</template>
