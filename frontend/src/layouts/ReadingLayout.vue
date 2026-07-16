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
  min-height: 100vh;
  background: var(--bg-primary);
}

.main-content {
  flex: 1;
  padding: var(--nav-height) var(--page-padding) var(--page-padding);
}
</style>
