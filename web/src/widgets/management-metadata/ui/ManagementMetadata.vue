<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Task } from '@/entities/task'
import { ManagementDialog } from '@/shared/ui/management'
import type { MetadataTarget } from '../model/types'
import AtomicEditor from './AtomicEditor.vue'
import MetadataBatch from './MetadataBatch.vue'
const props = defineProps<{ targets: MetadataTarget[]; previewId: string; writable: boolean }>()
const emit = defineEmits<{ close: []; saved: []; accepted: [task: Task]; task: [id: string] }>()
const mode = ref<'patch' | 'atomic'>('patch'),
  state = ref({ busy: false, dirty: false }),
  pending = ref<'close' | 'patch' | 'atomic' | ''>('')
const canEditIndividually = computed(() => props.targets.length > 0 && props.targets.length <= 100 && !props.previewId)
const reviewing = ref(false)
function leave(action: 'close' | 'patch' | 'atomic') {
  if (state.value.busy) return
  if (state.value.dirty) {
    pending.value = action
    return
  }
  apply(action)
}
function apply(action: 'close' | 'patch' | 'atomic') {
  pending.value = ''
  state.value = { busy: false, dirty: false }
  reviewing.value = false
  if (action === 'close') emit('close')
  else mode.value = action
}
</script>
<template>
  <ManagementDialog
    :open="true"
    title="批量编辑漫画资料"
    :busy="state.busy"
    :placement="mode === 'atomic' ? 'center' : 'right'"
    :size="mode === 'atomic' && (reviewing || targets.length === 1) ? 'form' : 'wide'"
    class="metadata-dialog"
    @close="leave('close')"
  >
    <div v-if="canEditIndividually" class="metadata-mode" role="group" aria-label="批量资料编辑方式">
      <button
        class="mw-button"
        :aria-pressed="mode === 'patch'"
        :disabled="state.busy"
        @click="mode !== 'patch' && leave('patch')"
      >
        统一修改</button
      ><button
        class="mw-button"
        :aria-pressed="mode === 'atomic'"
        :disabled="state.busy"
        @click="mode !== 'atomic' && leave('atomic')"
      >
        逐本编辑
      </button>
    </div>
    <p v-if="!targets.length && !previewId" class="mw-muted">
      当前按条件选择漫画。若需为每部作品填写不同资料，请先在漫画库勾选 1—100 部作品，再进入“编辑所选资料”。
    </p>
    <div v-if="pending" class="metadata-review" role="alert">
      <p>仍有未保存的编辑或未确认的预览，继续会放弃本面板草稿。</p>
      <button class="mw-button" @click="apply(pending as 'close' | 'patch' | 'atomic')">确认放弃并继续</button
      ><button class="mw-button" @click="pending = ''">继续编辑</button>
    </div>
    <AtomicEditor
      v-if="mode === 'atomic'"
      :targets="props.targets"
      :writable="writable"
      @state="state = $event"
      @review="reviewing = $event"
      @saved="emit('saved')"
    />
    <MetadataBatch
      v-else
      :targets="props.targets"
      :preview-id="previewId"
      :writable="writable"
      @state="state = $event"
      @accepted="emit('accepted', $event)"
      @task="emit('task', $event)"
    />
  </ManagementDialog>
