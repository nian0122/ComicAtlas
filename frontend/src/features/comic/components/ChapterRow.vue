<template>
  <div
    class="chapter-row"
    :class="{ active: active }"
    :style="{
      paddingLeft: (indent ?? 0) + 12 + 'px',
      '--chapter-guide-left': (indent ?? 0) + 28 + 'px',
    }"
    @click="emit('click')"
  >
    <span class="chapter-no">
      <template v-for="(segment, index) in chapterNumberSegments" :key="`number-${index}`">
        <mark v-if="segment.matched">{{ segment.text }}</mark>
        <template v-else>{{ segment.text }}</template>
      </template>
    </span>
    <span class="chapter-title">
      <template v-for="(segment, index) in titleSegments" :key="`title-${index}`">
        <mark v-if="segment.matched">{{ segment.text }}</mark>
        <template v-else>{{ segment.text }}</template>
      </template>
    </span>
    <span v-if="active" class="chapter-status">上次阅读</span>
    <span class="chapter-pages">{{ chapter.pageCount }}p</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChapterRef } from '@/entities/comic/types'

interface TextSegment {
  text: string
  matched: boolean
}

const props = defineProps<{
  chapter: ChapterRef
  active?: boolean
  indent?: number
  highlightKeyword?: string
}>()

const emit = defineEmits<{
  click: []
}>()

function splitText(text: string): TextSegment[] {
  const keyword = props.highlightKeyword?.trim()
  if (!keyword || !text) return [{ text, matched: false }]
  const segments: TextSegment[] = []
  const normalizedText = text.toLocaleLowerCase()
  const normalizedKeyword = keyword.toLocaleLowerCase()
  let cursor = 0
  let matchIndex = normalizedText.indexOf(normalizedKeyword, cursor)
  while (matchIndex >= 0) {
    if (matchIndex > cursor) segments.push({ text: text.slice(cursor, matchIndex), matched: false })
    segments.push({ text: text.slice(matchIndex, matchIndex + keyword.length), matched: true })
    cursor = matchIndex + keyword.length
    matchIndex = normalizedText.indexOf(normalizedKeyword, cursor)
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor), matched: false })
  return segments
}

const chapterNumberSegments = computed(() => splitText(props.chapter.chapterNo ? `第${props.chapter.chapterNo}话` : '未知'))
const titleSegments = computed(() => splitText(props.chapter.title || ''))
</script>

<style scoped>
.chapter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  cursor: pointer;
  border-radius: 0;
  transition: background 150ms ease, color 150ms ease;
}

.chapter-row:hover {
  background: var(--bg-surface);
}

.chapter-row.active {
  background: var(--accent-bg);
  border-left: 3px solid var(--accent);
}

.chapter-row.active .chapter-title {
  color: var(--text-primary);
}

.chapter-no {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  min-width: 66px;
}

.chapter-title {
  flex: 1;
  font-size: 13px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

mark {
  padding: 0 1px;
  border-radius: 2px;
  background: var(--accent-bg);
  color: var(--accent);
}

.chapter-pages {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--text-muted);
}

.chapter-status {
  font-size: 11px;
  font-weight: 600;
  color: var(--accent);
  padding: 2px 8px;
  background: var(--accent-bg);
  border-radius: var(--radius-sm);
}

@media (max-width: 1024px) {
  .chapter-row {
    gap: 6px;
    padding-right: 8px;
  }

  .chapter-no {
    flex: 0 0 54px;
    min-width: 54px;
    font-size: 12px;
  }

  .chapter-title {
    min-width: 0;
    font-size: 12px;
  }

  .chapter-status {
    display: none;
  }

  .chapter-pages {
    flex: 0 0 34px;
    text-align: right;
  }
}
</style>
