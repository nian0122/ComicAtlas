<script setup lang="ts">
defineProps<{ flush?: boolean }>()
</script>

<template>
  <section class="management-panel" :class="{ 'management-panel--flush': flush }"><slot /></section>
</template>

<style scoped>
.management-panel {
  min-width: 0;
  padding: var(--space-5);
  border: 1px solid var(--border);
  border-radius: var(--card-radius);
  background: var(--bg-surface);
  box-shadow: var(--shadow-sm);
}
.management-panel--flush {
  padding: 0;
  overflow: hidden;
}
</style>
