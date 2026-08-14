<template>
  <button
    class="display-mode-toggle"
    type="button"
    :aria-label="`切换为${targetLabel}`"
    @click="displayModeStore.toggleExplicitMode()"
  >
    <span class="display-mode-toggle__indicator" aria-hidden="true" />
    <span>{{ targetLabel }}</span>
  </button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useDisplayModeStore } from '@/stores/display-mode-store'

const displayModeStore = useDisplayModeStore()
const targetLabel = computed(() => (displayModeStore.isDesktop ? '移动版' : '桌面版'))
</script>

<style scoped>
.display-mode-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  min-height: 44px;
  padding: 0 var(--space-4);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast);
}

.display-mode-toggle:hover,
.display-mode-toggle:active {
  border-color: var(--text-primary);
  background: var(--color-overlay-faint);
  color: var(--text-primary);
}

.display-mode-toggle__indicator {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
}
</style>
