<template>
  <svg
    class="material-symbol-icon"
    viewBox="0 0 24 24"
    aria-hidden="true"
    focusable="false"
  >
    <path
      v-if="name === 'play'"
      d="M6.5 5.5v13l11-6.5-11-6.5Z"
      fill="none"
      stroke="currentColor"
      stroke-linejoin="round"
      stroke-width="1.75"
    />
    <path
      v-else-if="name === 'refresh'"
      d="M20 4v6h-6m6 0a8 8 0 1 0 1.1 6"
      fill="none"
      stroke="currentColor"
      stroke-linecap="round"
      stroke-linejoin="round"
      stroke-width="2"
    />
    <path v-else :d="paths[name]" />
  </svg>
</template>

<script setup lang="ts">
type MaterialIconName = 'history' | 'home' | 'library' | 'play' | 'refresh'

interface Props {
  readonly name: MaterialIconName
}

defineProps<Props>()

const paths: Readonly<Record<Exclude<MaterialIconName, 'play' | 'refresh'>, string>> = {
  history: 'M13 3a9 9 0 0 0-9 9H1l4 4 4-4H6a7 7 0 1 1 2.05 4.95l-1.42 1.42A9 9 0 1 0 13 3Zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12Z',
  home: 'M12 3 2 12h3v9h6v-6h2v6h6v-9h3L12 3Zm5 16h-2v-6H9v6H7v-7.81l5-4.5 5 4.5V19Z',
  library: 'M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6Zm16-4H8a2 2 0 0 0-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4a2 2 0 0 0-2-2Zm-1 9H9V9h10v2Zm-4 4H9v-2h6v2Zm4-8H9V5h10v2Z',
}
</script>

<style scoped>
.material-symbol-icon {
  display: block;
  fill: currentcolor;
}
</style>
