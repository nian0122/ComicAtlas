<template>
  <ManagementPanel class="content-panel">
    <div class="filter-row">
      <div class="filter-group">
        <span class="filter-label">标记</span>
        <AppButton
          v-for="option in reactionOptions"
          :key="option.value || 'all'"
          size="sm"
          :variant="reactionFilter === option.value ? 'primary' : 'ghost'"
          :aria-pressed="reactionFilter === option.value"
          @click="reactionFilter = option.value"
        >
          {{ option.label }}
        </AppButton>
      </div>
      <label class="trash-toggle"><input v-model="includeTrashed" type="checkbox" /><span>包含回收站</span></label>
    </div>
    <div class="list-toolbar">
      <strong>{{ items.length }} 个{{ targetType === 'COMIC' ? '漫画' : '章节' }}</strong>
      <span v-if="selectedIds.length" class="selected-count">已选 {{ selectedIds.length }} 个</span>
      <div class="batch-actions">
        <AppButton :disabled="!selectedIds.length || loading" @click="updateSelected('LIKE')">设为喜欢</AppButton>
        <AppButton :disabled="!selectedIds.length || loading" @click="updateSelected('DISLIKE')">设为不喜欢</AppButton>
        <AppButton :disabled="!selectedIds.length || loading" @click="updateSelected('NONE')">取消标记</AppButton>
        <AppButton variant="danger" :disabled="!selectedIds.length || loading" @click="trashSelected"
          >送入回收站</AppButton
        >
      </div>
    </div>
    <div v-if="errorMessage" class="error-banner" role="alert">{{ errorMessage }}</div>
    <ContentState v-if="!loading && !items.length" state="empty" message="暂无符合条件的标记" />
    <div v-else class="reaction-table-wrap">
      <table class="reaction-table">
        <thead>
          <tr>
            <th class="check-cell"><input v-model="allSelected" type="checkbox" aria-label="全选" /></th>
            <th>名称</th>
            <th>标记</th>
            <th>最近标记</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td class="check-cell"><input v-model="selectedIds" type="checkbox" :value="item.id" /></td>
            <td>
              <span class="media-id">#{{ item.id }}</span> {{ item.title || '未命名'
              }}<span v-if="targetType === 'CHAPTER'" class="secondary"> · 漫画 {{ item.comicId }}</span>
            </td>
            <td>
              <span class="reaction-badge" :class="item.reaction.toLowerCase()"
                ><span class="reaction-dot" />{{ reactionLabel(item.reaction) }}</span
              >
            </td>
            <td class="date-cell">{{ formatDate(item.reactionAt) }}</td>
            <td>
              <span class="status-text">{{ statusLabel(item.status) }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </ManagementPanel>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { AppButton } from '@/shared/ui/button'
import { ContentState } from '@/shared/ui/content-state'
import { ManagementPanel } from '@/shared/ui/management-panel'
import { getApiErrorMessage } from '@/shared/api/http'
import { contentReactionApi, type ContentReactionTarget, type ContentReactionVO } from '../api/content-reaction-api'
import type { MediaReaction } from '../types'

const props = defineProps<{ targetType: ContentReactionTarget }>()
const reactionFilter = ref<'' | 'LIKE' | 'DISLIKE'>('')
const includeTrashed = ref(false)
const items = ref<ContentReactionVO[]>([])
const selectedIds = ref<number[]>([])
const loading = ref(false)
const errorMessage = ref('')
const reactionOptions = [
  { value: '', label: '全部标记' },
  { value: 'LIKE' as const, label: '喜欢' },
  { value: 'DISLIKE' as const, label: '不喜欢' },
] as const
const allSelected = computed({
  get: () => items.value.length > 0 && selectedIds.value.length === items.value.length,
  set: (value: boolean) => {
    selectedIds.value = value ? items.value.map((item) => item.id) : []
  },
})

async function loadItems(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await contentReactionApi.list(props.targetType, {
      reaction: reactionFilter.value || undefined,
      includeTrashed: includeTrashed.value,
    })
    items.value = response.data
    selectedIds.value = []
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error, '加载标记失败')
  } finally {
    loading.value = false
  }
}
async function updateSelected(reaction: MediaReaction): Promise<void> {
  if (!selectedIds.value.length) return
  loading.value = true
  try {
    await contentReactionApi.updateBatch(props.targetType, selectedIds.value, reaction)
    await loadItems()
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error, '批量更新标记失败')
  } finally {
    loading.value = false
  }
}
async function trashSelected(): Promise<void> {
  if (
    !selectedIds.value.length ||
    !window.confirm(
      `确认将选中的 ${selectedIds.value.length} 个${props.targetType === 'COMIC' ? '漫画' : '章节'}送入回收站吗？`,
    )
  )
    return
  const ids = [...selectedIds.value]
  loading.value = true
  try {
    await contentReactionApi.trashBatch(props.targetType, ids)
    items.value = items.value.filter((item) => !ids.includes(item.id))
    selectedIds.value = []
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error, '批量回收失败')
  } finally {
    loading.value = false
  }
}
function reactionLabel(reaction: MediaReaction): string {
  return reaction === 'LIKE' ? '喜欢' : '不喜欢'
}
function statusLabel(status: string | null): string {
  return status === 'TRASHED' ? '回收站' : status === 'DELETED' ? '已删除' : '正常'
}
function formatDate(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }) : '—'
}
watch([reactionFilter, includeTrashed, () => props.targetType], loadItems)
onMounted(loadItems)
</script>

<style scoped>
.content-panel {
  padding: 20px;
  display: grid;
  gap: 16px;
}
.filter-row,
.filter-group,
.list-toolbar,
.batch-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.filter-row,
.list-toolbar {
  justify-content: space-between;
  flex-wrap: wrap;
}
.filter-group {
  flex-wrap: wrap;
}
.filter-label {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
.reaction-table-wrap {
  overflow-x: auto;
}
.reaction-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 680px;
}
.reaction-table th,
.reaction-table td {
  padding: 13px 10px;
  border-bottom: 1px solid var(--border);
  text-align: left;
}
.reaction-table th {
  color: var(--text-muted);
  font-size: 12px;
}
.check-cell {
  width: 42px;
}
.media-id {
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.secondary {
  color: var(--text-muted);
  font-size: 12px;
}
.reaction-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.reaction-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-muted);
}
.reaction-badge.like .reaction-dot {
  background: var(--accent);
}
.reaction-badge.dislike .reaction-dot {
  background: var(--danger);
}
.date-cell {
  color: var(--text-muted);
  font-size: 13px;
  white-space: nowrap;
}
.status-text {
  color: var(--text-secondary);
  font-size: 13px;
}
.selected-count {
  color: var(--text-muted);
  font-size: 13px;
}
.error-banner {
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--danger-bg);
  color: var(--danger);
}
</style>
