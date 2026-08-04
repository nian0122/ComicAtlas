<template>
  <section class="media-tab" data-testid="media-tab">
    <div class="media-header">
      <h2 class="media-title">媒体</h2>
      <p class="media-subtitle">章节媒体概览（完整媒体管理见阅读器）</p>
    </div>

    <div class="media-empty" data-testid="media-empty">
      当前工作区媒体概览。选择章节后可在阅读端查看媒体详情。
    </div>
  </section>
</template>

<script setup lang="ts">
</script>

<style scoped>
.media-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  min-width: 0;
}

.media-header {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.media-title {
  margin: 0;
  font-size: var(--text-section);
  font-weight: 700;
  color: var(--text-primary);
}

.media-subtitle {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-muted);
}

.media-empty {
  padding: var(--space-8);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px dashed var(--border-strong);
  color: var(--text-muted);
  font-size: var(--text-sm);
}
</style>
