<template>
  <section class="tasks-tab" data-testid="tasks-tab">
    <h2 class="tasks-title">任务</h2>

    <div v-if="!activeTask" class="tasks-empty" data-testid="tasks-empty">
      当前无活跃任务。
    </div>

    <div v-else class="tasks-card" data-testid="tasks-active">
      <div class="task-line">
        <span class="task-type">{{ activeTask.taskType.value }}</span>
        <span class="task-pct" data-testid="tasks-progress">{{ activeTask.progress }}%</span>
      </div>
      <div
        class="task-progress-track"
        role="progressbar"
        :aria-valuenow="activeTask.progress"
        aria-valuemin="0"
        aria-valuemax="100"
      >
        <div class="task-progress-fill" :style="{ width: `${activeTask.progress}%` }" />
      </div>
      <p v-if="activeTask.errorMessage" class="task-error">{{ activeTask.errorMessage }}</p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useComicWorkspaceStore } from '@/stores/management/workspace'

const store = useComicWorkspaceStore()
const activeTask = computed(() => store.activeTask)
</script>

<style scoped>
.tasks-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 560px;
  min-width: 0;
}

.tasks-title {
  margin: 0;
  font-size: var(--text-section);
  font-weight: 700;
  color: var(--text-primary);
}

.tasks-empty {
  padding: var(--space-8);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px dashed var(--border-strong);
  color: var(--text-muted);
  font-size: var(--text-sm);
}

.tasks-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-5);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px solid var(--border);
}

.task-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.task-type {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
}

.task-pct {
  font-size: var(--text-sm);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}

.task-error {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--danger);
  word-break: break-word;
  overflow-wrap: anywhere;
}
</style>
