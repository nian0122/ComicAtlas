<template>
  <div class="poster-test-page">
    <ComicPoster
      id="poster-test-1"
      cover-url="https://example.com/nonexistent-cover.jpg"
      title="测试漫画"
      subtitle="作者 · 120页 · 45%"
      :progress="45"
      status="ready"
      size="md"
    />
  </div>
</template>

<script setup lang="ts">
import ComicPoster from '@/components/reading/comic/ComicPoster.vue'
</script>

<style scoped>
.poster-test-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-primary);
  padding: var(--page-padding);
}
</style>
