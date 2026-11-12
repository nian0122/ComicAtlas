<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { comicApi, type CoverSelection } from '@/entities/comic'
import { selectComicCover } from '@/features/manage-cover'
import { getApiErrorMessage, ApiError } from '@/shared/api/http'
import { ManagementDialog, ManagementImage } from '@/shared/ui/management'
const props = defineProps<{
  open: boolean
  comicId: string
  mediaId: string
  title: string
  previewUrl: string | null
  writable: boolean
}>()
const emit = defineEmits<{ close: []; changed: [] }>()
const selection = ref<CoverSelection | null>(null),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  conflict = ref(false)
let generation = 0
async function load() {
  const current = ++generation,
    id = props.comicId
  loading.value = true
  selection.value = null
  error.value = ''
  conflict.value = false
  try {
    const result = await comicApi.coverSelection(id)
    if (current !== generation) return
    if (result.comicId !== id || !Number.isInteger(result.version) || result.version < 1 || !result.mediaId)
      throw new Error('封面选择响应与当前漫画不一致')
    selection.value = result
  } catch (e) {
    if (current === generation) error.value = getApiErrorMessage(e, '读取封面选择失败')
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(
  () => [props.open, props.comicId, props.mediaId],
  () => {
    if (props.open) void load()
    else generation++
  },
  { immediate: true },
)
onBeforeUnmount(() => generation++)
async function save() {
  const choice = selection.value
  if (!choice || !props.writable || saving.value || loading.value || conflict.value || choice.mediaId === props.mediaId)
    return
  const current = generation,
    comicId = props.comicId,
    mediaId = props.mediaId
  saving.value = true
  error.value = ''
  try {
    const result = await selectComicCover(comicId, mediaId, choice.version)
    if (current !== generation) return
    if (result.comicId !== comicId || result.mediaId !== mediaId || result.version <= choice.version)
      throw new Error('封面保存响应与当前选择不一致，请刷新核对')
    emit('changed')
    emit('close')
  } catch (e) {
    if (current !== generation) return
    conflict.value = e instanceof ApiError && e.status === 409
    error.value = conflict.value ? '封面选择已变化。请重新读取并核对后再保存。' : getApiErrorMessage(e, '设置封面失败')
  } finally {
    saving.value = false
  }
}
</script>
<template>
  <ManagementDialog :open="open" title="设置漫画封面" :busy="saving" @close="emit('close')">
    <div class="cover-choice">
      <div class="cover-choice-preview"><ManagementImage :src="previewUrl" :alt="title" /></div>
      <div>
        <h3>{{ title }}</h3>
        <p class="mw-muted">使用这张图片作为漫画封面。封面选择会保留，调整章节和页码顺序不会换图。</p>
      </div>
    </div>
    <p v-if="loading" class="mw-muted">正在读取当前封面选择…</p>
    <p v-if="error" class="mw-error" role="alert">{{ error }}</p>
    <p v-if="selection?.mediaId === mediaId" class="mw-notice">当前已经使用这张图片作为封面。</p>
    <button v-if="error" class="mw-button" :disabled="loading || saving" @click="load">重新读取封面选择</button>
    <footer class="mw-dialog-footer">
      <button class="mw-button" :disabled="saving" @click="emit('close')">取消</button
      ><button
        class="mw-button mw-button-primary"
        :disabled="!selection || !writable || loading || saving || conflict || selection.mediaId === mediaId"
        @click="save"
      >
        {{ saving ? '正在保存…' : '设为封面' }}
      </button>
    </footer>
  </ManagementDialog>
</template>
<style scoped>
.cover-choice {
  display: flex;
  align-items: center;
  gap: 20px;
  margin: 16px 0 24px;
}
.cover-choice-preview {
  width: 100px;
  aspect-ratio: 3 / 4;
  flex-shrink: 0;
  overflow: hidden;
  border-radius: var(--atlas-radius-control);
  background: var(--atlas-surface);
  display: grid;
  place-items: center;
}
.cover-choice-preview :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.cover-choice h3 {
  font-size: 16px;
  overflow-wrap: anywhere;
}
</style>
