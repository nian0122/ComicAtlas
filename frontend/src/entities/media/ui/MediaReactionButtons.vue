<template>
  <div class="reaction-buttons" :class="{ compact }" role="group" aria-label="内容标记">
    <button type="button" :class="{ active: reaction === 'LIKE' }" aria-label="喜欢" @click="toggle('LIKE')">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M12 20.2 4.7 13a4.8 4.8 0 0 1 6.8-6.8L12 6.7l.5-.5A4.8 4.8 0 0 1 19.3 13L12 20.2Z" />
      </svg>
    </button>
    <button type="button" :class="{ active: reaction === 'DISLIKE' }" aria-label="不喜欢" @click="toggle('DISLIKE')">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path
          d="M7 14V4H4a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h3Zm0-10h8.7a2.4 2.4 0 0 1 2.3 1.8l1.6 6.1a1.7 1.7 0 0 1-1.6 2.1H14l.8 3.8a2.3 2.3 0 0 1-2.2 2.8L7 14V4Z"
        />
      </svg>
    </button>
  </div>
</template>
<script setup lang="ts">
import type { MediaReaction } from '../types'
withDefaults(defineProps<{ reaction: MediaReaction; compact?: boolean }>(), { compact: false })
const emit = defineEmits<{ toggle: [reaction: MediaReaction] }>()
function toggle(next: Exclude<MediaReaction, 'NONE'>) {
  emit('toggle', next)
}
</script>
<style scoped>
.reaction-buttons {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}
.reaction-buttons button {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
  color: var(--text-secondary);
  cursor: pointer;
  transition:
    transform var(--transition-fast),
    color var(--transition-fast),
    background var(--transition-fast),
    border-color var(--transition-fast);
}
.reaction-buttons button:hover {
  border-color: var(--accent);
  color: var(--accent);
  transform: translateY(-1px);
}
.reaction-buttons button.active {
  border-color: var(--accent);
  background: var(--accent-bg);
  color: var(--accent);
}
.reaction-buttons button:last-child.active {
  border-color: var(--text-muted);
  background: var(--surface-highlight);
  color: var(--text-primary);
}
.reaction-buttons svg {
  width: 19px;
  height: 19px;
  fill: currentColor;
}
.reaction-buttons.compact button {
  width: 32px;
  height: 32px;
}
.reaction-buttons.compact svg {
  width: 16px;
  height: 16px;
}
</style>
