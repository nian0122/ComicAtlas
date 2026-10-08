<template>
  <AppButton
    icon-only
    variant="ghost"
    :disabled="pending"
    :aria-label="active ? '退出全屏' : '进入全屏'"
    :title="active ? '退出全屏' : '进入全屏'"
    :aria-pressed="active"
    @click="emit('toggle')"
  >
    <svg
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <path v-if="active" d="M3 8h5V3m8 0v5h5M3 16h5v5m8 0v-5h5" />
      <path v-else d="M8 3H3v5m13-5h5v5M3 16v5h5m8 0h5v-5" />
    </svg>
  </AppButton>
</template>

<script setup lang="ts">
import AppButton from './AppButton.vue'

withDefaults(defineProps<{ active?: boolean; pending?: boolean }>(), { active: false, pending: false })
const emit = defineEmits<{ (event: 'toggle'): void }>()
</script>
