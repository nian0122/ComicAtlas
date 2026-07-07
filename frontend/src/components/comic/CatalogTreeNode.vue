<template>
  <div class="catalog-node">
    <!-- 折叠标题行 -->
    <div
      v-if="node.title"
      class="node-header"
      :style="{ paddingLeft: depth * 16 + 12 + 'px' }"
    >
      <button
        class="expand-btn"
        :class="{ expanded: isNodeExpanded }"
        @click="toggle"
      >
        <el-icon :size="12"><ArrowRight /></el-icon>
      </button>
      <span class="node-title">{{ node.title }}</span>
      <span v-if="node.chapters?.length" class="node-count">
        {{ node.chapters.length }} 话
      </span>
    </div>

    <!-- 展开后的内容 -->
    <div v-if="isNodeExpanded || !node.title" class="node-body">
      <ChapterRow
        v-for="ch in node.chapters"
        :key="ch.id"
        :chapter="ch"
        :active="ch.id === activeChapterId"
        :indent="(node.title ? depth + 1 : depth) * 16"
        @click="emit('select', ch.id)"
      />
      <CatalogTreeNode
        v-for="child in node.children"
        :key="child.id ?? child.title ?? `${depth + 1}`"
        :node="child"
        :depth="depth + 1"
        :active-chapter-id="activeChapterId"
        @select="emit('select', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, onMounted } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import type { CatalogNode } from '@/types'
import ChapterRow from './ChapterRow.vue'

defineOptions({
  name: 'CatalogTreeNode',
})

const props = defineProps<{
  node: CatalogNode
  depth: number
  activeChapterId?: number | null
}>()

const emit = defineEmits<{
  select: [chapterId: number]
}>()

const expandedIds = inject<Set<string>>('expandedIds', new Set())
const toggleExpanded = inject<(key: string) => void>('toggleExpanded', () => {})
const isExpanded = inject<(key: string) => boolean>('isExpanded', () => true)

const nodeKey = computed(() => String(props.node.id ?? props.node.title ?? 'root'))
const isNodeExpanded = computed(() => isExpanded(nodeKey.value))

function toggle() {
  toggleExpanded(nodeKey.value)
}

onMounted(() => {
  if (props.depth === 0 && props.node.title) {
    expandedIds.add(nodeKey.value)
  }
})
</script>

<style scoped>
.catalog-node {
  margin-bottom: 1px;
}

.node-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--surface);
  border-radius: var(--radius-sm);
  cursor: default;
  user-select: none;
}

.expand-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  background: transparent;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  transition: transform 150ms ease;
}

.expand-btn.expanded {
  transform: rotate(90deg);
}

.node-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-h);
}

.node-count {
  font-size: 12px;
  color: var(--text-muted);
}

.node-body {
  margin-top: 2px;
}
</style>