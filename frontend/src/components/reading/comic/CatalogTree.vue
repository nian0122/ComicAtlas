<template>
  <div class="catalog-tree">
    <CatalogTreeNode
      v-for="node in tree"
      :key="node.id ?? node.title ?? 'root'"
      :node="node"
      :depth="0"
      :active-chapter-id="activeChapterId"
      @select="emit('select', $event)"
    />
  </div>
</template>

<script setup lang="ts">
import { provide, ref } from 'vue'
import type { CatalogNode } from '@/types'
import CatalogTreeNode from './CatalogTreeNode.vue'

const props = defineProps<{
  tree: CatalogNode[]
  activeChapterId?: number | null
}>()

const emit = defineEmits<{
  select: [chapterId: number]
}>()

const expandedIds = ref<Set<string>>(new Set())

provide('expandedIds', expandedIds)
provide('toggleExpanded', (key: string) => {
  if (expandedIds.value.has(key)) expandedIds.value.delete(key)
  else expandedIds.value.add(key)
})
provide('isExpanded', (key: string) => expandedIds.value.has(key))

if (props.tree.length === 1) {
  const only = props.tree[0]
  if (only?.title) expandedIds.value.add(String(only.id ?? only.title))
}
</script>

<style scoped>
.catalog-tree {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>