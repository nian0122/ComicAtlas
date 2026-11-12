<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { RecycleScroller } from 'vue-virtual-scroller'
import { ManagementIcon, formatBytes } from '@/shared/ui/management'
import type { OrderRow } from '../model/types'

const ITEM_SIZE = 52
const props = defineProps<{
  rows: OrderRow[]
  selected: string[]
  active: string
  busy: boolean
  unit: string
}>()
const emit = defineEmits<{
  reorder: [from: string, to: string, after: boolean]
  move: [direction: -1 | 1]
  focus: [id: string]
}>()
const list = ref<{ $el: HTMLElement } | null>(null)
const dragged = ref(''),
  dropTarget = ref<{ id: string; after: boolean } | null>(null)
const kinds = { comic: '漫画', catalog: '目录', chapter: '章节', image: '图片', video: '视频' } as const
const icons = { comic: 'book', catalog: 'folder', chapter: 'openFolder', image: 'image', video: 'video' } as const
function meta(row: OrderRow) {
  if (row.kind === 'chapter' && row.count !== null) return `${row.count} 个媒体`
  if (row.kind === 'catalog' && row.count !== null) return `${row.count} 个子项`
  if (row.bytes !== null) return formatBytes(row.bytes)
  return row.subtitle
}
function rowElement(id: string) {
  return list.value?.$el.querySelector<HTMLElement>(`[data-order-id="${CSS.escape(id)}"]`) ?? null
}
async function focusRow(id: string) {
  const index = props.rows.findIndex((row) => row.id === id)
  if (index < 0) return
  const scroller = list.value?.$el
  if (scroller) {
    const top = index * ITEM_SIZE
    if (top < scroller.scrollTop || top + ITEM_SIZE > scroller.scrollTop + scroller.clientHeight)
      scroller.scrollTop = Math.max(0, top - 4)
  }
  for (let attempt = 0; attempt < 8; attempt++) {
    await nextTick()
    const element = rowElement(id)
    if (element) {
      element.focus({ preventScroll: true })
      await new Promise((resolve) => requestAnimationFrame(resolve))
      if (document.activeElement === element) return
    } else await new Promise((resolve) => requestAnimationFrame(resolve))
  }
}
function resetDrag() {
  dragged.value = ''
  dropTarget.value = null
}
function dragStart(event: DragEvent, row: OrderRow) {
  if (props.busy) {
    event.preventDefault()
    return
  }
  dragged.value = row.id
  dropTarget.value = null
  event.dataTransfer?.setData('text/plain', row.id)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function dragOver(event: DragEvent, row: OrderRow) {
  if (!dragged.value || dragged.value === row.id) return
  event.preventDefault()
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  dropTarget.value = { id: row.id, after: event.clientY > rect.top + rect.height / 2 }
  const scroller = list.value?.$el
  if (!scroller) return
  const bounds = scroller.getBoundingClientRect()
  if (event.clientY < bounds.top + 48) scroller.scrollTop -= 16
  else if (event.clientY > bounds.bottom - 48) scroller.scrollTop += 16
}
function drop(event: DragEvent) {
  event.preventDefault()
  const target = dropTarget.value
  if (dragged.value && target && dragged.value !== target.id) emit('reorder', dragged.value, target.id, target.after)
  resetDrag()
}
function onKeydown(event: KeyboardEvent, row: OrderRow) {
  if (props.busy || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return
  const index = props.rows.findIndex((item) => item.id === row.id)
  if (index < 0) return
  event.preventDefault()
  const delta = event.key === 'ArrowUp' ? -1 : 1
  if (event.altKey) {
    emit('move', delta)
    void focusRow(row.id)
    return
  }
  const next = props.rows[index + delta]
  if (next) void focusRow(next.id)
}
</script>

<template>
  <section class="mw-order-session" aria-label="调整顺序">
    <header class="mw-order-head">
      <p class="mw-order-summary">共 {{ rows.length }} {{ unit }} · 整组提交，保存后生效</p>
      <p class="mw-order-hint"><ManagementIcon name="info" :size="13" />拖动行，或聚焦后按 Alt+↑ / Alt+↓ 移动</p>
    </header>
    <div class="mw-order-scroll">
      <RecycleScroller
        ref="list"
        :items="rows"
        :item-size="ITEM_SIZE"
        key-field="id"
        role="list"
        class="mw-order-list"
        :aria-label="`同级范围，共 ${rows.length} ${unit}`"
      >
        <template #default="{ item, index }">
          <div
            :data-order-id="item.id"
            class="mw-order-row"
            :class="{
              'is-active': active === item.id,
              'is-selected': selected.includes(item.id),
              'is-dragged': dragged === item.id,
              'is-over-before': dropTarget?.id === item.id && !dropTarget.after,
              'is-over-after': dropTarget?.id === item.id && dropTarget.after,
            }"
            role="listitem"
            tabindex="0"
            :draggable="!busy"
            :aria-label="`第 ${index + 1} 项，${item.title}`"
            :aria-posinset="index + 1"
            :aria-setsize="rows.length"
            @focus="emit('focus', item.id)"
            @keydown="onKeydown($event, item)"
            @dragstart="dragStart($event, item)"
            @dragover="dragOver($event, item)"
            @drop="drop"
            @dragend="resetDrag"
          >
            <ManagementIcon class="mw-order-grip" name="grip" :size="16" />
            <span class="mw-order-index">{{ index + 1 }}</span>
            <ManagementIcon class="mw-order-kind" :name="icons[item.kind]" :size="16" />
            <span class="mw-order-title" :title="item.title">{{ item.title }}</span>
            <span class="mw-order-meta">{{ meta(item) }}</span>
            <span class="mw-order-badge">{{ kinds[item.kind] }}</span>
          </div>
        </template>
      </RecycleScroller>
    </div>
  </section>
</template>
