<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string | number
    description?: string
    unit?: string
    tone?: 'neutral' | 'primary' | 'success' | 'warning' | 'danger'
  }>(),
  { description: undefined, unit: undefined, tone: 'neutral' },
)
</script>

<template>
  <article class="stat-card" :class="`stat-card--${tone}`">
    <span v-if="$slots.icon" class="stat-card__icon" aria-hidden="true"><slot name="icon" /></span>
    <span class="stat-card__label">{{ label }}</span>
    <strong class="stat-card__value"
      >{{ value }}<small v-if="unit"> {{ unit }}</small></strong
    >
    <span v-if="description" class="stat-card__description">{{ description }}</span>
  </article>
</template>

<style scoped>
.stat-card {
  --stat-tone: var(--border);
  display: grid;
  align-content: center;
  gap: var(--space-2);
  min-width: 0;
  padding: var(--space-5);
  border: 1px solid var(--border);
  border-top: 2px solid var(--stat-tone);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
}
.stat-card--primary {
  --stat-tone: var(--accent);
}
.stat-card--success {
  --stat-tone: var(--success);
}
.stat-card--warning {
  --stat-tone: var(--warning);
}
.stat-card--danger {
  --stat-tone: var(--danger);
}
.stat-card__label,
.stat-card__description {
  color: var(--text-muted);
  font-size: var(--text-sm);
  overflow-wrap: anywhere;
}
.stat-card__value {
  color: var(--text-primary);
  font-size: var(--text-section);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.stat-card__value small {
  font-size: var(--text-xs);
  font-weight: 400;
  color: var(--text-muted);
}
.stat-card__icon {
  display: inline-flex;
  color: var(--text-secondary);
}
.stat-card--warning .stat-card__icon,
.stat-card--danger .stat-card__icon {
  color: var(--stat-tone);
}
</style>
