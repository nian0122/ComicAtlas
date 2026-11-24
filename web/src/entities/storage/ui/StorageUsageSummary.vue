<script setup lang="ts">
import { computed } from 'vue'
import { ManagementIcon, formatBytes, taskStatusLabels, taskTypeLabel } from '@/shared/ui/management'
import { describeHqState, describeLqState } from '../model/status'
import type { StorageObject } from '../model/types'

const props = withDefaults(defineProps<{ object: StorageObject; hideCovers?: boolean }>(), { hideCovers: false })
const parts = computed(() => {
  const { hq, lq, covers, currentLqImages, images } = props.object.inventory
  return [
    { key: 'hq', name: '原件', bytes: hq.bytes, detail: hq.files ? `${hq.files} 个文件` : '未登记' },
    {
      key: 'lq',
      name: '轻量副本',
      bytes: lq.bytes,
      detail: images ? `${currentLqImages}/${images} 张` : '无图片',
    },
    { key: 'covers', name: '封面', bytes: covers.bytes, detail: covers.files ? `${covers.files} 个文件` : '未登记' },
  ].filter((part) => part.key !== 'covers' || (!props.hideCovers && (covers.files > 0 || covers.bytes > 0)))
})
/** 总量由明细求和，避免后端总量与可见构成不一致时出现两套数字。 */
const totalBytes = computed(() => parts.value.reduce((sum, part) => sum + part.bytes, 0))
const visibleParts = computed(() => parts.value.filter((part) => part.bytes > 0))
const states = computed(() => [
  { key: 'hq', ...describeHqState(props.object.inventory, props.object.hqStatus) },
  { key: 'lq', ...describeLqState(props.object.inventory, props.object.lqStatus) },
])
const facts = computed(() => {
  const { availableBytes, availableImages, missingLq, releasingImages } = props.object.inventory
  return [
    availableBytes
      ? {
          key: 'release',
          label: '登记可释放',
          value: availableImages
            ? `${availableImages} 张 · ${formatBytes(availableBytes)}`
            : formatBytes(availableBytes),
          tone: 'muted',
        }
      : null,
    missingLq ? { key: 'missing', label: '缺副本', value: `${missingLq} 张`, tone: 'warn' } : null,
    releasingImages ? { key: 'releasing', label: '释放中', value: `${releasingImages} 张`, tone: 'warn' } : null,
  ].filter((fact) => fact !== null)
})
const barLabel = computed(
  () => visibleParts.value.map((part) => `${part.name} ${formatBytes(part.bytes)}`).join('，') || '尚未登记媒体文件',
)
function isRunning(status: string) {
  return status === 'running' || status === 'cancelling'
}
function statusLabel(status: string) {
  return taskStatusLabels[status as keyof typeof taskStatusLabels] ?? status
}
</script>

<template>
  <div class="mw-usage">
    <div class="mw-usage-states">
      <span
        v-for="state in states"
        :key="state.key"
        class="mw-usage-state"
        :class="`is-${state.tone}`"
        :title="state.detail"
        :aria-label="`${state.label}：${state.detail}`"
        ><i aria-hidden="true" />{{ state.label }}</span
      >
    </div>
    <div class="mw-usage-meter">
      <div class="mw-usage-total">
        <span>登记占用</span><strong>{{ formatBytes(totalBytes) }}</strong>
      </div>
      <div v-if="visibleParts.length" class="mw-usage-bar" role="img" :aria-label="`登记占用构成：${barLabel}`">
        <i
          v-for="part in visibleParts"
          :key="part.key"
          :class="`is-${part.key}`"
          :style="{ flexGrow: part.bytes }"
          aria-hidden="true"
        />
      </div>
      <p v-else class="mw-usage-empty">尚未登记媒体文件</p>
      <dl class="mw-usage-parts">
        <div v-for="part in parts" :key="part.key">
          <dt><i :class="`is-${part.key}`" aria-hidden="true" />{{ part.name }}</dt>
          <dd>
            <strong>{{ formatBytes(part.bytes) }}</strong
            ><small>{{ part.detail }}</small>
          </dd>
        </div>
      </dl>
    </div>
    <div v-if="facts.length" class="mw-usage-facts">
      <span v-for="fact in facts" :key="fact.key" :class="`is-${fact.tone}`"
        ><span>{{ fact.label }}</span
        ><strong>{{ fact.value }}</strong></span
      >
    </div>
    <div v-if="object.activeTasks.length" class="mw-usage-tasks">
      <p
        v-for="task in object.activeTasks"
        :key="task.id"
        :class="{ 'is-attention': task.status === 'failed' || task.status === 'cancelling' }"
      >
        <ManagementIcon
          :name="isRunning(task.status) ? 'loading' : 'task'"
          :size="13"
          :class="{ 'mw-spin': isRunning(task.status) }"
        />
        <span>{{ taskTypeLabel(task.type) }}</span>
        <small>{{ statusLabel(task.status) }}</small>
      </p>
    </div>
  </div>
