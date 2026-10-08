<script setup lang="ts">
defineProps<{ title: string; description: string }>()
</script>

<template>
  <section class="analysis-hero">
    <div class="hero-mark" aria-hidden="true">AI</div>
    <div class="hero-copy">
      <span class="eyebrow">VISION / ENRICHMENT</span>
      <h2>{{ title }}</h2>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.default" class="hero-status"><slot /></div>
  </section>
</template>

<style scoped>
.analysis-hero {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  padding: var(--space-6);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
}
.hero-mark {
  display: grid;
  place-items: center;
  flex: 0 0 68px;
  height: 68px;
  border: 1px solid var(--accent-border);
  color: var(--accent);
  background: var(--accent-bg);
  font: 800 20px var(--font-ui);
  letter-spacing: 0.08em;
}
.hero-copy {
  min-width: 0;
  flex: 1;
}
.hero-copy h2 {
  margin: var(--space-2) 0;
  color: var(--text-primary);
  font: 700 var(--text-section)/1.2 var(--font-editorial);
  overflow-wrap: anywhere;
}
.hero-copy p {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  line-height: var(--leading-body);
}
.eyebrow {
  color: var(--accent);
  font: 700 var(--text-xs) var(--mono);
  letter-spacing: var(--tracking-kicker);
}
.hero-status {
  color: var(--text-muted);
  font-size: var(--text-sm);
}
@media (max-width: 800px) {
  .analysis-hero {
    flex-wrap: wrap;
    align-items: flex-start;
  }
  .hero-status {
    width: 100%;
  }
}
</style>
