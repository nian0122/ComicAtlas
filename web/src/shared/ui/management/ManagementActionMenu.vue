<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import ManagementIcon from './ManagementIcon.vue'
const props = withDefaults(defineProps<{ label?: string; ariaLabel?: string }>(), {
  label: '更多维护',
  ariaLabel: '',
})
const root = ref<HTMLDetailsElement>()
function close(restore = false) {
  if (!root.value?.open) return
  root.value.open = false
  if (restore) root.value.querySelector('summary')?.focus({ preventScroll: true })
}
function outside(event: PointerEvent) {
  if (!root.value?.contains(event.target as Node)) close()
}
function action(event: MouseEvent) {
  const target = event.target as Element
  if (target.closest('[data-menu-keep-open]')) return
  if (target.closest('button,a')) close(true)
}
function escape(event: KeyboardEvent) {
  if (event.key === 'Escape' && root.value?.open) {
    event.preventDefault()
    event.stopPropagation()
    close(true)
  }
}
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>
<template>
  <details ref="root" class="mw-action-menu" @keydown="escape">
    <summary class="mw-button" :aria-label="props.ariaLabel || undefined">
      {{ props.label }}<ManagementIcon name="down" :size="14" />
    </summary>
    <div class="mw-action-menu-content atlas-menu" @click.capture="action"><slot /></div>
  </details>
</template>
<style scoped>
.mw-action-menu {
  position: relative;
}
summary {
  list-style: none;
  display: inline-flex;
  align-items: center;
  gap: 12px;
}
summary::-webkit-details-marker {
  display: none;
}
.mw-action-menu-content {
  position: absolute;
  right: 0;
  top: calc(100% + 8px);
  z-index: 30;
  width: min(280px, calc(100vw - 48px));
}

.mw-action-menu-content :deep(p) {
  font-size: 11px;
  margin: 4px 8px 10px;
}
.mw-action-menu-content :deep(hr) {
  width: 100%;
  border: 0;
  border-top: 1px solid var(--atlas-line);
}
@media (max-width: 760px) {
  .mw-action-menu-content {
    position: fixed;
    left: 16px;
    right: 16px;
    top: auto;
    bottom: 16px;
    width: auto;
    max-height: 70dvh;
    overflow-y: auto;
  }
}
</style>
