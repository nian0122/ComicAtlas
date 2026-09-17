<script setup lang="ts">
withDefaults(defineProps<{ state: 'loading' | 'error' | 'empty'; message?: string; bordered?: boolean }>(), {
  message: undefined,
  bordered: false,
})
</script>

<template>
  <section
    class="content-state"
    :class="[`content-state--${state}`, { 'content-state--bordered': bordered }]"
    :aria-busy="state === 'loading' || undefined"
    :role="state === 'error' ? 'alert' : 'status'"
  >
    <span v-if="state === 'loading'" class="content-state__spinner" aria-hidden="true" />
    <span v-else-if="$slots.icon" class="content-state__icon" aria-hidden="true"><slot name="icon" /></span>
    <p v-if="message">{{ message }}</p>
    <div v-if="$slots.default" class="content-state__actions"><slot /></div>
  </section>
</template>

<style scoped>
.content-state {
  display: grid;
  justify-items: center;
  gap: var(--space-3);
  padding: var(--content-state-padding);
  color: var(--text-muted);
  text-align: center;
  font-size: var(--text-sm);
}
.content-state--bordered {
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
}
.content-state p {
  margin: 0;
}
.content-state__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-3);
}
.content-state__spinner {
  width: var(--content-state-spinner-size);
  height: var(--content-state-spinner-size);
  border: 2px solid var(--border-strong);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: content-state-spin 700ms linear infinite;
}
.content-state__icon {
  font-size: var(--text-section);
}
@keyframes content-state-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
