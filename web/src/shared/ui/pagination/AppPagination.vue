<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(
  defineProps<{
    page: number
    pages: number
    disabled?: boolean
    label?: string
  }>(),
  { disabled: false, label: '分页' },
)
const emit = defineEmits<{ change: [page: number] }>()
const current = computed(() => Math.min(Math.max(1, props.page), Math.max(1, props.pages)))
const items = computed<(number | 'gap')[]>(() => {
  const last = Math.max(1, props.pages)
  const values = new Set<number>()
  for (const value of [1, last, current.value - 1, current.value, current.value + 1]) {
    if (value >= 1 && value <= last) values.add(value)
  }
  const sorted = [...values].sort((a, b) => a - b)
  const result: (number | 'gap')[] = []
  sorted.forEach((value, index) => {
    if (index > 0 && value - sorted[index - 1]! > 1) result.push('gap')
    result.push(value)
  })
  return result
})
function go(page: number) {
  if (props.disabled) return
  if (page < 1 || page > props.pages || page === current.value) return
  emit('change', page)
}
</script>
<template>
  <nav class="app-pagination" :aria-label="label">
    <button type="button" class="app-pagination-step" :disabled="disabled || current <= 1" @click="go(current - 1)">
      上一页
    </button>
    <template v-for="(item, index) in items" :key="`${item}-${index}`">
      <span v-if="item === 'gap'" class="app-pagination-gap" aria-hidden="true">…</span>
      <button
        v-else
        type="button"
        class="app-pagination-page"
        :class="{ 'is-current': item === current }"
        :aria-label="`第 ${item} 页`"
        :aria-current="item === current ? 'page' : undefined"
        :disabled="disabled"
        @click="go(item)"
      >
        {{ item }}
      </button>
    </template>
    <button type="button" class="app-pagination-step" :disabled="disabled || current >= pages" @click="go(current + 1)">
      下一页
    </button>
  </nav>
</template>
<style scoped>
.app-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--atlas-muted);
}
.app-pagination button {
  min-width: 36px;
  height: 36px;
  padding: 0 8px;
  color: var(--atlas-muted);
  background: none;
  border: 0;
  font: inherit;
  font-size: 11px;
  letter-spacing: 0.06em;
  cursor: pointer;
}
.app-pagination button:hover:not(:disabled) {
  color: var(--atlas-text);
}
.app-pagination button:disabled {
  cursor: default;
  opacity: 0.35;
}
.app-pagination-page.is-current {
  color: var(--atlas-text);
  font-weight: 600;
}
.app-pagination-page.is-current::after {
  display: block;
  width: 10px;
  height: 1px;
  margin: 3px auto 0;
  content: '';
  background: var(--atlas-text);
}
.app-pagination-gap {
  min-width: 14px;
  color: var(--atlas-faint);
  font-size: 11px;
  text-align: center;
}
.app-pagination-step {
  letter-spacing: 0.08em;
}
@media (max-width: 899px) {
  .app-pagination {
    gap: 0;
  }

  .app-pagination button {
    min-width: 26px;
    height: 32px;
    padding: 0 4px;
    font-size: 10px;
  }

  .app-pagination-gap {
    min-width: 8px;
    font-size: 10px;
  }
}
</style>
