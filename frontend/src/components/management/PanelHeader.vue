<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    description?: string
    eyebrow?: string
    titleId?: string
    level?: 'h1' | 'h2' | 'h3'
  }>(),
  { level: 'h2' },
)
</script>

<template>
  <header class="panel-header" :class="`panel-header--${level}`">
    <span v-if="$slots.leading" class="panel-header__leading"><slot name="leading" /></span>
    <div class="panel-header__text">
      <span v-if="eyebrow" class="panel-header__eyebrow">{{ eyebrow }}</span>
      <component :is="level" :id="titleId" class="panel-header__title">{{ title }}</component>
      <p v-if="description || $slots.description" class="panel-header__description">
        <slot name="description">{{ description }}</slot>
      </p>
    </div>
    <div v-if="$slots.default" class="panel-header__actions"><slot /></div>
  </header>
</template>

<style scoped>
.panel-header {
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: var(--space-4);
  min-width: 0;
}
.panel-header__text {
  flex: 1 1 15rem;
  min-width: 0;
}
.panel-header__title {
  margin: 0;
  color: var(--text-primary);
  font-size: var(--text-lg);
  overflow-wrap: anywhere;
}
.panel-header--h1 .panel-header__title {
  font-size: var(--text-page);
}
.panel-header__eyebrow {
  display: block;
  margin-bottom: var(--space-2);
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 800;
  letter-spacing: 0.12em;
}
.panel-header__description {
  margin: var(--space-1) 0 0;
  color: var(--text-muted);
  font-size: var(--text-sm);
  line-height: 1.5;
}
.panel-header__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  max-width: 100%;
}
.panel-header__leading {
  color: var(--accent);
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}
</style>
