<template>
  <section class="overview-tab" data-testid="overview-tab">
    <div v-if="loadError" class="ov-error" data-testid="overview-error" role="alert">
      {{ loadError }}
    </div>

    <div v-else-if="!loaded" class="ws-state">
      <div class="action-btn-spinner" aria-hidden="true" />
      <span>加载中…</span>
    </div>

    <template v-else>
      <!-- 元数据编辑 -->
      <div class="ov-card">
        <h2 class="ov-section-title">元数据</h2>

        <div class="ov-field">
          <label class="ov-label" for="ov-title">标题</label>
          <input
            id="ov-title"
            v-model="form.title"
            class="ov-input"
            data-testid="overview-title-input"
            :disabled="!canEdit"
            maxlength="255"
            placeholder="输入漫画标题"
          />
        </div>

        <div class="ov-field">
          <label class="ov-label" for="ov-author">作者</label>
          <input
            id="ov-author"
            v-model="form.author"
            class="ov-input"
            data-testid="overview-author-input"
            :disabled="!canEdit"
            maxlength="128"
            placeholder="输入作者名（可选）"
          />
        </div>

        <div class="ov-field">
          <label class="ov-label" for="ov-desc">描述</label>
          <textarea
            id="ov-desc"
            v-model="form.description"
            class="ov-input ov-input--area"
            data-testid="overview-desc-input"
            :disabled="!canEdit"
            rows="3"
            maxlength="4000"
            placeholder="输入漫画描述（可选）"
          />
        </div>

        <div class="ov-field" data-testid="overview-category">
          <label class="ov-label" for="ov-category">分类</label>
          <select
            id="ov-category"
            v-model="form.categoryId"
            class="ov-input ov-select"
            :disabled="!canEdit"
          >
            <option :value="null">未分类</option>
            <option v-for="cat in categories" :key="cat.id" :value="cat.id">{{ cat.name }}</option>
          </select>
          <span class="ov-current">{{ currentCategoryName }}</span>
        </div>

        <div class="ov-field" data-testid="overview-tags">
          <label class="ov-label">标签</label>
          <div class="ov-tags">
            <span v-if="selectedTags.length === 0" class="ov-empty">无标签</span>
            <span v-for="tag in selectedTags" :key="tag.id" class="ov-tag">{{ tag.name }}</span>
          </div>
        </div>

        <p v-if="!canEdit" class="ov-blocked" data-testid="overview-blocked-EDIT">
          {{ editBlockedReason }}
        </p>

        <div class="ov-actions">
          <button
            class="action-btn action-btn--primary"
            type="button"
            data-testid="overview-save"
            :disabled="!canEdit || saving"
            @click="save"
          >
            <span v-if="saving" class="action-btn-spinner" aria-hidden="true" />
            <span>{{ saving ? '保存中…' : '保存' }}</span>
          </button>
          <span v-if="savedOk" class="ov-saved-ok" data-testid="overview-save-ok" role="status">
            <el-icon :size="14"><Check /></el-icon>已保存
          </span>
          <span v-if="saveError" class="ov-error-inline" data-testid="overview-error" role="alert">
            {{ saveError }}
          </span>
        </div>
      </div>

      <!-- 存储摘要 -->
      <div class="ov-card">
        <h2 class="ov-section-title">存储摘要</h2>
        <div class="ov-storage-grid">
          <div class="ov-storage-item">
            <span class="ov-label">HQ</span>
            <span class="ov-value" data-testid="ov-storage-hq">{{ formatBytes(storage?.hqSize ?? 0) }}</span>
          </div>
          <div class="ov-storage-item">
            <span class="ov-label">LQ</span>
            <span class="ov-value" data-testid="ov-storage-lq">{{ formatBytes(storage?.lqSize ?? 0) }}</span>
          </div>
          <div class="ov-storage-item">
            <span class="ov-label">总文件数</span>
            <span class="ov-value">{{ storage?.pageCount ?? 0 }}</span>
          </div>
          <div class="ov-storage-item">
            <span class="ov-label">章节数</span>
            <span class="ov-value">{{ storage?.chapterCount ?? 0 }}</span>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Check } from '@element-plus/icons-vue'
import { useComicWorkspaceStore } from '@/stores/management/workspace'
import { workspaceApi, workspaceErrorMessage } from '@/services/management/workspace'
import { useCategoryStore } from '@/stores/management/category'
import { tagApi } from '@/services/management'
import { OperationName } from '@/types/management/enums'
import type { TagDTO } from '@/types'
import type { ComicStorageItem } from '@/types'
import { storageService } from '@/services/storage'

const props = defineProps<{ readonly comicId: number }>()

const store = useComicWorkspaceStore()
const categoryStore = useCategoryStore()

