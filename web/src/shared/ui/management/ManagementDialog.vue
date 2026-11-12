<script setup lang="ts">
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'
import ManagementIcon from './ManagementIcon.vue'
const props = defineProps<{
  open: boolean
  title: string
  busy?: boolean
  placement?: 'center' | 'right'
  size?: 'compact' | 'form' | 'wide' | 'results'
}>()
const emit = defineEmits<{ close: [] }>()
const element = ref<HTMLDialogElement>()
onBeforeUnmount(() => element.value?.close())
watch(
  () => props.open,
  async (open) => {
    await nextTick()
    if (open && !element.value?.open) element.value?.showModal()
    else if (!open) element.value?.close()
  },
  { immediate: true },
)
function close() {
  if (!props.busy) emit('close')
}
</script>
<template>
  <dialog
    ref="element"
    class="mw-scope mw-dialog"
    :class="[{ 'mw-dialog-drawer': placement === 'right' }, size && `mw-dialog-${size}`]"
    :aria-label="title"
    @cancel.prevent="close"
    @click="
      (e) => {
        if (e.target === element) close()
      }
    "
  >
    <header>
      <div>
        <h2>{{ title }}</h2>
      </div>
      <button class="mw-icon-button" aria-label="关闭对话框" :disabled="busy" @click="close">
        <ManagementIcon name="close" />
      </button>
    </header>
    <slot />
  </dialog>
</template>
