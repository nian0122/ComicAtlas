<script setup lang="ts">
withDefaults(
  defineProps<{
    as?: 'section' | 'aside' | 'main'
    flush?: boolean
    padding?: 'compact' | 'default' | 'spacious'
  }>(),
  { as: 'section', padding: 'default' },
)
</script>

<template>
  <component
    :is="as"
    class="management-panel"
    :class="[`management-panel--${padding}`, { 'management-panel--flush': flush }]"
  >
    <slot />
  </component>
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
.management-panel--compact {
  padding: var(--space-4);
}
.management-panel--spacious {
  padding: var(--space-8);
}
.management-panel--flush {
  padding: 0;
  overflow: hidden;
}
</style>
