<script setup lang="ts">
defineProps<{ title: string; description?: string; eyebrow?: string; spaced?: boolean }>()
</script>

<template>
  <header class="page-header" :class="{ 'page-header--spaced': spaced }">
    <div class="page-header__copy">
      <p v-if="eyebrow" class="page-header__eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p v-if="description" class="page-header__description">
        <slot name="description">{{ description }}</slot>
      </p>
    </div>
    <div v-if="$slots.default" class="page-header__actions"><slot /></div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--page-header-gap);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--border);
}
.page-header--spaced {
  margin-bottom: var(--space-6);
}
.page-header__copy {
  min-width: 0;
}
.page-header__eyebrow {
  margin: 0 0 var(--space-2);
  color: var(--accent);
  font-size: var(--text-xs);
  letter-spacing: var(--tracking-kicker);
}
.page-header h1 {
  margin: 0;
  color: var(--text-primary);
  font-family: var(--heading);
  font-size: var(--text-page);
  line-height: 1.05;
}
.page-header__description {
  margin: var(--space-2) 0 0;
  color: var(--text-muted);
  font-size: var(--text-sm);
}
.page-header__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--page-header-action-gap);
}
@media (max-width: 640px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-4);
  }
  .page-header__actions {
    justify-content: flex-start;
    width: 100%;
  }
}
</style>
