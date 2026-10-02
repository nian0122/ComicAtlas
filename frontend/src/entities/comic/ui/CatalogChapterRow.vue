<script setup lang="ts">
import type { ChapterRef } from '../model/types'
import ChapterRow from './ChapterRow.vue'
defineProps<{ chapter: ChapterRef; active?: boolean; indent?: number; highlightKeyword?: string }>()
defineEmits<{ click: [] }>()
</script>
<template>
  <ChapterRow
    :chapter="chapter"
    :active="active"
    :indent="indent"
    :highlight-keyword="highlightKeyword"
    appearance="catalog"
    @click="$emit('click')"
  />
</template>
