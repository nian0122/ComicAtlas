<template>
  <div class="preview-node">
    <div class="preview-node-row">
      <span class="preview-node-icon" aria-hidden="true">{{ kindIcon }}</span>
      <span class="preview-node-name" :title="node.relativePath">{{ node.name }}</span>
      <span class="preview-node-count">{{ node.fileCount }} 个媒体</span>
      <ScanWarningChip v-for="warning in node.warnings ?? []" :key="warning.code" :severity="warning.severity">
        {{ warning.message }}
      </ScanWarningChip>
    </div>
    <div v-if="(node.children ?? []).length > 0" class="preview-node-children">
      <PreviewNode v-for="child in node.children" :key="child.relativePath" :node="child" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ScanNodeKind, ScanPreviewNodeVO } from '../model/types'
import ScanWarningChip from './ScanWarningChip.vue'

const props = defineProps<{ node: ScanPreviewNodeVO }>()

const kindIcons: Record<ScanNodeKind, string> = {
  COMIC: '◈',
  ARCHIVE: '▤',
  DIRECTORY: '▸',
}

const kindIcon = computed(() => kindIcons[props.node.kind] ?? kindIcons.DIRECTORY)
</script>

<style scoped>
.preview-node-children {
  margin-left: var(--space-md);
  padding-left: var(--space-sm);
  border-left: 1px solid var(--border);
}

.preview-node-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  min-width: 0;
  padding: 2px 0;
  font-size: 12px;
  color: var(--text-secondary);
}

.preview-node-icon {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 12px;
}

.preview-node-name {
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-node-count {
  flex-shrink: 0;
  color: var(--text-muted);
  margin-left: auto;
}

@media (max-width: 640px) {
  .preview-node-count {
    display: none;
  }
}
</style>
