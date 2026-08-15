<template>
  <span class="comic-atlas-logo" :class="`comic-atlas-logo--${size}`" aria-label="ComicAtlas">
    <svg viewBox="0 0 48 48" role="img" focusable="false" aria-hidden="true">
      <rect x="2" y="2" width="44" height="44" rx="12" class="logo-shell" />
      <path d="M11 12.5c5.8.4 9.3 2.1 13 5.4v18.5c-3.7-3.3-7.2-5-13-5.4z" class="logo-page" />
      <path d="M37 12.5c-5.8.4-9.3 2.1-13 5.4v18.5c3.7-3.3 7.2-5-13 5.4z" class="logo-page" />
      <path d="M27.5 22.5h4.2m-4.2 0v5m0-5-3.5-2.2m3.5 7h4.2m-4.2 0-3.5 2.2" class="logo-tree" />
      <circle cx="34.2" cy="22.5" r="2.1" class="logo-node logo-node--red" />
      <circle cx="34.2" cy="27.5" r="2.1" class="logo-node logo-node--teal" />
      <path d="M8 33.5c6.8 5.8 18.5 6.5 27.8.5" class="logo-orbit" />
      <path d="m24.2 30.2 5 2.8-5 2.8z" class="logo-play" />
    </svg>
    <span v-if="showWordmark" class="comic-atlas-logo__wordmark"><strong>Comic</strong><em>Atlas</em></span>
  </span>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ size?: 'sm' | 'md' | 'lg'; showWordmark?: boolean }>(), { size: 'md', showWordmark: true })
</script>

<style scoped>
.comic-atlas-logo { display: inline-flex; align-items: center; gap: .6rem; color: var(--text-primary); }
.comic-atlas-logo svg { width: 2.35rem; height: 2.35rem; flex: 0 0 auto; }
.comic-atlas-logo--sm svg { width: 1.8rem; height: 1.8rem; }
.comic-atlas-logo--lg svg { width: 3rem; height: 3rem; }
.logo-shell { fill: var(--bg-surface); stroke: var(--color-brand); stroke-width: 1.7; }
.logo-page { fill: none; stroke: var(--text-primary); stroke-width: 1.55; stroke-linejoin: round; }
.logo-tree { fill: none; stroke: var(--color-brand); stroke-width: 1.15; stroke-linecap: round; }
.logo-node--red { fill: var(--color-brand); }
.logo-node--teal { fill: var(--color-brand); }
.logo-orbit { fill: none; stroke: var(--color-brand); stroke-width: 1.2; stroke-linecap: round; }
.logo-play { fill: var(--color-brand); }
.comic-atlas-logo__wordmark { font-size: 1.08rem; font-weight: 800; letter-spacing: -.035em; }
.comic-atlas-logo__wordmark strong { color: var(--text-primary); }
.comic-atlas-logo__wordmark em { color: var(--color-brand); font-style: normal; }
</style>
