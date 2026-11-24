<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RecycleScroller } from 'vue-virtual-scroller'
import { ManagementIcon } from '@/shared/ui/management'
import { useVirtualFocus } from '@/shared/lib/virtual-focus'

export interface ManagementTreeEntry {
  id: string
  title: string
  kind: 'catalog' | 'chapter'
  depth: number
}

interface TreeRow {
  key: string
  id: string
  title: string
  kind: 'root' | 'catalog' | 'chapter'
  depth: number
}

/* 行高与 management.css 的 .mw-tree-item 保持一致，树目录才有稳定的虚拟布局。 */
const ROW_SIZE = 38

const props = defineProps<{
  rootId: string
  rootTitle: string
  entries: ManagementTreeEntry[]
  catalogId: string
  chapterId: string
}>()
const emit = defineEmits<{
  root: []
  catalog: [id: string]
  chapter: [id: string]
}>()

const rows = computed<TreeRow[]>(() => [
  { key: `root:${props.rootId}`, id: props.rootId, title: props.rootTitle, kind: 'root', depth: 0 },
  ...props.entries.map((entry) => ({
    key: `${entry.kind}:${entry.id}`,
    id: entry.id,
    title: entry.title,
    kind: entry.kind,
    depth: entry.depth,
  })),
])
const activeKey = computed(() =>
  props.catalogId
    ? `catalog:${props.catalogId}`
    : props.chapterId
      ? `chapter:${props.chapterId}`
      : `root:${props.rootId}`,
)
const root = ref<HTMLElement>()
const scroller = ref<{
  scrollToItem: (index: number, options?: { align?: 'start' | 'center' | 'end' | 'nearest' }) => void
}>()

watch(
  [activeKey, () => rows.value.length],
  async () => {
    await nextTick()
    const index = rows.value.findIndex((row) => row.key === activeKey.value)
    if (index >= 0) scroller.value?.scrollToItem(index, { align: 'nearest' })
  },
  { immediate: true },
)

/* 行被回收时把焦点交还给同一行，避免目录虚拟化后键盘焦点落回 body。 */
const { onFocusIn, onFocusOut } = useVirtualFocus({
  keyOf: (element) => element.closest<HTMLElement>('[data-tree-key]')?.dataset.treeKey ?? '',
  within: () => root.value,
})
function activate(row: TreeRow) {
  if (row.kind === 'root') emit('root')
  else if (row.kind === 'catalog') emit('catalog', row.id)
  else emit('chapter', row.id)
}
</script>
<template>
  <div ref="root" class="mw-tree-list" @focusin="onFocusIn" @focusout="onFocusOut">
    <RecycleScroller
      ref="scroller"
      :items="rows"
      key-field="key"
      :item-size="ROW_SIZE"
      page-mode
      role="navigation"
      :aria-label="`${rootTitle}目录`"
    >
      <template #default="{ item }">
        <button
          class="mw-tree-item"
          :class="{ active: item.key === activeKey }"
          :style="item.kind === 'root' ? undefined : { paddingLeft: `${23 + Math.min(item.depth, 6) * 13}px` }"
          :aria-current="item.key === activeKey ? 'page' : undefined"
          :data-tree-key="item.key"
          @click="activate(item)"
        >
          <ManagementIcon
            :name="item.kind === 'root' ? 'book' : item.kind === 'catalog' ? 'folder' : 'image'"
            :size="item.kind === 'root' ? 15 : 14"
          /><span class="mw-tree-name">{{ item.title }}</span>
        </button>
      </template>
    </RecycleScroller>
  </div>
</template>
