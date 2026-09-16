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

const chapterNumberSegments = computed(() =>
  splitText(props.chapter.chapterNo ? `第${props.chapter.chapterNo}话` : '未知'),
)
const titleSegments = computed(() => splitText(props.chapter.title || ''))
</script>

<style scoped>
.chapter-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  box-sizing: border-box;
  padding: 0 12px;
  border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent);
  border-radius: 0;
  cursor: pointer;
  transition:
    background 150ms ease,
    color 150ms ease;
}

.chapter-row::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: var(--chapter-guide-left, 28px);
  width: 1px;
  background: color-mix(in srgb, var(--border) 72%, transparent);
  content: '';
}

.chapter-row:hover {
  background: color-mix(in srgb, var(--bg-surface) 72%, transparent);
}

.chapter-row.active {
  border-left: 0;
  background: var(--accent-bg);
  box-shadow: inset 3px 0 var(--accent);
}

.chapter-row.active .chapter-title {
  color: var(--text-primary);
}

.chapter-no {
  min-width: 66px;
  color: var(--accent);
  font-size: 13px;
  font-weight: 600;
}

.chapter-title {
  flex: 1;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 13px;
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
  color: var(--text-muted);
  font-size: 11px;
}

.chapter-status {
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  background: var(--accent-bg);
  color: var(--accent);
  font-size: 11px;
  font-weight: 600;
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

@media (max-width: 480px) {
  .chapter-row {
    gap: 4px;
    height: 38px;
    padding-right: 6px;
  }

  .chapter-no {
    flex: 0 0 48px;
    min-width: 48px;
  }

  .chapter-pages {
    flex-basis: 30px;
  }
}
</style>
