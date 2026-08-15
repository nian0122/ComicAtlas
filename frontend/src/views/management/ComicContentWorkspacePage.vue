<template>
  <div class="content-workspace">
    <header class="content-header">
      <div><span class="eyebrow">COMIC / CONTENT + STORAGE</span><h2>目录与存储</h2><p>在同一工作区管理目录、媒体文件和 HQ / LQ 存储状态。</p></div>
    </header>
    <section class="content-stage">
      <ComicStructurePage />
    </section>
  </div>
</template>

<script setup lang="ts">
import ComicStructurePage from './ComicStructurePage.vue'
</script>

<style scoped>
.content-workspace { display: grid; gap: var(--space-5); width: 100%; }
.content-header { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--space-5); padding-bottom: var(--space-5); border-bottom: 1px solid var(--border); }
.eyebrow { color: var(--accent); font: 800 10px var(--mono); letter-spacing: .16em; }
.content-header h2 { margin: var(--space-2) 0; color: var(--text-primary); font-family: Georgia, 'Times New Roman', serif; font-size: clamp(1.8rem, 3vw, 2.5rem); letter-spacing: -.04em; }
.content-header p { color: var(--text-muted); font-size: var(--text-sm); }
.content-stage { min-width: 0; }
.content-stage :deep(.structure-page) { width: 100%; max-width: none; padding: 0; }
.content-stage :deep(.structure-header) { display: none; }
</style>