const loaded = ref(false)
const loadError = ref<string | null>(null)
const saving = ref(false)
const savedOk = ref(false)
const saveError = ref<string | null>(null)
const storage = ref<ComicStorageItem | null>(null)

const form = ref({ title: '', author: '', description: '', categoryId: null as number | null })
const selectedTagIds = ref<readonly number[]>([])
const allTags = ref<readonly TagDTO[]>([])

const categories = computed(() => categoryStore.list)

const canEdit = computed(() => store.can(OperationName.EDIT))
const editBlockedReason = computed(() => store.blockedReason(OperationName.EDIT) ?? '当前状态不允许编辑')

const currentCategoryName = computed(() => {
  const cat = categories.value.find((c) => c.id === form.value.categoryId)
  return cat ? cat.name : '未分类'
})

const selectedTags = computed(() =>
  selectedTagIds.value
    .map((id) => allTags.value.find((t) => t.id === id))
    .filter((t): t is TagDTO => !!t)
)

async function load(): Promise<void> {
  loadError.value = null
  try {
    const [metadata, tagIds, categoryList, storageData] = await Promise.all([
      workspaceApi.metadata(props.comicId),
      workspaceApi.tags(props.comicId),
      categoryStore.fetchList(),
      storageService.fetchComic(props.comicId).catch(() => null),
    ])
    form.value = {
      title: metadata.title ?? '',
      author: metadata.author ?? '',
      description: metadata.description ?? '',
      categoryId: metadata.categoryId ?? null,
    }
    selectedTagIds.value = tagIds
    storage.value = storageData
    // 分类列表由 categoryStore 持有
    void categoryList
    // 标签全量（编辑用）
    try {
      const res = await tagApi.list()
      allTags.value = (res.data as TagDTO[]) || []
    } catch {
      allTags.value = []
    }
    loaded.value = true
  } catch (err: unknown) {
    loadError.value = workspaceErrorMessage(err, '加载概览失败')
  }
}

async function save(): Promise<void> {
  saving.value = true
  savedOk.value = false
  saveError.value = null
  try {
    await Promise.all([
      workspaceApi.updateMetadata(props.comicId, {
        title: form.value.title.trim(),
        author: form.value.author?.trim() || '',
        description: form.value.description?.trim() || '',
        categoryId: form.value.categoryId,
      }),
      workspaceApi.updateTags(props.comicId, { tagIds: selectedTagIds.value }),
    ])
    savedOk.value = true
  } catch (err: unknown) {
    saveError.value = workspaceErrorMessage(err, '保存失败')
  } finally {
    saving.value = false
  }
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  const rounded = Math.round(size * 10) / 10
  const text = Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)
  return `${text} ${units[i]}`
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.overview-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  max-width: 760px;
  min-width: 0;
}

.ov-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-6);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px solid var(--border);
}

.ov-section-title {
  margin: 0;
  font-size: var(--text-md);
  font-weight: 700;
  color: var(--text-primary);
}

.ov-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
}

.ov-label {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-muted);
}

.ov-input {
  display: block;
  width: 100%;
  box-sizing: border-box;
  min-height: var(--control-min-size);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  font-family: var(--font-ui);
  font-size: var(--text-sm);
  outline: none;
  transition: border-color var(--transition-fast);
}

.ov-input:focus {
  border-color: var(--accent);
}

.ov-input:disabled {
  opacity: var(--disabled-opacity);
  cursor: not-allowed;
}

.ov-input--area {
  resize: vertical;
  min-height: 72px;
}

.ov-select {
  appearance: none;
  cursor: pointer;
}

.ov-current {
  font-size: var(--text-xs);
  color: var(--text-secondary);
}

.ov-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  min-height: var(--control-min-size);
  padding: var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  border: 1px solid var(--border);
}

.ov-tag {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--accent-bg);
  color: var(--text-primary);
  font-size: var(--text-xs);
  font-weight: 600;
}

.ov-empty {
  color: var(--text-muted);
  font-size: var(--text-xs);
}

.ov-blocked {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--warning);
}

.ov-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.ov-saved-ok {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--success);
  font-size: var(--text-sm);
  font-weight: 600;
}

.ov-error-inline {
  color: var(--danger);
  font-size: var(--text-sm);
}

.ov-error {
  padding: var(--space-4);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px solid var(--danger);
  color: var(--danger);
  font-size: var(--text-sm);
}

.ov-storage-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: var(--space-4);
}

.ov-storage-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.ov-value {
  font-size: var(--text-lg);
  font-weight: 700;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.ws-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  padding: var(--space-16) 0;
  color: var(--text-secondary);
  font-size: var(--text-sm);
}
</style>
