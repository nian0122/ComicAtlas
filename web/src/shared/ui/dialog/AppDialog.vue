<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { AppButton } from '@/shared/ui/button'
import { ManagementIcon } from '@/shared/ui/management'
import { ReadingIcon } from '@/shared/ui/reading'

/*
 * 阅读端与管理端的对话框只保留一份生命周期实现：
 * 原生 dialog 的 showModal/close、Esc 取消、点遮罩关闭、卸载前关闭和关闭按钮都在这里，
 * 两端只在主题类名、标题结构和关闭按钮控件上不同。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    scope?: 'reading' | 'management'
    busy?: boolean
    placement?: 'center' | 'right'
    size?: 'compact' | 'form' | 'wide' | 'results'
  }>(),
  { scope: 'management', busy: false, placement: 'center', size: undefined },
)
const emit = defineEmits<{ close: [] }>()
const element = ref<HTMLDialogElement>()
watch(
  () => props.open,
  async (open) => {
    await nextTick()
    if (open && !element.value?.open) element.value?.showModal()
    else if (!open) element.value?.close()
  },
  { immediate: true },
)
onBeforeUnmount(() => element.value?.close())
function close() {
  if (!props.busy) emit('close')
}
function nativeClose() {
  if (props.scope === 'reading') emit('close')
}
</script>
<template>
  <dialog
    ref="element"
    :class="[
      scope === 'reading' ? ['ca-scope', 'ca-dialog'] : ['mw-scope', 'mw-dialog'],
      scope === 'management' && placement === 'right' ? 'mw-dialog-drawer' : '',
      scope === 'management' && size ? `mw-dialog-${size}` : '',
    ]"
    :aria-label="title"
    @cancel.prevent="close"
    @click="
      (event) => {
        if (event.target === element) close()
      }
    "
    @close="nativeClose"
  >
    <header>
      <div v-if="scope === 'management'">
        <h2>{{ title }}</h2>
      </div>
      <h2 v-else>{{ title }}</h2>
      <button
        v-if="scope === 'management'"
        class="mw-icon-button"
        aria-label="关闭对话框"
        :disabled="busy"
        @click="close"
      >
        <ManagementIcon name="close" />
      </button>
      <AppButton v-else variant="text" icon-only aria-label="关闭" @click="close"
        ><ReadingIcon name="close"
      /></AppButton>
    </header>
    <slot />
  </dialog>
</template>
