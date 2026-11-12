<script setup lang="ts">
withDefaults(defineProps<{ selected: number; eligible: number; blocked: number; unit?: string }>(), {
  unit: '部',
})
</script>

<template>
  <p class="mw-batch-summary">
    选择 <strong>{{ selected }}</strong> {{ unit }} · 可执行 <strong>{{ eligible }}</strong> {{ unit }} · 被阻止
    <strong>{{ blocked }}</strong> {{ unit }}
  </p>
</template>

<style scoped>
.mw-batch-summary {
  margin: var(--atlas-space-3) 0 var(--atlas-space-2);
  color: var(--atlas-muted);
  font-size: 12px;
  line-height: 1.6;
}
.mw-batch-summary strong {
  color: var(--atlas-text);
  font-size: 15px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
</style>