</template>
<style>
.metadata-dialog .metadata-mode,
.metadata-dialog .metadata-heading {
  display: flex;
  gap: 10px;
  justify-content: space-between;
  flex-wrap: wrap;
  align-items: center;
  margin: 18px 0;
}
.metadata-dialog .metadata-mode {
  justify-content: flex-start;
}
.metadata-dialog .metadata-mode [aria-pressed='true'] {
  border-color: var(--atlas-accent);
  color: var(--atlas-text);
}
.metadata-dialog fieldset {
  border: 0;
  padding: 0;
  margin: 18px 0;
  min-width: 0;
}
.metadata-dialog legend {
  padding: 0;
  margin-bottom: 12px;
  font-size: 18px;
  font-weight: 600;
}
.metadata-dialog .metadata-steps {
  display: flex;
  gap: 24px;
  list-style: none;
  margin: 20px 0;
  padding: 0 0 14px;
  border-bottom: 1px solid var(--atlas-line);
  color: var(--atlas-muted);
  font-size: 13px;
}
.metadata-dialog .metadata-steps [aria-current='step'] {
  color: var(--atlas-text);
  font-weight: 600;
}
.metadata-dialog .metadata-scope-summary {
  margin: 16px 0;
  color: var(--atlas-muted);
}
.metadata-dialog .metadata-scope-summary strong {
  color: var(--atlas-text);
}
.metadata-dialog .metadata-scope-conditions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  padding: 0;
  margin: 8px 0 0;
  list-style: none;
  color: var(--atlas-text);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-range-warning {
  color: var(--atlas-warning);
  margin: 18px 0;
  line-height: 1.7;
}
.metadata-dialog .metadata-tag-fields {
  border-top: 1px solid var(--atlas-line);
  padding-top: 12px;
}
.metadata-dialog .metadata-tag-fields summary,
.metadata-dialog .metadata-batch-details summary {
  cursor: pointer;
  padding: 8px 0;
  font-size: 13px;
}
.metadata-dialog .metadata-tag-fields > .metadata-filter {
  margin-top: 12px;
}
.metadata-dialog .metadata-review-actions {
  margin: 16px 0;
}
.metadata-dialog .metadata-filter,
.metadata-dialog .metadata-current-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
  align-items: start;
}
.metadata-dialog .metadata-wide {
  grid-column: 1/-1;
}
.metadata-dialog .metadata-selected label {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}
.metadata-dialog small {
  display: block;
  color: var(--atlas-muted);
  font-size: 12px;
  margin-top: 5px;
  line-height: 1.6;
}
.metadata-dialog .metadata-selected {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
  margin: 16px 0;
}
.metadata-dialog .metadata-selected img,
.metadata-dialog .metadata-selected .mw-image-empty {
  width: 42px;
  height: 63px;
  object-fit: cover;
  border-radius: 4px;
}
.metadata-dialog .metadata-check {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 20px 0;
  line-height: 1.7;
}
.metadata-dialog .metadata-review {
  border-top: 1px solid var(--atlas-line);
  margin-top: 22px;
  padding-top: 16px;
}
.metadata-dialog .metadata-id {
  color: var(--atlas-muted);
  overflow-wrap: anywhere;
  display: block;
}
.metadata-dialog .metadata-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 28px;
  margin: 20px 0;
}
.metadata-dialog .metadata-counts div {
  min-width: 72px;
}
.metadata-dialog .metadata-counts dt {
  color: var(--atlas-muted);
  font-size: 12px;
}
.metadata-dialog .metadata-counts dd {
  margin: 6px 0 0;
  font-size: 21px;
}
.metadata-dialog .metadata-value {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-results article {
  display: flex;
  align-items: center;
  gap: 12px;
  border-top: 1px solid var(--atlas-line);
  padding: 14px 0;
}
.metadata-dialog .metadata-results article > :first-child {
  width: 52px;
  height: 78px;
  object-fit: cover;
  flex: none;
  border-radius: 4px;
}
.metadata-dialog .metadata-results article > div {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-new,
.metadata-dialog .metadata-restore {
  margin-top: 20px;
}
.metadata-dialog .metadata-review > button {
  margin: 4px 8px 4px 0;
}
.metadata-dialog .metadata-atomic-change {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  align-items: start;
  gap: 14px;
  padding: 16px 0;
  border-top: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-atomic-change > :first-child {
  width: 40px;
  height: 60px;
  object-fit: cover;
  border-radius: var(--atlas-radius-cover);
}
.metadata-dialog .metadata-atomic-change h4 {
  margin: 0 0 10px;
  font-size: 14px;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-atomic-change dl {
  margin: 0;
  display: grid;
  grid-template-columns: 50px minmax(0, 1fr);
  gap: 10px;
  font-size: 13px;
}
.metadata-dialog .metadata-atomic-change dd {
  margin: 0;
  display: grid;
  gap: 4px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-before {
  color: var(--atlas-muted);
}
.metadata-dialog .metadata-primary-actions {
  margin-top: 24px;
}
.metadata-dialog .metadata-draft-tools {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-draft-tools > summary {
  width: fit-content;
  cursor: pointer;
  font-size: 13px;
  color: var(--atlas-muted);
}
.metadata-dialog .metadata-draft-tools p {
  font-size: 13px;
  line-height: 1.7;
}
.metadata-dialog .metadata-editor-summary {
  color: var(--atlas-muted);
  font-size: 13px;
  line-height: 1.7;
}
.metadata-dialog .metadata-workspace {
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr);
  gap: 24px;
  margin-top: 24px;
  align-items: start;
}
.metadata-dialog .metadata-queue {
  max-height: min(480px, 55dvh);
  overflow: auto;
  scrollbar-gutter: stable;
}
.metadata-dialog .metadata-workspace.metadata-single {
  grid-template-columns: minmax(0, 1fr);
}
.metadata-dialog .metadata-queue ol {
  margin: 0;
  padding: 0;
  list-style: none;
}
.metadata-dialog .metadata-queue-item {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: var(--atlas-radius-control);
  background: transparent;
  color: var(--atlas-text);
  text-align: left;
  cursor: pointer;
}
.metadata-dialog .metadata-queue-item:hover {
  background: var(--atlas-hover);
}
.metadata-dialog .metadata-queue-item[aria-current='true'] {
  background: var(--atlas-selected);
  border-color: var(--atlas-control-border-hover);
}
.metadata-dialog .metadata-queue-item > :first-child {
  width: 32px;
  height: 48px;
  object-fit: cover;
  border-radius: var(--atlas-radius-cover);
  flex: none;
}
.metadata-dialog .metadata-queue-item > span {
  min-width: 0;
}
.metadata-dialog .metadata-queue-item strong {
  display: block;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-queue-item small {
  margin-top: 4px;
}
.metadata-dialog .metadata-current {
  min-width: 0;
}
.metadata-dialog .metadata-current-heading {
  display: flex;
  justify-content: space-between;
  align-items: start;
  gap: 16px;
}
.metadata-dialog .metadata-current-heading h3 {
  margin: 0;
  font-size: 16px;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-current-heading > span {
  white-space: nowrap;
  color: var(--atlas-muted);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.metadata-dialog .metadata-current-fields .mw-field {
  margin-bottom: 0;
}
.metadata-dialog .metadata-empty-tags {
  display: flex;
  align-items: center;
  min-height: var(--atlas-control-height);
  color: var(--atlas-muted);
  font-size: 13px;
}
.metadata-dialog .metadata-field-error {
  color: var(--atlas-error);
}
.metadata-dialog .metadata-validation-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin: 16px 0;
  font-size: 13px;
}
.metadata-dialog .metadata-validation-summary p {
  margin: 0;
}
.metadata-dialog .metadata-label-picker > summary {
  cursor: pointer;
  color: var(--atlas-text);
  padding: 12px 0;
  font-size: 13px;
}
.metadata-dialog .metadata-label-picker > div > .atlas-search {
  margin: 4px 0 12px;
}
.metadata-dialog .metadata-label-options {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px 16px;
  max-height: 160px;
  overflow: auto;
  padding: 4px;
}
.metadata-dialog .metadata-label-options label {
  display: flex;
  align-items: start;
  gap: 8px;
  color: var(--atlas-text);
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-book-navigation {
  font-size: 12px;
}
.metadata-dialog .metadata-original {
  margin-top: 16px;
  color: var(--atlas-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-original summary {
  cursor: pointer;
}
@media (max-width: 600px) {
  .metadata-dialog .metadata-filter,
  .metadata-dialog .metadata-current-fields {
    grid-template-columns: 1fr;
  }
  .metadata-dialog .metadata-results article {
    flex-wrap: wrap;
  }
}
</style>
