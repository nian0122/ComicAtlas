<template>
  <div class="content-workspace">
    <PanelHeader title="目录与存储" description="在同一工作区管理目录、媒体文件和 HQ / LQ 存储状态。" />
    <section class="content-stage">
      <ComicStructurePage />
    </section>
  </div>
</template>

<script setup lang="ts">
import { PanelHeader } from '@/shared/ui/management-panel'
import ComicStructurePage from './ComicStructurePage.vue'
</script>

<style scoped>
.content-workspace {
  display: grid;
  gap: var(--space-5);
  width: 100%;
  min-width: 0;
}
.content-stage {
  min-width: 0;
}
.content-stage > :deep(.structure-page) {
  width: 100%;
  max-width: none;
  padding: 0;
}
.content-stage > :deep(.structure-page > .structure-header) {
  display: none;
}
</style>
