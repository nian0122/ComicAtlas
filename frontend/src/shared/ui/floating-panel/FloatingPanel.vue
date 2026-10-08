<script setup lang="ts">
withDefaults(defineProps<{ as?: 'section' | 'header' | 'nav' | 'aside' }>(), { as: 'section' })
</script>

<template>
  <component :is="as" class="floating-panel"><slot /></component>
</template>

<style scoped>
.floating-panel {
  min-width: 0;
  padding: var(--space-2);
  color: var(--text-primary);
  background: var(--floating-panel-bg);
  border: 1px solid var(--border);
  border-radius: var(--floating-panel-radius);
  box-shadow: var(--shadow-sm);
}
</style>
