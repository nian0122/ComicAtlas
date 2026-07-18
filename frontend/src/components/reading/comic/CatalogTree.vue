<template>
  <div class="catalog-tree">
    <RecycleScroller
      class="catalog-scroller"
      :items="flatItems"
      :item-size="42"
      key-field="flatKey"
      :buffer="100"
    >
      <template #default="{ item }">
        <div
          v-if="item.type === 'header'"
          class="node-header"
          :style="{ paddingLeft: item.depth * 16 + 12 + 'px' }"
          @click="toggleExpanded(item.nodeId)"
        >
          <button
            type="button"
            class="expand-btn"
            :class="{ expanded: isExpanded(item.nodeId) }"
          >
            <el-icon :size="12"><ArrowRight /></el-icon>
          </button>
          <span class="node-title">{{ item.title }}</span>
          <span v-if="item.count > 0" class="node-count">{{ item.count }} 话</span>
        </div>
        <ChapterRow
          v-else
          :chapter="item.chapter"
          :active="item.chapterId === activeChapterId"
          :indent="item.indent"
          @click="emit('select', item.chapterId)"
        />
      </template>
    </RecycleScroller>
  </div>
</template>

<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { RecycleScroller } from 'vue-virtual-scroller'
import { ArrowRight } from '@element-plus/icons-vue'
import type { CatalogNode, ChapterRef } from '@/types'
import ChapterRow from './ChapterRow.vue'

/** 扁平化后的分组标题行 */
interface HeaderFlatItem {
  type: 'header'
  flatKey: string
  nodeId: string
  title: string
  count: number
  depth: number
}

/** 扁平化后的章节行 */
interface ChapterFlatItem {
  type: 'chapter'
  flatKey: string
  chapterId: number
  chapterNo: string
  title: string
  pageCount: number
  status?: string
  chapter: ChapterRef
  indent: number
}

type FlatItem = HeaderFlatItem | ChapterFlatItem

const props = defineProps<{
  tree: CatalogNode[]
  activeChapterId?: number | null
}>()

const emit = defineEmits<{
  select: [chapterId: number]
}>()

const expandedIds = ref<Set<string>>(new Set())

function toggleExpanded(key: string) {
  if (expandedIds.value.has(key)) expandedIds.value.delete(key)
  else expandedIds.value.add(key)
}

function isExpanded(key: string) {
  return expandedIds.value.has(key)
}

provide('expandedIds', expandedIds)
provide('toggleExpanded', toggleExpanded)
provide('isExpanded', isExpanded)

function nodeKeyOf(node: CatalogNode): string {
  return String(node.id ?? node.title ?? 'root')
}

// 默认展开顶层分组（与原 CatalogTreeNode depth=0 onMounted 自动展开行为一致）
watch(
  () => props.tree,
  (tree) => {
    for (const node of tree) {
      if (node.title) expandedIds.value.add(nodeKeyOf(node))
    }
  },
  { immediate: true }
)

/**
 * 递归扁平化目录树：
 * - 有标题的节点输出 header 行，仅在展开时输出其章节与子节点
 * - 无标题的节点不输出 header，章节与子节点始终可见
 * 缩进规则与原 CatalogTreeNode 保持一致。
 */
function walkNodes(nodes: CatalogNode[], depth: number, path: string, out: FlatItem[]) {
  nodes.forEach((node, index) => {
    const nodeKey = nodeKeyOf(node)
    const nodePath = `${path}/${node.id ?? node.title ?? index}`

    if (node.title) {
      out.push({
        type: 'header',
        flatKey: `h:${nodePath}`,
        nodeId: nodeKey,
        title: node.title,
        count: node.chapters?.length ?? 0,
        depth,
      })
    }

    if (!node.title || isExpanded(nodeKey)) {
      const indent = (node.title ? depth + 1 : depth) * 16
      for (const ch of node.chapters ?? []) {
        out.push({
          type: 'chapter',
          flatKey: `c:${ch.id}`,
          chapterId: ch.id,
          chapterNo: ch.chapterNo,
          title: ch.title,
          pageCount: ch.pageCount,
          status: ch.status,
          chapter: ch,
          indent,
        })
      }
      walkNodes(node.children ?? [], depth + 1, nodePath, out)
    }
  })
}

const flatItems = computed<FlatItem[]>(() => {
  const out: FlatItem[] = []
  walkNodes(props.tree, 0, '', out)
  return out
})
</script>

<style scoped>
.catalog-tree {
  display: flex;
  flex-direction: column;
}

.catalog-scroller {
  max-height: min(70vh, 420px);
}

.node-header {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  box-sizing: border-box;
  padding: 0 12px;
  background: var(--bg-surface);
  border-radius: var(--radius-sm);
  cursor: pointer;
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
  transition: transform var(--transition-fast);
}

.expand-btn.expanded {
  transform: rotate(90deg);
}

.node-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.node-count {
  font-size: 12px;
  color: var(--text-muted);
}

/* 章节行对齐 42px 固定行高（作用于子组件根元素） */
.chapter-row {
  height: 42px;
  box-sizing: border-box;
}
</style>
