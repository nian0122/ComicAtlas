<script setup lang="ts">
import PanelHeader from './PanelHeader.vue'

defineProps<{ title: string; description?: string; eyebrow?: string; spaced?: boolean }>()
</script>

<template>
  <PanelHeader
    class="management-page-header"
    :class="{ 'management-page-header--spaced': spaced }"
    level="h1"
    :title="title"
    :description="description"
    :eyebrow="eyebrow"
  >
    <template v-if="$slots.default" #default><slot /></template>
    <template v-if="$slots.description" #description><slot name="description" /></template>
  </PanelHeader>
</template>

<style scoped>
.management-page-header {
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--border);
}
.management-page-header--spaced {
  margin-bottom: var(--space-6);
}
</style>
