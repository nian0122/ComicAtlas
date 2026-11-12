<script setup lang="ts">
import { ref, watch } from 'vue'
import { ManagementIcon, ManagementImage } from '@/shared/ui/management'
import type { FileRow } from '../model/types'
const props = defineProps<{
  rows: FileRow[]
  selected: string[]
  active: string
  mode: 'list' | 'grid'
  busy: boolean
  loading: boolean
  selectionMode: boolean
  tileSize: number
  emptyTitle: string
  emptyMessage: string
}>()
const emit = defineEmits<{
  open: [row: FileRow]
  focus: [row: FileRow]
  details: [row: FileRow]
  select: [id: string, range: boolean]
  selectAll: []
}>()
const collection = ref<HTMLElement>()
const failedImages = ref(new Map<string, Set<string>>())
const pendingImages = ref(new Set<string>())
const imageVersions = ref<Record<string, number>>({})
watch(
  () => props.rows,
  (rows, previous) => {
    const ids = new Set(
      rows
        .filter((row) =>
          previous.some(
            (old) =>
              old.id === row.id &&
              old.cover === row.cover &&
              JSON.stringify(old.previewImages) === JSON.stringify(row.previewImages),
          ),
        )
        .map((row) => row.id),
    )
    failedImages.value = new Map([...failedImages.value].filter(([id]) => ids.has(id)))
    pendingImages.value = new Set([...pendingImages.value].filter((id) => ids.has(id)))
    imageVersions.value = Object.fromEntries(Object.entries(imageVersions.value).filter(([id]) => ids.has(id)))
  },
)
function imageFailed(id: string, image: string) {
  pendingImages.value.delete(id)
  const failures = failedImages.value.get(id) ?? new Set<string>()
  failures.add(image)
  failedImages.value.set(id, failures)
}
function imageReady(id: string, image: string) {
  const failures = failedImages.value.get(id)
  failures?.delete(image)
  if (failures?.size) return
  const row = collection.value?.querySelector<HTMLElement>(`[data-file-id="${CSS.escape(id)}"]`)
  if (document.activeElement === row?.querySelector('.mw-image-retry'))
    row?.querySelector<HTMLElement>('.mw-file-title')?.focus({ preventScroll: true })
  pendingImages.value.delete(id)
  failedImages.value.delete(id)
}
function reloadImage(id: string) {
  if (pendingImages.value.has(id)) return
  pendingImages.value.add(id)
  imageVersions.value[id] = (imageVersions.value[id] ?? 0) + 1
}
const icons = { comic: 'book', catalog: 'folder', chapter: 'openFolder', image: 'image', video: 'video' } as const
function activate(row: FileRow) {
  emit('open', row)
}
</script>
<template>
  <div v-if="loading" class="mw-loading" role="status">
    <ManagementIcon name="loading" class="mw-spin" />正在读取资料…
  </div>
  <div v-else-if="!rows.length" class="mw-empty mw-files-empty">
    <ManagementIcon name="folder" :size="40" />
    <h3>{{ emptyTitle }}</h3>
    <p>{{ emptyMessage }}</p>
  </div>
  <div
    v-else
    ref="collection"
    class="mw-file-collection"
    :class="[mode === 'grid' ? 'mw-file-grid' : 'mw-file-list', { 'mw-selection-mode': selectionMode }]"
    :style="{ '--mw-tile-size': `${tileSize}px` }"
  >
    <article
      v-for="row in rows"
      :key="row.id"
      :data-file-id="row.id"
      :class="{ active: active === row.id, selected: selected.includes(row.id) }"
    >
      <label class="mw-file-check"
        ><input
          type="checkbox"
          :aria-label="`选择${row.title}`"
          :checked="selected.includes(row.id)"
          :disabled="busy"
          @click="emit('select', row.id, $event.shiftKey)"
      /></label>
      <button
        class="mw-tile-cover"
        :class="`mw-kind-${row.kind}`"
        :aria-label="`${row.kind === 'image' || row.kind === 'video' ? '预览' : '打开'}${row.title}`"
        @click="activate(row)"
        @focus="emit('focus', row)"
      >
        <span
          v-if="row.previewImages.length"
          class="mw-node-mosaic"
          :class="{ 'mw-single-image': row.previewImages.length === 1 }"
          ><ManagementImage
            v-for="(src, index) in row.previewImages"
            :key="`${row.id}:${index}:${imageVersions[row.id] ?? 0}`"
            :src="src"
            :alt="`${row.title}预览${index + 1}`"
            @failed="imageFailed(row.id, String(index))"
            @ready="imageReady(row.id, String(index))"
        /></span>
        <ManagementImage
          v-else-if="row.cover"
          :key="`${row.id}:${imageVersions[row.id] ?? 0}`"
          :src="row.cover"
          :alt="row.title"
          @failed="imageFailed(row.id, 'cover')"
          @ready="imageReady(row.id, 'cover')"
        />
        <span v-else class="mw-no-preview"
          ><ManagementIcon :name="icons[row.kind]" :size="32" /><small>{{
            row.kind === 'video' ? '视频' : '暂无预览'
          }}</small></span
        >
        <span v-if="row.kind === 'video'" class="mw-media-badge"><ManagementIcon name="video" :size="14" />播放</span>
      </button>
      <div class="mw-file-caption">
        <div class="mw-file-copy">
          <button class="mw-file-title" @click="activate(row)">{{ row.title }}</button><small>{{ row.subtitle }}</small>
          <button
            v-if="failedImages.has(row.id)"
            class="mw-button mw-image-retry"
            :aria-label="`重新加载${row.title}预览`"
            :aria-disabled="pendingImages.has(row.id)"
            @click="reloadImage(row.id)"
          >
            <ManagementIcon
              :name="pendingImages.has(row.id) ? 'loading' : 'refresh'"
              :class="{ 'mw-spin': pendingImages.has(row.id) }"
              :size="14"
            />{{ pendingImages.has(row.id) ? '正在重新加载' : '重试预览' }}
          </button>
        </div>
        <button
          class="mw-file-details"
          :aria-label="`查看${row.title}详情`"
          :disabled="busy"
          @click="emit('details', row)"
        >
          <ManagementIcon name="info" :size="17" />
        </button>
      </div>
    </article>
  </div>
</template>
