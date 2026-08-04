<script setup lang="ts">
import { computed } from 'vue'

/**
 * 管理状态标签（T4 DESIGN 原语）：文字标签 + 语义颜色双通道。
 * tone 只决定颜色；状态含义必须由文字独立表达。
 */
type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'

const props = defineProps<{
  label: string
  tone: StatusTone
  ariaLabel?: string
}>()

const toneClass = computed(() => `status-tag--${props.tone}`)
</script>

<template>
  <span
    class="status-tag"
    :class="toneClass"
    role="status"
    :aria-label="ariaLabel ?? label"
    :title="label"
  >{{ label }}</span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  min-height: 18px;
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  font-size: 10px;
  font-weight: 600;
  border: 1px solid transparent;
  white-space: nowrap;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-tag--success {
  color: var(--success);
  background: rgb(102 197 139 / 10%);
  border-color: var(--success);
}

.status-tag--warning {
  color: var(--warning);
  background: rgb(216 165 79 / 10%);
  border-color: var(--warning);
}

.status-tag--danger {
  color: var(--danger);
  background: rgb(240 107 112 / 10%);
  border-color: var(--danger);
}

.status-tag--info {
  color: var(--info);
  background: rgb(112 166 216 / 10%);
  border-color: var(--info);
}

.status-tag--neutral {
  color: var(--text-muted);
  background: var(--bg-primary);
  border-color: var(--border);
}
</style>
