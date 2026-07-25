<template>
  <div class="reading-layout">
    <TopNav />
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import TopNav from '@/components/layout/TopNav.vue'
</script>

<style scoped>
.reading-layout {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--bg-primary);
}

.main-content {
  flex: 1;
  min-width: 0;
  padding: var(--nav-height) var(--content-gutter) var(--space-10);
}

@media (max-width: 768px) {
  .main-content {
    padding-right: var(--space-4);
    padding-bottom: calc(
      var(--mobile-tabbar-height) + var(--space-8) + env(safe-area-inset-bottom)
    );
    padding-left: var(--space-4);
  }
}
</style>
