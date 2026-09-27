<template>
  <div class="media-reactions-page">
    <PageHeader title="媒体标记" description="集中查看短视频阅读中的喜欢与不喜欢媒体。" eyebrow="MEDIA / REACTIONS">
      <AppButton :loading="loading" @click="loadItems">刷新列表</AppButton>
    </PageHeader>

    <ManagementPanel class="filter-panel">
      <div class="filter-row">
        <div class="filter-group">
          <span class="filter-label">标记</span>
          <button
            v-for="option in reactionOptions"
            :key="option.value || 'all'"
            type="button"
            class="filter-chip"
            :class="{ active: reactionFilter === option.value }"
            @click="reactionFilter = option.value"
          >
            {{ option.label }}
          </button>
        </div>
        <div class="filter-group">
          <span class="filter-label">类型</span>
          <button
            v-for="option in mediaTypeOptions"
            :key="option.value || 'all'"
            type="button"
            class="filter-chip"
            :class="{ active: mediaTypeFilter === option.value }"
            @click="mediaTypeFilter = option.value"
          >
            {{ option.label }}
          </button>
        </div>
        <label class="trash-toggle">
          <input v-model="includeTrashed" type="checkbox" />
          <span>包含回收站媒体</span>
        </label>
      </div>
    </ManagementPanel>

    <ManagementPanel class="list-panel">
      <div class="list-toolbar">
        <div>
          <strong>{{ items.length }} 个媒体</strong>
          <span v-if="selectedIds.length" class="selected-count">已选 {{ selectedIds.length }} 个</span>
        </div>
        <div class="batch-actions">
          <AppButton :disabled="!selectedIds.length || batchLoading" @click="updateSelected('LIKE')"
            >设为喜欢</AppButton
          >
          <AppButton :disabled="!selectedIds.length || batchLoading" @click="updateSelected('DISLIKE')"
            >设为不喜欢</AppButton
          >
          <AppButton :disabled="!selectedIds.length || batchLoading" @click="updateSelected('NONE')"
            >取消标记</AppButton
          >
          <AppButton class="danger-button" :disabled="!selectedIds.length || batchLoading" @click="trashSelected">
            送入回收站
          </AppButton>
        </div>
      </div>

      <div v-if="errorMessage" class="error-banner" role="alert">{{ errorMessage }}</div>
      <ContentState v-if="!loading && !items.length" state="empty" message="暂无符合条件的媒体标记" />
      <div v-else class="reaction-table-wrap">
        <table class="reaction-table">
          <thead>
            <tr>
              <th class="check-cell"><input v-model="allSelected" type="checkbox" aria-label="全选" /></th>
              <th>媒体</th>
              <th>类型</th>
              <th>标记</th>
              <th>最近标记</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id">
              <td class="check-cell"><input v-model="selectedIds" type="checkbox" :value="item.id" /></td>
              <td>
                <div class="media-cell">
                  <span class="media-id">#{{ item.id }}</span>
                  <span>章节 {{ item.chapterId }} · 第 {{ item.pageNumber }} 页</span>
                </div>
              </td>
              <td>
                <span class="type-badge">{{ item.mediaType === 'VIDEO' ? '视频' : '图片' }}</span>
              </td>
              <td>
                <span class="reaction-badge" :class="item.reaction.toLowerCase()">
                  <span class="reaction-dot" aria-hidden="true" />
                  {{ reactionLabel(item.reaction) }}
                </span>
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { AppButton } from '@/shared/ui/button'
import { ContentState } from '@/shared/ui/content-state'
import { ManagementPanel } from '@/shared/ui/management-panel'
import { PageHeader } from '@/shared/ui/page-header'
import { getApiErrorMessage } from '@/shared/api/http'
import { mediaReactionApi, type MediaReaction, type MediaReactionVO } from '@/entities/media'

const reactionFilter = ref<'' | 'LIKE' | 'DISLIKE'>('')
const mediaTypeFilter = ref<'' | 'IMAGE' | 'VIDEO'>('')
const includeTrashed = ref(false)
const items = ref<MediaReactionVO[]>([])
const selectedIds = ref<number[]>([])
const loading = ref(false)
const batchLoading = ref(false)
const errorMessage = ref('')

const reactionOptions = [
  { value: '', label: '全部标记' },
  { value: 'LIKE' as const, label: '喜欢' },
  { value: 'DISLIKE' as const, label: '不喜欢' },
] as const
const mediaTypeOptions = [
  { value: '', label: '全部类型' },
  { value: 'VIDEO' as const, label: '视频' },
  { value: 'IMAGE' as const, label: '图片' },
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
    const response = await mediaReactionApi.list({
      reaction: reactionFilter.value || undefined,
      mediaType: mediaTypeFilter.value || undefined,
      includeTrashed: includeTrashed.value,
    })
    items.value = response.data
    selectedIds.value = selectedIds.value.filter((id) => items.value.some((item) => item.id === id))
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error, '加载媒体标记失败')
  } finally {
    loading.value = false
  }
}