</template>

<style scoped>
.mw-usage {
  display: grid;
  gap: 12px;
}
.mw-usage-states {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.mw-usage-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 9px;
  border: 1px solid var(--atlas-line);
  border-radius: 999px;
  background: var(--atlas-raised);
  color: var(--atlas-muted);
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
}
.mw-usage-state i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.mw-usage-state.is-good {
  border-color: color-mix(in srgb, var(--atlas-success) 30%, var(--atlas-line));
  background: var(--atlas-success-surface);
  color: var(--atlas-success);
}
.mw-usage-state.is-warn {
  border-color: color-mix(in srgb, var(--atlas-warning) 30%, var(--atlas-line));
  background: color-mix(in srgb, var(--atlas-warning) 9%, transparent);
  color: var(--atlas-warning);
}
.mw-usage-meter {
  display: grid;
  gap: 8px;
}
.mw-usage-total {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.mw-usage-total span {
  color: var(--atlas-muted);
  font-size: 11px;
}
.mw-usage-total strong {
  color: var(--atlas-text);
  font-size: 19px;
  font-weight: 500;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
}
.mw-usage-bar {
  display: flex;
  gap: 2px;
  height: 6px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--atlas-raised);
}
.mw-usage-bar i {
  min-width: 2px;
  border-radius: 999px;
}
.mw-usage-parts {
  display: grid;
  gap: 5px;
  margin: 0;
}
.mw-usage-parts > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: baseline;
  gap: 8px;
}
.mw-usage-parts dt {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--atlas-muted);
  font-size: 11px;
}
.mw-usage-parts dd {
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 7px;
  margin: 0;
  color: var(--atlas-muted);
  font-variant-numeric: tabular-nums;
}
.mw-usage-parts dd strong {
  color: var(--atlas-text);
  font-size: 12px;
  font-weight: 500;
}
.mw-usage-parts dd small {
  font-size: 11px;
}
.mw-usage-parts i,
.mw-usage-facts i {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  flex-shrink: 0;
}
.mw-usage i.is-hq {
  background: var(--atlas-text);
}
.mw-usage i.is-lq {
  background: var(--atlas-muted);
}
.mw-usage i.is-covers {
  background: #7d6a58;
}
.mw-usage-empty {
  margin: 0;
  color: var(--atlas-faint);
  font-size: 11px;
}
.mw-usage-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  padding-top: 10px;
  border-top: 1px solid var(--atlas-line);
  color: var(--atlas-muted);
  font-size: 11px;
}
.mw-usage-facts span {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
}
.mw-usage-facts strong {
  color: var(--atlas-text);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.mw-usage-facts .is-warn strong {
  color: var(--atlas-warning);
}
.mw-usage-tasks {
  display: grid;
  gap: 6px;
  padding-top: 10px;
  border-top: 1px solid var(--atlas-line);
}
.mw-usage-tasks p {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: var(--atlas-text);
  font-size: 12px;
}
.mw-usage-tasks p.is-attention {
  color: var(--atlas-warning);
}
.mw-usage-tasks small {
  margin-left: auto;
  color: var(--atlas-muted);
  font-size: 11px;
}
</style>
