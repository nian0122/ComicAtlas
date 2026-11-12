<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'text' | 'danger'
    size?: 'sm' | 'default'
    iconOnly?: boolean
    loading?: boolean
    disabled?: boolean
    type?: 'button' | 'submit'
  }>(),
  { variant: 'secondary', type: 'button', size: 'default' },
)
</script>
<template>
  <button
    :type="type"
    class="app-button"
    :class="[`app-button--${variant}`, { 'is-small': size === 'sm', 'is-icon': iconOnly }]"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
  >
    <span v-if="loading" class="ca-button-spinner" aria-hidden="true" /><span class="app-button-content"><slot /></span>
  </button>
</template>
