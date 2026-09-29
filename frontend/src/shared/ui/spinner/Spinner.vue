<script setup lang="ts">
withDefaults(defineProps<{ size?: 'inline' | 'small' | 'medium' | 'large'; color?: 'current' | 'accent' }>(), {
  size: 'medium',
  color: 'accent',
})
</script>

<template>
  <span
    class="app-spinner"
    :class="[`app-spinner--${size}`, `app-spinner--${color}`]"
    aria-hidden="true"
  />
</template>

<style scoped>
.app-spinner {
  --spinner-size: var(--content-state-spinner-size);
  --spinner-color: var(--accent);
  display: inline-block;
  flex: 0 0 auto;
  width: var(--spinner-size);
  height: var(--spinner-size);
  border: max(2px, calc(var(--spinner-size) / 12)) solid var(--border-strong);
  border-top-color: var(--spinner-color);
  border-radius: 50%;
  animation: app-spinner-rotate 700ms linear infinite;
}
.app-spinner--inline {
  --spinner-size: 1em;
}
.app-spinner--small {
  --spinner-size: 14px;
}
.app-spinner--medium {
  --spinner-size: 18px;
}
.app-spinner--large {
  --spinner-size: var(--content-state-spinner-size);
}
.app-spinner--current {
  --spinner-color: currentColor;
  border-color: currentColor;
  border-right-color: transparent;
}
@keyframes app-spinner-rotate {
  to {
    transform: rotate(360deg);
  }
}
</style>
