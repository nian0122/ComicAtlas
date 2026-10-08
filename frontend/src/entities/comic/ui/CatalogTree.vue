<template>
  <div class="catalog-tree" :class="{ 'catalog-tree--page-mode': pageMode }">
    <RecycleScroller
      class="catalog-scroller"
      :items="flatItems"
      :item-size="40"
      key-field="flatKey"
      :buffer="100"
      :page-mode="pageMode"
    >
      <template #default="{ item }">
        <div
          v-if="item.type === 'header'"
          class="node-header"
          :style="{ paddingLeft: item.depth * 16 + 12 + 'px' }"
          @click="toggleExpanded(item.nodePath)"
        >
          <AppButton
            type="button"
            class="expand-btn"
            variant="ghost"
            size="sm"
            icon-only
            :aria-label="isExpanded(item.nodePath) ? `收起${item.title}` : `展开${item.title}`"
            :class="{ expanded: isExpanded(item.nodePath) }"
          >
            <el-icon :size="12"><ArrowRight /></el-icon>
          </AppButton>
          <span class="node-title">{{ item.title }}</span>
          <span v-if="item.count > 0" class="node-count">{{ item.count }} 话</span>
        </div>
        <CatalogChapterRow
          v-else
          :chapter="item.chapter"
          :active="item.chapterId === activeChapterId"
          :indent="item.indent"
          :highlight-keyword="highlightKeyword"
          @click="emit('select', item.chapterId)"
        />
      </template>
    </RecycleScroller>
  </div>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { computed, provide, ref, watch } from 'vue'
import { RecycleScroller } from 'vue-virtual-scroller'
import { ArrowRight } from '@element-plus/icons-vue'
import type { CatalogNode, ChapterRef } from '@/entities/comic/model/types'
import CatalogChapterRow from './CatalogChapterRow.vue'

/** 扁平化后的分组标题行 */
interface HeaderFlatItem {
  type: 'header'
  flatKey: string
  /** 完整 nodePath（如 `/1/3/5`），作为折叠状态唯一键，同名/无 id 目录互不干扰 */
  nodePath: string
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
  highlightKeyword?: string
  expandedNodePaths?: readonly string[]
  pageMode?: boolean
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
  return expandedIds.value.has(key) || props.expandedNodePaths?.includes(key) === true
}

provide('expandedIds', expandedIds)
provide('toggleExpanded', toggleExpanded)
provide('isExpanded', isExpanded)

/**
 * 节点路径段：优先用稳定 id；无 id 时用「下标:标题」保证同级唯一，
 * 避免共用 title/root 键导致同名目录折叠状态互相干扰。
 */
function keySegmentOf(node: CatalogNode, index: number): string {
  return node.id != null ? String(node.id) : `${index}:${node.title ?? ''}`
}

/** 递归统计节点全部后代章节数（不把子目录当"话"） */
function countChapters(node: CatalogNode): number {
  let count = node.chapters?.length ?? 0
  for (const child of node.children ?? []) count += countChapters(child)
  return count
}

// 默认展开顶层分组（与原 CatalogTreeNode depth=0 onMounted 自动展开行为一致）
watch(
  () => props.tree,
  (tree, previousTree) => {
    // 过滤树切换时不改写 expandedIds，确保清空搜索后恢复用户原来的折叠状态。
    if (previousTree?.length && tree.length) return
    tree.forEach((node, index) => {
      if (node.title) expandedIds.value.add(`/${keySegmentOf(node, index)}`)
    })
  },
  { immediate: true },
)

/**
 * 递归扁平化目录树：
 * - 有标题的节点输出 header 行，仅在展开时输出其章节与子节点
 * - 无标题的节点（匿名根）不输出 header，章节与子节点始终可见
 * 同级目录与章节按共享 sortOrder 混排；章节 globalOrder 由此树顺序派生，仅用于阅读器翻章。
 */
function walkNode(node: CatalogNode, depth: number, path: string, out: FlatItem[], index: number) {
  const nodePath = `${path}/${keySegmentOf(node, index)}`

  if (node.title) {
    out.push({
      type: 'header',
      flatKey: `h:${nodePath}`,
      nodePath,
      title: node.title,
      count: countChapters(node),
      depth,
    })
  }

  if (!node.title || isExpanded(nodePath)) {
    const indent = (node.title ? depth + 1 : depth) * 16
    const chapters = node.chapters ?? []
    const children = node.children ?? []
    const items = [
      ...children.map((node, childIndex) => ({
        kind: 'catalog' as const,
        order: node.sortOrder ?? Number.MAX_SAFE_INTEGER,
        node,
        childIndex,
      })),
      ...chapters.map((chapter) => ({
        kind: 'chapter' as const,
        order: chapter.sortOrder ?? Number.MAX_SAFE_INTEGER,
        chapter,
      })),
    ].sort((left, right) => left.order - right.order || left.kind.localeCompare(right.kind))

    for (const item of items) {
      if (item.kind === 'chapter') {
        const ch = item.chapter
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
      } else {
        // 携带原始兄弟下标，保证同名/无 id 子目录路径段唯一
        walkNode(item.node, depth + 1, nodePath, out, item.childIndex)
      }
    }
  }
}

function walkNodes(nodes: CatalogNode[], depth: number, path: string, out: FlatItem[]) {
  nodes.forEach((node, index) => walkNode(node, depth, path, out, index))
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
  overflow: hidden;
  border-top: 1px solid var(--border);
}

.catalog-scroller {
  max-height: min(calc(100vh - 96px), 720px);
}

.catalog-tree--page-mode {
  overflow: visible;
}

.catalog-tree--page-mode .catalog-scroller {
  max-height: none;
}

.node-header {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 40px;
  box-sizing: border-box;
  padding: 0 12px;
  border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent);
  border-left: 2px solid color-mix(in srgb, var(--accent) 58%, var(--border));
  background: color-mix(in srgb, var(--bg-surface) 38%, transparent);
  cursor: pointer;
  user-select: none;
  transition:
    background-color var(--transition-fast),
    border-color var(--transition-fast);
}

.node-header:hover {
  border-bottom-color: color-mix(in srgb, var(--accent) 30%, var(--border));
  background: color-mix(in srgb, var(--bg-surface) 78%, transparent);
}

.expand-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 28px;
  width: 28px;
  min-width: 28px;
  height: 28px;
  min-height: 28px;
  padding: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  transition:
    color var(--transition-fast),
    background-color var(--transition-fast),
    transform var(--transition-fast);
}

.node-header:hover .expand-btn {
  color: var(--accent);
  background: color-mix(in srgb, var(--surface-highlight) 80%, transparent);
}

.expand-btn.expanded {
  transform: rotate(90deg);
}

.node-title {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.node-count {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
