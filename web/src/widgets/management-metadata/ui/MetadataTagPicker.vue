<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import type { Term } from '@/entities/comic'
import { AppSearch } from '@/shared/ui/search'
const props = defineProps<{
  modelValue: string[]
  tags: Term[]
  label: string
  searchLabel: string
  clearLabel: string
  error?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()
const query = ref('')
const errorId = useId()
const visible = computed(() =>
  props.tags.filter((tag) => tag.name.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())),
)
function toggle(id: string, checked: boolean) {
  if (checked && !props.modelValue.includes(id) && props.modelValue.length >= 100) return
  emit(
    'update:modelValue',
    checked ? [...new Set([...props.modelValue, id])] : props.modelValue.filter((value) => value !== id),
  )
}
</script>
<template>
  <details v-if="tags.length" class="metadata-label-picker" :open="!!error">
    <summary>已选 {{ modelValue.length }} 个标签 · 展开修改</summary>
    <div
      role="group"
      :aria-label="label"
      tabindex="-1"
      :aria-invalid="error ? true : undefined"
      :aria-describedby="error ? errorId : undefined"
    >
      <AppSearch v-if="tags.length > 8" v-model="query" :label="searchLabel" placeholder="输入标签名称" />
      <div class="metadata-label-options">
        <label v-for="tag in visible" :key="tag.id"
          ><input
            type="checkbox"
            :checked="modelValue.includes(tag.id)"
            :disabled="!modelValue.includes(tag.id) && modelValue.length >= 100"
            @change="toggle(tag.id, ($event.target as HTMLInputElement).checked)"
          />{{ tag.name }}</label
        >
      </div>
      <p v-if="!visible.length" class="mw-muted">没有匹配的标签，已选标签保持不变。</p>
      <p class="mw-muted">已选 {{ modelValue.length }} / 100 项。搜索只筛选选项，不会清除勾选。</p>
      <button class="mw-button" :disabled="!modelValue.length" @click="emit('update:modelValue', [])">
        {{ clearLabel }}
      </button>
    </div>
  </details>
  <span v-else class="metadata-empty-tags">暂无可选标签</span>
  <small v-if="error" :id="errorId" class="metadata-field-error">{{ error }}</small>
</template>
