<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { AppButton } from '@/shared/ui/button'
import ReadingIcon from './ReadingIcon.vue'
const props = defineProps<{ open: boolean; title: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
watch(
  () => props.open,
  async (open) => {
    await nextTick()
    if (open && !dialog.value?.open) dialog.value?.showModal()
    else if (!open) dialog.value?.close()
  },
  { immediate: true },
)
</script>
<template>
  <dialog
    ref="dialog"
    class="ca-dialog ca-scope"
    :aria-label="title"
    @cancel.prevent="emit('close')"
    @close="emit('close')"
    @click="
      (event) => {
        if (event.target === dialog) emit('close')
      }
    "
  >
    <header>
      <h2>{{ title }}</h2>
      <AppButton variant="text" icon-only aria-label="关闭" @click="emit('close')"
        ><ReadingIcon name="close"
      /></AppButton>
    </header>
    <slot />
  </dialog>
</template>
