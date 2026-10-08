<script setup lang="ts">
import type { ScanWarningSeverity } from '../model/types'
defineProps<{ severity: ScanWarningSeverity }>()
</script>

<template>
  <span class="warn-chip" :class="`severity-${severity.toLowerCase()}`"><slot /></span>
</template>

<style scoped>
.warn-chip {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  line-height: 1.5;
}
.severity-error {
  color: var(--danger);
  background: var(--danger-bg);
}
.severity-warning {
  color: var(--warning);
  background: var(--warning-bg);
}
.severity-info {
  color: var(--info);
  background: var(--surface-highlight);
}
</style>
