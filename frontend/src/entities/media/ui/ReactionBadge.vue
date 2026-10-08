<script setup lang="ts">
import type { MediaReaction } from '../types'
defineProps<{ reaction: MediaReaction; emphasized?: boolean }>()
</script>

<template>
  <span class="reaction-badge" :class="[reaction.toLowerCase(), { emphasized }]">
    <span class="reaction-dot" aria-hidden="true" />
    {{ reaction === 'LIKE' ? '喜欢' : reaction === 'DISLIKE' ? '不喜欢' : '未标记' }}
  </span>
</template>

<style scoped>
.reaction-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
}
.reaction-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-muted);
}
.like .reaction-dot {
  background: var(--accent);
}
.dislike .reaction-dot {
  background: var(--danger);
}
.emphasized {
  gap: 7px;
  font-weight: 700;
}
.emphasized.like {
  color: var(--color-brand);
}
.emphasized.like .reaction-dot {
  box-shadow: 0 0 0 4px var(--color-brand-soft);
}
.emphasized.dislike {
  color: var(--text-secondary);
}
.emphasized.dislike .reaction-dot {
  background: var(--text-muted);
  box-shadow: 0 0 0 4px var(--color-border-faint);
}
</style>
