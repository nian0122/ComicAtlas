<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Task } from '@/entities/task'
import { AppDialog } from '@/shared/ui/dialog'
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
  <AppDialog
    :open="true"
    title="批量编辑漫画资料"
    :busy="state.busy"
    :placement="mode === 'atomic' ? 'center' : 'right'"
    :size="mode === 'atomic' && (reviewing || targets.length === 1) ? 'form' : 'wide'"
    class="metadata-dialog"
    @close="leave('close')"
  >
    <div v-if="canEditIndividually && !reviewing" class="metadata-mode">
      <span class="metadata-mode-label">编辑方式</span>
      <div class="metadata-mode-options" role="group" aria-label="批量资料编辑方式">
        <button
          type="button"
          class="mw-button"
          :aria-pressed="mode === 'patch'"
          :disabled="state.busy"
          @click="mode !== 'patch' && leave('patch')"
        >
          统一修改</button
        ><button
          type="button"
          class="mw-button"
          :aria-pressed="mode === 'atomic'"
          :disabled="state.busy"
          @click="mode !== 'atomic' && leave('atomic')"
        >
          逐本编辑
        </button>
      </div>
      <p class="metadata-mode-note">
        {{
          mode === 'patch'
            ? '同一组字段应用到范围内全部作品，先预览再执行。'
            : '为每部作品分别填写资料，核对后整批保存。'
        }}
      </p>
    </div>
    <div v-if="pending" class="metadata-pending" role="alert">
      <p>仍有未保存的编辑或未确认的预览，继续会放弃本面板草稿。</p>
      <div class="mw-inline-actions">
        <button class="mw-button" @click="apply(pending as 'close' | 'patch' | 'atomic')">确认放弃并继续</button
        ><button class="mw-button" @click="pending = ''">继续编辑</button>
      </div>
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
  </AppDialog>