async function updateSelected(reaction: MediaReaction): Promise<void> {
  if (!selectedIds.value.length) return
  batchLoading.value = true
  errorMessage.value = ''
  try {
    await mediaReactionApi.updateBatch(selectedIds.value, reaction)
    await loadItems()
    selectedIds.value = []
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error, '批量更新标记失败')
  } finally {
    batchLoading.value = false
  }
}

async function trashSelected(): Promise<void> {
  if (!selectedIds.value.length || !window.confirm(`确认将选中的 ${selectedIds.value.length} 个媒体送入回收站吗？`))
    return
  batchLoading.value = true
  errorMessage.value = ''
  try {
    await mediaReactionApi.trashBatch(selectedIds.value)
    await loadItems()
    selectedIds.value = []
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error, '批量回收媒体失败')
  } finally {
    batchLoading.value = false
  }
}

function reactionLabel(reaction: MediaReaction): string {
  return reaction === 'LIKE' ? '喜欢' : reaction === 'DISLIKE' ? '不喜欢' : '未标记'
}

function statusLabel(status: string | null): string {
  return status === 'TRASHED' ? '回收站' : status === 'DELETED' ? '已删除' : '正常'
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
}

watch([reactionFilter, mediaTypeFilter, includeTrashed], loadItems)
onMounted(loadItems)
</script>

<style scoped>
.media-reactions-page {
  display: grid;
  gap: 18px;
}
.filter-panel,
.list-panel {
  padding: 20px;
}
.filter-row,
.list-toolbar,
.batch-actions,
.filter-group {
  display: flex;
  align-items: center;
  gap: 10px;
}
.filter-row {
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px 28px;
}
.filter-group {
  flex-wrap: wrap;
}
.filter-label {
  color: var(--text-muted, #7c7b86);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.filter-chip {
  padding: 7px 12px;
  border: 1px solid var(--border-subtle, #e7e5ec);
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary, #65636f);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  transition: 0.18s ease;
}
.filter-chip:hover {
  border-color: var(--accent, #7462e8);
  color: var(--accent, #7462e8);
}
.filter-chip.active {
  border-color: var(--accent, #7462e8);
  background: var(--accent-soft, #f0edff);
  color: var(--accent, #5c4acc);
  font-weight: 700;
}
.trash-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary, #65636f);
  font-size: 13px;
  cursor: pointer;
}
.trash-toggle input,
.check-cell input {
  accent-color: var(--accent, #7462e8);
}
.list-toolbar {
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-subtle, #eceaf0);
}
.list-toolbar strong {
  font-size: 15px;
}
.selected-count {
  margin-left: 10px;
  color: var(--accent, #5c4acc);
  font-size: 13px;
}
.danger-button {
  color: #c53b57;
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
  padding: 14px 10px;
  border-bottom: 1px solid var(--border-subtle, #efedf2);
  text-align: left;
  font-size: 13px;
}
.reaction-table th {
  color: var(--text-muted, #85828e);
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.check-cell {
  width: 42px;
  text-align: center !important;
}
.media-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  color: var(--text-secondary, #65636f);
}
.media-id {
  color: var(--text-primary, #272532);
  font-weight: 700;
}
.type-badge,
.status-text {
  color: var(--text-muted, #777481);
}
.reaction-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-weight: 700;
}
.reaction-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #aaa;
}
.reaction-badge.like {
  color: #db4966;
}
.reaction-badge.like .reaction-dot {
  background: #e95170;
  box-shadow: 0 0 0 4px rgb(233 81 112 / 12%);
}
.reaction-badge.dislike {
  color: #5579a9;
}
.reaction-badge.dislike .reaction-dot {
  background: #668ab8;
  box-shadow: 0 0 0 4px rgb(102 138 184 / 12%);
}
.date-cell {
  color: var(--text-secondary, #65636f);
  white-space: nowrap;
}
.error-banner {
  margin: 14px 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff0f2;
  color: #b7334f;
  font-size: 13px;
}
@media (max-width: 760px) {
  .filter-panel,
  .list-panel {
    padding: 15px;
  }
  .batch-actions {
    width: 100%;
    overflow-x: auto;
    padding-bottom: 2px;
  }
  .batch-actions :deep(button) {
    flex: 0 0 auto;
  }
}
</style>
