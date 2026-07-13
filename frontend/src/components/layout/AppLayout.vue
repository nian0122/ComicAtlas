<template>
  <div class="app-layout">
    <TopNav />
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import TopNav from './TopNav.vue'
</script>

<style scoped>
.app-layout {
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
