<script setup lang="ts">
import ManagementImage from './ManagementImage.vue'
interface Row {
  key: string | number
  title: string
  cover?: string | null
  meta?: string
  status?: string
  reason?: string
  errorCode?: string
}
defineProps<{ rows: Row[]; total: number; loadingMore?: boolean }>()
const emit = defineEmits<{ more: [] }>()
</script>

<template>
  <ul v-if="rows.length" class="mw-batch-items">
    <li v-for="row in rows" :key="row.key">
      <ManagementImage :src="row.cover ?? null" :alt="row.title" />
      <div class="mw-batch-copy">
        <strong>{{ row.title }}</strong>
        <p v-if="row.meta" class="mw-muted">{{ row.meta }}</p>
        <p v-if="row.reason" class="mw-batch-reason">{{ row.reason }}</p>
        <details v-if="row.errorCode">
          <summary>诊断详情</summary>
          <code>{{ row.errorCode }}</code>
        </details>
      </div>
      <span v-if="row.status" class="mw-batch-status">{{ row.status }}</span>
    </li>
  </ul>
  <button v-if="rows.length < total" type="button" class="mw-button" :disabled="loadingMore" @click="emit('more')">
    加载更多清单（{{ rows.length }} / {{ total }}）
  </button>
</template>

<style scoped>
.mw-batch-items {
  list-style: none;
  margin: var(--atlas-space-4) 0 var(--atlas-space-2);
  padding: 0;
  max-height: 280px;
  overflow-y: auto;
}
.mw-batch-items li {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  gap: var(--atlas-space-3);
  align-items: center;
  padding: var(--atlas-space-3) 0;
  border-bottom: 1px solid var(--atlas-line);
}
.mw-batch-items :deep(img),
.mw-batch-items :deep(.mw-image-empty) {
  width: 40px;
  height: 52px;
  object-fit: contain;
  border-radius: 4px;
}
.mw-batch-copy {
  min-width: 0;
}
.mw-batch-copy strong {
  display: block;
  font-size: 13px;
  font-weight: 400;
  overflow-wrap: anywhere;
}
.mw-batch-copy p {
  margin: var(--atlas-space-1) 0 0;
  font-size: 12px;
}
.mw-batch-reason {
  color: var(--atlas-warning);
}
.mw-batch-status {
  color: var(--atlas-muted);
  font-size: 12px;
  white-space: nowrap;
}
.mw-batch-copy details summary {
  margin-top: var(--atlas-space-1);
  color: var(--atlas-muted);
  font-size: 11px;
  cursor: pointer;
}
.mw-batch-copy code {
  font-size: 10px;
  color: var(--atlas-warning);
}
</style>
