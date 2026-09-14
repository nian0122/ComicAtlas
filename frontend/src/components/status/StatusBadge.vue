<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    tone?: 'success' | 'warning' | 'danger' | 'info' | 'primary' | 'neutral'
    code?: string
    appearance?: 'tag' | 'dot'
    size?: 'small' | 'default' | 'large'
  }>(),
  { tone: 'neutral', appearance: 'tag', size: 'default' },
)
</script>

<template>
  <span class="status-badge" :class="[`status-badge--${tone}`, `status-badge--${appearance}`, `status-badge--${size}`]">
    <i v-if="appearance === 'dot'" aria-hidden="true" />
    <span>{{ label }}</span
    ><code v-if="code">{{ code }}</code>
  </span>
</template>

<style scoped>
.status-badge {
  --status-tone: var(--text-muted);
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  max-width: 100%;
  vertical-align: middle;
  font-size: var(--text-xs);
  line-height: 1.5;
}
.status-badge--success {
  --status-tone: var(--success);
}
.status-badge--warning {
  --status-tone: var(--warning);
}
.status-badge--danger {
  --status-tone: var(--danger);
}
.status-badge--primary {
  --status-tone: var(--accent);
}
.status-badge--info {
  --status-tone: var(--text-secondary);
}
.status-badge--tag {
  padding: var(--space-1) var(--space-2);
  color: var(--status-tone);
  background: color-mix(in srgb, var(--status-tone) 12%, var(--bg-surface));
  border: 1px solid color-mix(in srgb, var(--status-tone) 48%, var(--border));
  border-radius: var(--radius-sm);
}
.status-badge--dot {
  color: var(--text-secondary);
}
.status-badge i {
  flex: 0 0 var(--status-dot-size);
  width: var(--status-dot-size);
  height: var(--status-dot-size);
  border-radius: 50%;
  background: var(--status-tone);
}
.status-badge--small {
  font-size: var(--text-xs);
  padding-block: 0;
}
.status-badge--large {
  font-size: var(--text-sm);
}
.status-badge > span {
  overflow-wrap: anywhere;
}
.status-badge code {
  font-size: var(--text-xs);
  opacity: 0.72;
}
</style>
