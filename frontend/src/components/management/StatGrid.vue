<script setup lang="ts">
withDefaults(defineProps<{ columns?: 3 | 4 | 5; spaced?: boolean }>(), { columns: 3 })
</script>

<template>
  <section class="stat-grid" :class="[`stat-grid--${columns}`, { 'stat-grid--spaced': spaced }]"><slot /></section>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-4);
  min-width: 0;
}
.stat-grid--4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}
.stat-grid--5 {
  grid-template-columns: repeat(5, minmax(0, 1fr));
}
.stat-grid--spaced {
  margin-bottom: var(--space-6);
}
@media (max-width: 1100px) {
  .stat-grid--5 {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 520px) {
  .stat-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
