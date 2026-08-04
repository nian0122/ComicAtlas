<template>
  <div class="tasks-page fade-in">
    <header class="tasks-header">
      <h1 class="page-title">任务中心</h1>
      <p class="page-subtitle">统一管理任务（导入 / 导出 / 恢复 / 扫描 / LQ / HQ / 转码 / 元数据）</p>
    </header>
    <TasksTab />
  </div>
</template>

<script setup lang="ts">
import TasksTab from './console/TasksTab.vue'
</script>

<style scoped>
.tasks-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  min-width: 0;
}

.tasks-header {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.page-title {
  margin: 0;
  font-size: var(--text-page);
  font-weight: 700;
  font-family: var(--heading);
  color: var(--text-primary);
}

.page-subtitle {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-muted);
}
</style>
