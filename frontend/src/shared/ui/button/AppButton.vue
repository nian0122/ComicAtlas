<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'text' | 'overlay'
    size?: 'sm' | 'default' | 'lg'
    loading?: boolean
    disabled?: boolean
    iconOnly?: boolean
    type?: 'button' | 'submit' | 'reset'
  }>(),
  { variant: 'secondary', size: 'default', loading: false, disabled: false, iconOnly: false, type: 'button' },
)
</script>

<template>
  <button
    :type="type"
    class="app-button"
    :class="[`app-button--${variant}`, `app-button--${size}`, { 'app-button--icon-only': iconOnly }]"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
  >
    <span v-if="loading" class="app-button__spinner" aria-hidden="true" />
    <span :class="{ 'app-button__content--loading': loading }"><slot /></span>
  </button>
</template>

<style scoped>
.app-button {
  --button-bg: var(--control-bg);
  --button-border: var(--control-border);
  --button-color: var(--text-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  min-height: var(--button-height-default);
  min-width: var(--control-min-size);
  padding: 0 var(--button-padding-inline);
  color: var(--button-color);
  font: inherit;
  font-size: var(--text-sm);
  font-weight: 650;
  line-height: 1;
  background: var(--button-bg);
  border: 1px solid var(--button-border);
  border-radius: var(--control-radius);
  cursor: pointer;
  transition:
    color var(--transition-fast),
    background var(--transition-fast),
    border-color var(--transition-fast),
    transform var(--transition-fast);
}
.app-button:hover:not(:disabled) {
  background: var(--control-bg-hover);
  border-color: var(--control-border-hover);
}
.app-button:active:not(:disabled) {
  transform: translateY(1px);
}
.app-button:focus-visible {
  outline: 2px solid var(--control-focus-border);
  outline-offset: 2px;
  box-shadow: var(--button-focus-ring);
}
.app-button:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}
.app-button--primary {
  --button-bg: var(--accent);
  --button-border: var(--accent);
  --button-color: var(--color-on-brand);
}
.app-button--primary:hover:not(:disabled) {
  --button-bg: var(--accent-hover);
  --button-border: var(--accent-hover);
}
.app-button--danger {
  --button-bg: var(--danger);
  --button-border: var(--danger);
  --button-color: var(--color-on-brand);
}
.app-button--ghost {
  --button-bg: transparent;
}
.app-button--text {
  min-width: 0;
  padding-inline: var(--space-2);
  --button-bg: transparent;
  --button-border: transparent;
  --button-color: var(--accent);
}
.app-button--overlay {
  --button-bg: rgb(0 0 0 / 70%);
  --button-border: rgb(255 255 255 / 28%);
  --button-color: var(--color-on-brand);
}
.app-button--sm {
  min-height: var(--button-height-sm);
  padding-inline: var(--space-3);
  font-size: var(--text-xs);
}
.app-button--lg {
  min-height: var(--button-height-lg);
  padding-inline: var(--space-5);
}
.app-button--icon-only {
  width: var(--control-min-size);
  padding: 0;
}
.app-button__spinner {
  width: 1em;
  height: 1em;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: app-button-spin 700ms linear infinite;
}
.app-button__content--loading {
  opacity: 0.72;
}
@keyframes app-button-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
