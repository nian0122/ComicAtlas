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
  display: flex; flex-direction: column; min-height: 100vh; background: #0f0f0f;
}
.main-content { flex: 1; overflow-y: auto; }
</style>
