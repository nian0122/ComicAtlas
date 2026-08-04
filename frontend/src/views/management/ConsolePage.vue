<template>
  <div class="console-page fade-in">
    <header class="console-header">
      <h1 class="page-title">仓库控制台</h1>
      <p class="page-subtitle">统一管理异步任务、媒体优化与危险操作</p>
    </header>

    <el-tabs v-model="activeTab" class="console-tabs">
      <el-tab-pane name="optimization" :lazy="true">
        <template #label>
          <span data-testid="console-tab-optimization">优化</span>
        </template>
        <OptimizationTab />
      </el-tab-pane>
      <el-tab-pane name="tasks" :lazy="true">
        <template #label>
          <span data-testid="console-tab-tasks">任务</span>
        </template>
        <TasksTab :active="activeTab === 'tasks'" />
      </el-tab-pane>
      <el-tab-pane name="danger" :lazy="true">
        <template #label>
          <span data-testid="console-tab-danger">危险</span>
        </template>
        <DangerTab />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import OptimizationTab from './console/OptimizationTab.vue'
import TasksTab from './console/TasksTab.vue'
import DangerTab from './console/DangerTab.vue'

const activeTab = ref('optimization')
</script>

<style scoped>
.console-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  min-width: 0;
}

.console-header {
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

.console-tabs {
  min-width: 0;
}

.console-tabs :deep(.el-tabs__nav-wrap::after) {
  background-color: var(--border);
}

.console-tabs :deep(.el-tabs__item) {
  color: var(--text-muted);
  font-weight: 600;
  font-size: var(--text-sm);
}

.console-tabs :deep(.el-tabs__item.is-active) {
  color: var(--accent);
}

.console-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--accent);
}
</style>
