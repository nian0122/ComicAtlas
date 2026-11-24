<script setup lang="ts">
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
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
  <div v-if="rows.length" class="mw-batch-items">
    <DynamicScroller
      :items="rows"
      key-field="key"
      :min-item-size="76"
      page-mode
      list-tag="ul"
      item-tag="li"
      list-class="mw-batch-list"
    >
      <template #default="{ item: row, index, active }">
        <DynamicScrollerItem class="mw-batch-row" :item="row" :active="active" :index="index">
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
        </DynamicScrollerItem>
      </template>
    </DynamicScroller>
  </div>
  <button v-if="rows.length < total" type="button" class="mw-button" :disabled="loadingMore" @click="emit('more')">
    加载更多清单（{{ rows.length }} / {{ total }}）
  </button>
</template>

<style scoped>
.mw-batch-items {
  margin: var(--atlas-space-4) 0 var(--atlas-space-2);
  max-height: 280px;
  overflow-y: auto;
}
.mw-batch-items :deep(.mw-batch-list) {
  list-style: none;
  margin: 0;
  padding: 0;
}
.mw-batch-row {
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
