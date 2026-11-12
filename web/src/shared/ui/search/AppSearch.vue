<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { Search, X } from '@lucide/vue'

withDefaults(
  defineProps<{ modelValue?: string; label: string; placeholder?: string; disabled?: boolean; clearLabel?: string }>(),
  { modelValue: '', placeholder: '', clearLabel: '清空搜索' },
)
const emit = defineEmits<{ 'update:modelValue': [value: string]; search: []; submit: [] }>()
const input = ref<HTMLInputElement>()
let pending: ReturnType<typeof setTimeout> | undefined
function change(event: Event) {
  if ((event as InputEvent).isComposing) return
  emit('update:modelValue', (event.target as HTMLInputElement).value)
  clearTimeout(pending)
  pending = setTimeout(() => emit('search'), 300)
}
function submit(event: KeyboardEvent) {
  if (event.isComposing) return
  event.preventDefault()
  clearTimeout(pending)
  emit('search')
  emit('submit')
}
function clear() {
  clearTimeout(pending)
  emit('update:modelValue', '')
  emit('search')
  input.value?.focus()
}
onBeforeUnmount(() => clearTimeout(pending))
</script>
<template>
  <div class="atlas-search" :class="{ 'is-disabled': disabled }" @click="input?.focus()">
    <Search :size="16" aria-hidden="true" />
    <input
      ref="input"
      type="search"
      :value="modelValue"
      :aria-label="label"
      :placeholder="placeholder"
      :disabled="disabled"
      @input="change"
      @compositionend="change"
      @keydown.enter="submit"
    />
    <span class="atlas-search-tail"
      ><button
        v-if="modelValue"
        type="button"
        class="atlas-search-clear"
        :aria-label="clearLabel"
        :disabled="disabled"
        @click="clear"
      >
        <X :size="15" aria-hidden="true" /></button
    ></span>
  </div>
</template>
