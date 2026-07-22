<script setup lang="ts">
defineProps<{
  count: number
}>()

const emit = defineEmits<{
  deleteHq: []
  generateLq: []
}>()
</script>

<template>
  <div class="batch-bar">
    <span>已选 {{ count }} 部</span>
    <el-button type="danger" @click="emit('deleteHq')">删除选中 HQ</el-button>
    <el-button type="primary" @click="emit('generateLq')">生成选中 LQ</el-button>
  </div>
</template>

<style scoped>
.batch-bar {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  padding: var(--space-base) 0;
  border-top: 1px solid var(--border);
  margin-top: var(--space-base);
}
</style>
