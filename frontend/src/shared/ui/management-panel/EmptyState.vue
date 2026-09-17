<script setup lang="ts">
withDefaults(defineProps<{ description: string; bordered?: boolean }>(), { bordered: false })
</script>

<template>
  <div class="empty-state" :class="{ 'empty-state--bordered': bordered }" role="status">
    <span v-if="$slots.icon" class="empty-state__icon" aria-hidden="true"><slot name="icon" /></span>
    <p>{{ description }}</p>
    <div v-if="$slots.default" class="empty-state__actions"><slot /></div>
  </div>
</template>

<style scoped>
.empty-state {
  display: grid;
  justify-items: center;
  gap: var(--space-3);
  padding: var(--space-8);
  color: var(--text-muted);
  text-align: center;
  font-size: var(--text-sm);
}
.empty-state p {
  margin: 0;
}
.empty-state--bordered {
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
}
.empty-state__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-3);
}
.empty-state__icon {
  display: inline-flex;
  font-size: var(--text-section);
}
</style>
