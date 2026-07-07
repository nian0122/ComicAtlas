<template>
  <div
    class="chapter-row"
    :class="{ active: active }"
    :style="{ paddingLeft: (indent ?? 0) + 12 + 'px' }"
    @click="emit('click')"
  >
    <span class="chapter-no">{{ chapter.chapterNo ? `第${chapter.chapterNo}话` : '未知' }}</span>
    <span class="chapter-title">{{ chapter.title || '' }}</span>
    <span class="chapter-pages">{{ chapter.pageCount }}p</span>
  </div>
</template>

<script setup lang="ts">
import type { ChapterRef } from '@/types'

defineProps<{
  chapter: ChapterRef
  active?: boolean
  indent?: number
}>()

const emit = defineEmits<{
  click: []
}>()
</script>

<style scoped>
.chapter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background 150ms ease;
}

.chapter-row:hover {
  background: var(--surface);
}

.chapter-row.active {
  background: var(--accent-bg);
  border-left: 3px solid var(--accent);
}

.chapter-no {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  min-width: 70px;
}

.chapter-title {
  flex: 1;
  font-size: 13px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-pages {
  font-size: 11px;
  color: var(--text-muted);
}
</style>