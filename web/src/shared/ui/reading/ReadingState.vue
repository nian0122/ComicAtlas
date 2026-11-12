<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import ReadingIcon from './ReadingIcon.vue'
withDefaults(
  defineProps<{
    title?: string
    message?: string
    loading?: boolean
    error?: boolean
    action?: string
    level?: 1 | 2
  }>(),
  { title: '这里暂时还没有作品', message: '', action: '', level: 2 },
)
defineEmits<{ action: [] }>()
</script>
<template>
  <div v-if="loading" class="ca-skeleton-grid" role="status" aria-busy="true" aria-label="正在加载作品">
    <div v-for="n in 9" :key="n" />
  </div>
  <section v-else class="ca-empty" :role="error ? 'alert' : 'status'">
    <ReadingIcon :name="error ? 'missing' : 'library'" :size="34" />
    <component :is="`h${level}`">{{ title }}</component>
    <p v-if="message">{{ message }}</p>
    <AppButton v-if="action" @click="$emit('action')">{{ action }}</AppButton
    ><slot />
  </section>
</template>