</template>
<style>
/* 资料弹窗把标题、编辑方式、步骤与主操作固定在视口内，只让当前阶段滚动。 */
.metadata-dialog {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
.metadata-dialog > header {
  flex: 0 0 auto;
  margin: 0;
  padding: 22px 26px 14px;
}
.metadata-dialog > .metadata-mode,
.metadata-dialog > .metadata-pending {
  flex: 0 0 auto;
  margin: 0 26px 14px;
}
.metadata-dialog > .metadata-batch,
.metadata-dialog > .metadata-atomic {
  flex: 1 1 auto;
  min-height: 0;
}
.metadata-dialog .metadata-mode {
  display: grid;
  gap: 8px;
}
.metadata-dialog .metadata-mode-label {
  color: var(--atlas-muted);
  font-size: 11.5px;
}
.metadata-dialog .metadata-mode-options {
  display: flex;
  gap: 6px;
}
.metadata-dialog .metadata-mode-options .mw-button {
  min-height: 36px;
  padding: 7px 16px;
}
.metadata-dialog .metadata-mode-options [aria-pressed='true'] {
  border-color: var(--atlas-accent);
  background: var(--atlas-accent-soft);
  color: var(--atlas-text);
}
.metadata-dialog .metadata-mode-note {
  margin: 0;
  color: var(--atlas-muted);
  font-size: 12px;
  line-height: 1.6;
}
.metadata-dialog .metadata-pending {
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, var(--atlas-warning) 32%, var(--atlas-line));
  border-radius: 12px;
  background: color-mix(in srgb, var(--atlas-warning) 8%, var(--atlas-raised));
}
.metadata-dialog .metadata-pending p {
  margin: 0 0 8px;
  color: var(--atlas-warning);
  font-size: 12.5px;
  line-height: 1.6;
}
.metadata-dialog .metadata-pending .mw-inline-actions {
  margin: 0;
}
.metadata-dialog .metadata-batch {
  display: flex;
  flex-direction: column;
}
.metadata-dialog .metadata-flow {
  flex: 0 0 auto;
  padding: 0 26px;
}
.metadata-dialog .metadata-steps {
  display: flex;
  align-items: center;
  margin: 0;
  padding: 2px 0 12px;
  list-style: none;
}
.metadata-dialog .metadata-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--atlas-muted);
  font-size: 12.5px;
  white-space: nowrap;
}
.metadata-dialog .metadata-steps li:not(:last-child)::after {
  content: '';
  flex: 1;
  min-width: 24px;
  height: 1px;
  margin: 0 12px;
  background: var(--atlas-line);
}
.metadata-dialog .metadata-steps li.is-done:not(:last-child)::after {
  background: color-mix(in srgb, var(--atlas-accent) 45%, var(--atlas-line));
}
.metadata-dialog .metadata-step-mark {
  display: grid;
  flex: none;
  place-items: center;
  width: 20px;
  height: 20px;
  border: 1px solid var(--atlas-control-border);
  border-radius: 50%;
  color: var(--atlas-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.metadata-dialog .metadata-steps li.is-done .metadata-step-mark {
  border-color: transparent;
  background: var(--atlas-selected);
  color: var(--atlas-success);
}
.metadata-dialog .metadata-steps li.is-current {
  color: var(--atlas-text);
  font-weight: 600;
}
.metadata-dialog .metadata-steps li.is-current .metadata-step-mark {
  border-color: var(--atlas-accent);
  background: var(--atlas-accent);
  color: var(--atlas-on-accent);
  font-weight: 600;
}
.metadata-dialog .metadata-scope-summary {
  margin: 0 0 14px;
  padding: 10px 12px;
  border: 1px solid var(--atlas-line);
  border-radius: 12px;
  background: var(--atlas-surface);
}
.metadata-dialog .metadata-scope-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  margin: 0;
  font-size: 13px;
}
.metadata-dialog .metadata-scope-label {
  flex: none;
  color: var(--atlas-muted);
  font-size: 11.5px;
  letter-spacing: 0.02em;
}
.metadata-dialog .metadata-scope-line strong {
  overflow: hidden;
  color: var(--atlas-text);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.metadata-dialog .metadata-scope-flag {
  flex: none;
  padding: 1px 7px;
  border: 1px solid color-mix(in srgb, var(--atlas-warning) 32%, var(--atlas-line));
  border-radius: 999px;
  color: var(--atlas-warning);
  font-size: 11px;
  line-height: 1.7;
}
.metadata-dialog .metadata-scope-history {
  margin: 6px 0 0;
  color: var(--atlas-muted);
  font-size: 11.5px;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-scope-conditions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 0;
  margin: 8px 0 0;
  list-style: none;
}
.metadata-dialog .metadata-scope-conditions li {
  padding: 3px 8px;
  border: 1px solid var(--atlas-line);
  border-radius: 999px;
  background: var(--atlas-raised);
  color: var(--atlas-muted);
  font-size: 11.5px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-scope-exclusion {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 0 0;
  color: var(--atlas-muted);
  font-size: 11.5px;
}
.metadata-dialog .metadata-text-button {
  padding: 0;
  border: 0;
  background: none;
  color: var(--atlas-muted);
  font: inherit;
  font-size: 12px;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}
.metadata-dialog .metadata-text-button:hover {
  color: var(--atlas-text);
}
.metadata-dialog .metadata-stage-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 0 26px 20px;
  scrollbar-gutter: stable;
}
.metadata-dialog [data-step]:focus-visible {
  outline: 2px solid var(--atlas-control-focus);
  outline-offset: 2px;
}
.metadata-dialog fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.metadata-dialog legend {
  width: 100%;
  margin: 14px 0 6px;
  padding: 0;
  color: var(--atlas-text);
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.1px;
}
.metadata-dialog .metadata-stage-note {
  max-width: 72ch;
  margin: 0 0 16px;
  color: var(--atlas-muted);
  font-size: 12.5px;
  line-height: 1.65;
}
.metadata-dialog .metadata-entry-hint {
  margin: 16px 0 0;
  color: var(--atlas-muted);
  font-size: 12px;
  line-height: 1.7;
}
.metadata-dialog .metadata-range-mode {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 0 0 14px;
}
.metadata-dialog .metadata-range-option {
  display: grid;
  gap: 3px;
  padding: 11px 13px;
  border: 1px solid var(--atlas-control-border);
  border-radius: 12px;
  background: var(--atlas-surface);
  color: var(--atlas-text);
  text-align: left;
  cursor: pointer;
  transition:
    background-color var(--atlas-control-transition),
    border-color var(--atlas-control-transition);
}
.metadata-dialog .metadata-range-option strong {
  font-size: 13px;
  font-weight: 500;
}
.metadata-dialog .metadata-range-option small {
  margin: 0;
  color: var(--atlas-muted);
  font-size: 11.5px;
}
.metadata-dialog .metadata-range-option:hover:not(:disabled) {
  border-color: var(--atlas-control-border-hover);
  background: var(--atlas-control-fill-hover);
}
.metadata-dialog .metadata-range-option:disabled {
  opacity: var(--atlas-control-disabled-opacity);
  cursor: not-allowed;
}
.metadata-dialog .metadata-range-option[aria-pressed='true'] {
  border-color: var(--atlas-accent);
  background: var(--atlas-accent-soft);
}
.metadata-dialog .metadata-range-option[aria-pressed='true'] small {
  color: color-mix(in srgb, var(--atlas-text) 72%, var(--atlas-accent));
}
.metadata-dialog .metadata-selected {
  max-height: min(360px, 40dvh);
  margin: 0 0 14px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}
.metadata-dialog .metadata-selected-row {
  display: grid;
  gap: 10px;
  align-items: start;
  padding-bottom: 10px;
}
.metadata-dialog .metadata-target {
  display: grid;
  grid-template-columns: auto 42px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 8px;
  border: 1px solid var(--atlas-line);
  border-radius: 12px;
  background: var(--atlas-surface);
  cursor: pointer;
  transition:
    background-color var(--atlas-control-transition),
    border-color var(--atlas-control-transition);
}
.metadata-dialog .metadata-target:hover {
  border-color: var(--atlas-control-border-hover);
}
.metadata-dialog .metadata-target:has(input:focus-visible) {
  outline: 2px solid var(--atlas-control-focus);
  outline-offset: -2px;
}
.metadata-dialog .metadata-target.is-chosen {
  border-color: color-mix(in srgb, var(--atlas-accent) 55%, var(--atlas-line));
  background: var(--atlas-selected);
}
.metadata-dialog .metadata-target img,
.metadata-dialog .metadata-target .mw-image-empty {
  width: 42px;
  height: 63px;
  object-fit: cover;
  border-radius: 4px;
}
.metadata-dialog .metadata-target-title {
  display: -webkit-box;
  overflow: hidden;
  color: var(--atlas-text);
  font-size: 12.5px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-filter-panel {
  margin: 0 0 14px;
  padding: 14px 16px 16px;
  border: 1px solid var(--atlas-line);
  border-radius: 12px;
  background: var(--atlas-surface);
}
.metadata-dialog .metadata-filter-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  color: var(--atlas-muted);
  font-size: 11.5px;
}
.metadata-dialog .metadata-filter {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px 16px;
  align-items: start;
}
.metadata-dialog .metadata-filter .mw-field {
  margin-bottom: 0;
}
.metadata-dialog .metadata-wide {
  grid-column: 1/-1;
}
.metadata-dialog .metadata-restore {
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-restore > summary {
  width: fit-content;
  color: var(--atlas-muted);
  font-size: 12px;
  cursor: pointer;
}
.metadata-dialog .metadata-restore > p {
  max-width: 72ch;
  margin: 10px 0 12px;
  font-size: 12px;
  line-height: 1.7;
}
.metadata-dialog .metadata-restore .mw-button {
  margin: 0 8px 8px 0;
}

/* 字段按“启用 → 填写”排列，关闭的字段只保留启用入口。 */
.metadata-dialog .metadata-field-list {
  border-top: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-field-row {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 12px 20px;
  align-items: center;
  padding: 14px 0;
  border-bottom: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-field-head {
  display: grid;
  gap: 3px;
}
.metadata-dialog .metadata-field-toggle {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--atlas-muted);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.metadata-dialog .metadata-field-row.is-on .metadata-field-toggle,
.metadata-dialog .metadata-field-row:focus-within .metadata-field-toggle {
  color: var(--atlas-text);
}
.metadata-dialog .metadata-field-head small {
  margin: 0;
  color: var(--atlas-muted);
  font-size: 11.5px;
  line-height: 1.5;
}
.metadata-dialog .metadata-field-control {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.metadata-dialog .metadata-field-control > input:not([type='checkbox'], [type='radio']),
.metadata-dialog .metadata-field-control > select,
.metadata-dialog .metadata-field-control > textarea {
  width: 100%;
  max-width: none;
}
.metadata-dialog .metadata-field-hint {
  margin: 0;
  color: var(--atlas-muted);
  font-size: 11.5px;
  line-height: 1.6;
}
.metadata-dialog .metadata-field-hint.is-error {
  color: var(--atlas-error);
}
.metadata-dialog .metadata-choice {
  display: inline-flex;
  gap: 2px;
  width: fit-content;
  padding: 3px;
  border: 1px solid var(--atlas-control-border);
  border-radius: 9px;
  background: var(--atlas-control-fill);
}
.metadata-dialog .metadata-choice label {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 11px;
  border-radius: 7px;
  color: var(--atlas-muted);
  font-size: 12px;
  cursor: pointer;
}
.metadata-dialog .metadata-choice label.is-on {
  background: var(--atlas-hover);
  color: var(--atlas-text);
}
.metadata-dialog .metadata-choice label:has(input:focus-visible) {
  outline: 2px solid var(--atlas-control-focus);
  outline-offset: 1px;
}
.metadata-dialog .metadata-choice input {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: 0;
  opacity: 0;
  pointer-events: none;
}
.metadata-dialog .metadata-tag-fields {
  border-bottom: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-tag-fields > summary {
  padding: 14px 0;
  color: var(--atlas-text);
  font-size: 13px;
  cursor: pointer;
}
.metadata-dialog .metadata-tag-fields > .metadata-filter {
  padding-bottom: 14px;
}
.metadata-dialog .metadata-tag-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.metadata-dialog .metadata-tag-fields .metadata-label-picker > summary {
  padding: 8px 0;
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
.metadata-dialog .metadata-empty-tags {
  display: flex;
  align-items: center;
  min-height: var(--atlas-control-height);
  color: var(--atlas-muted);
  font-size: 13px;
}
.metadata-dialog .metadata-footer {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  padding: 14px 26px 18px;
  border-top: 1px solid var(--atlas-line);
  background: var(--atlas-raised);
}
.metadata-dialog .metadata-footer-bar {
  display: flex;
  align-items: center;
  gap: 16px;
}
.metadata-dialog .metadata-check.metadata-check-footer {
  margin: 0;
  padding: 0;
  border: 0;
  background: none;
  font-size: 12px;
  line-height: 1.5;
}
.metadata-dialog .metadata-footer-note {
  min-width: 0;
  margin: 0;
  color: var(--atlas-muted);
  font-size: 12px;
  line-height: 1.5;
}
.metadata-dialog .metadata-footer-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-left: auto;
}

/* 核对阶段 */
.metadata-dialog .metadata-review-stage {
  display: block;
}
.metadata-dialog .metadata-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin: 14px 0 4px;
}
.metadata-dialog .metadata-heading > div {
  min-width: 0;
}
.metadata-dialog .metadata-heading h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.1px;
}
.metadata-dialog .metadata-heading .metadata-stage-note {
  margin: 6px 0 0;
}
.metadata-dialog .metadata-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 28px;
  margin: 16px 0 18px;
  padding: 12px 0;
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-stats > div {
  display: grid;
  gap: 2px;
  min-width: 60px;
}
.metadata-dialog .metadata-stats dt {
  color: var(--atlas-muted);
  font-size: 11.5px;
}
.metadata-dialog .metadata-stats dd {
  margin: 0;
  color: var(--atlas-text);
  font-size: 16px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.metadata-dialog .metadata-patch {
  margin: 0 0 16px;
}
.metadata-dialog .metadata-patch h4 {
  margin: 0 0 8px;
  color: var(--atlas-muted);
  font-size: 11.5px;
  font-weight: 500;
}
.metadata-dialog .metadata-patch .mw-properties {
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 8px 16px;
  margin: 0;
}
.metadata-dialog .metadata-batch-details summary {
  padding: 8px 0;
  color: var(--atlas-muted);
  font-size: 12px;
  cursor: pointer;
}
.metadata-dialog .metadata-id {
  display: block;
  color: var(--atlas-muted);
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-task {
  margin: 14px 0;
  padding: 10px 12px;
  border: 1px solid var(--atlas-line);
  border-radius: 12px;
  background: var(--atlas-surface);
  font-size: 12.5px;
}
.metadata-dialog .metadata-task > p {
  margin: 0 0 6px;
}
.metadata-dialog .metadata-task .mw-inline-actions {
  margin-top: 8px;
}
.metadata-dialog .metadata-check {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 16px 0;
  padding: 12px 14px;
  border: 1px solid var(--atlas-line);
  border-radius: 12px;
  background: var(--atlas-surface);
  color: var(--atlas-text);
  font-size: 12.5px;
  line-height: 1.65;
}
.metadata-dialog .metadata-check:has(input:disabled) {
  color: var(--atlas-muted);
}
.metadata-dialog .metadata-results {
  margin-top: 6px;
  border-top: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-results article {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 0;
  border-bottom: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-results-empty {
  margin: 16px 0;
  font-size: 12.5px;
}
.metadata-dialog .metadata-results article > :first-child {
  width: 44px;
  height: 66px;
  object-fit: cover;
  flex: none;
  border-radius: 4px;
}
.metadata-dialog .metadata-results article > div {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-result-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
}
.metadata-dialog .metadata-result-title strong {
  color: var(--atlas-text);
  font-size: 13px;
  font-weight: 500;
}
.metadata-dialog .metadata-status {
  padding: 1px 7px;
  border: 1px solid var(--atlas-line);
  border-radius: 999px;
  color: var(--atlas-muted);
  font-size: 11px;
  line-height: 1.7;
  white-space: nowrap;
}
.metadata-dialog .metadata-status.is-succeeded {
  border-color: color-mix(in srgb, var(--atlas-success) 32%, var(--atlas-line));
  color: var(--atlas-success);
}
.metadata-dialog .metadata-status.is-blocked {
  border-color: color-mix(in srgb, var(--atlas-warning) 32%, var(--atlas-line));
  color: var(--atlas-warning);
}
.metadata-dialog .metadata-status.is-failed {
  border-color: color-mix(in srgb, var(--atlas-error) 38%, var(--atlas-line));
  color: var(--atlas-error);
}
.metadata-dialog .metadata-status.is-cancelled {
  color: var(--atlas-faint);
}
.metadata-dialog .metadata-results article small {
  margin-top: 3px;
}
.metadata-dialog .metadata-results .mw-button {
  flex: none;
  min-height: 32px;
  padding: 6px 10px;
  font-size: 12px;
}
.metadata-dialog .metadata-results .mw-error {
  margin: 4px 0 0;
  font-size: 12px;
}
.metadata-dialog .metadata-new {
  margin-top: 20px;
}
.metadata-dialog .metadata-draft-tools,
.metadata-dialog .metadata-atomic-change,
.metadata-dialog .metadata-original,
.metadata-dialog .metadata-primary-actions {
  margin-top: 20px;
}
.metadata-dialog .metadata-draft-tools {
  padding-top: 16px;
  border-top: 1px solid var(--atlas-line);
}
.metadata-dialog .metadata-draft-tools > summary {
  width: fit-content;
  color: var(--atlas-muted);
  font-size: 12px;
  cursor: pointer;
}
.metadata-dialog .metadata-draft-tools p {
  font-size: 12.5px;
  line-height: 1.7;
}
.metadata-dialog .metadata-editor-summary {
  margin: 14px 0 12px;
  color: var(--atlas-muted);
  font-size: 12.5px;
  line-height: 1.7;
}
.metadata-dialog .metadata-validation-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin: 14px 0;
  font-size: 12.5px;
}
.metadata-dialog .metadata-validation-summary p {
  margin: 0;
}
.metadata-dialog .metadata-review {
  margin-top: 18px;
  padding: 12px 14px;
  border: 1px solid var(--atlas-line);
  border-radius: 12px;
  background: var(--atlas-surface);
}
.metadata-dialog .metadata-review p {
  margin: 0 0 8px;
  font-size: 12.5px;
  line-height: 1.65;
}
.metadata-dialog .metadata-review > button {
  margin: 0 8px 8px 0;
}
.metadata-dialog .metadata-workspace {
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr);
  gap: 24px;
  margin-top: 16px;
  align-items: start;
}
.metadata-dialog .metadata-workspace.metadata-single {
  grid-template-columns: minmax(0, 1fr);
}
.metadata-dialog .metadata-queue {
  max-height: min(480px, 55dvh);
  overflow: auto;
  scrollbar-gutter: stable;
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
.metadata-dialog .metadata-current-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
  align-items: start;
  margin: 14px 0;
}
.metadata-dialog .metadata-current-fields .mw-field {
  margin-bottom: 0;
}
.metadata-dialog .metadata-field-error {
  color: var(--atlas-error);
}
.metadata-dialog .metadata-label-picker > summary {
  padding: 8px 0;
  color: var(--atlas-text);
  font-size: 12.5px;
  cursor: pointer;
}
.metadata-dialog .metadata-label-picker > div > .atlas-search {
  margin: 4px 0 12px;
}
.metadata-dialog .metadata-book-navigation {
  font-size: 12px;
}
.metadata-dialog .metadata-original {
  color: var(--atlas-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-original summary {
  cursor: pointer;
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
.metadata-dialog .metadata-atomic {
  overflow-y: auto;
  padding: 0 26px 24px;
  scrollbar-gutter: stable;
}
.metadata-dialog .metadata-atomic-review h3 {
  margin: 14px 0 6px;
  font-size: 16px;
  font-weight: 600;
}
.metadata-dialog .metadata-atomic-review > p {
  margin: 0 0 12px;
}
.metadata-dialog .metadata-value {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.metadata-dialog .metadata-pagination {
  margin-top: 14px;
}
.metadata-dialog .mw-inline-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.metadata-dialog .metadata-primary-actions {
  margin-top: 20px;
}
@media (max-width: 860px) {
  .metadata-dialog .metadata-filter {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metadata-dialog .metadata-field-row {
    grid-template-columns: 180px minmax(0, 1fr);
  }
  .metadata-dialog .metadata-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 620px) {
  .metadata-dialog > header {
    padding: 18px 18px 12px;
  }
  .metadata-dialog > .metadata-mode,
  .metadata-dialog > .metadata-pending {
    margin-right: 18px;
    margin-left: 18px;
  }
  .metadata-dialog .metadata-flow {
    padding: 0 18px;
  }
  .metadata-dialog .metadata-stage-scroll {
    padding: 0 18px 16px;
  }
  .metadata-dialog .metadata-footer {
    padding: 12px 18px 16px;
  }
  .metadata-dialog .metadata-footer-bar {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .metadata-dialog .metadata-scope-line {
    flex-wrap: wrap;
  }
  .metadata-dialog .metadata-scope-line strong {
    overflow: visible;
    white-space: normal;
  }
  .metadata-dialog .metadata-footer-actions {
    margin-left: 0;
  }
  .metadata-dialog .metadata-footer-actions .mw-button {
    flex: 1;
  }
  .metadata-dialog .metadata-steps li:not(:last-child)::after {
    min-width: 12px;
    margin: 0 8px;
  }
  .metadata-dialog .metadata-range-mode,
  .metadata-dialog .metadata-filter,
  .metadata-dialog .metadata-field-row,
  .metadata-dialog .metadata-current-fields {
    grid-template-columns: minmax(0, 1fr);
  }
  .metadata-dialog .metadata-results article {
    flex-wrap: wrap;
  }
  .metadata-dialog .metadata-results .mw-button {
    width: 100%;
  }
  .metadata-dialog .metadata-atomic {
    padding: 0 18px 20px;
  }
}
</style>
