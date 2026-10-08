<template>
  <div class="metadata-page">
    <PageHeader spaced title="元数据管理" />

    <StatGrid spaced class="metadata-summary" aria-label="元数据统计" :columns="3">
      <StatCard label="分类" :value="categoryStore.list.length" description="可用于仓库筛选" />
      <StatCard label="标签" :value="tagStore.list.length" description="用于漫画检索与归档" />
      <StatCard
        label="维护状态"
        :value="metadataStatusLabel"
        :description="metadataStatusHint"
        :tone="metadataHealthy ? 'success' : 'danger'"
      />
    </StatGrid>

    <el-tabs v-model="activeTab" class="metadata-tabs">
      <el-tab-pane label="分类" name="category">
        <div class="tab-toolbar">
          <el-input
            v-model="newCategoryName"
            placeholder="新分类名称"
            class="metadata-input"
            @keyup.enter="onCreateCategory"
          />
          <AppButton variant="primary" :loading="categoryStore.loading" @click="onCreateCategory">添加分类</AppButton>
        </div>

        <el-table v-loading="categoryStore.loading" :data="categoryStore.list" style="width: 100%">
          <el-table-column prop="name" label="名称" />
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <AppButton variant="text" @click="startEditCategory(row)">编辑</AppButton>
              <AppButton variant="text" @click="onDeleteCategory(row.id)">删除</AppButton>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="标签" name="tag">
        <ManagementPanel class="tag-workspace" aria-label="标签词库管理">
          <div class="tag-toolbar">
            <div class="tag-total" aria-label="标签总数">
              <strong>{{ tagStore.list.length }}</strong>
              <span>个标签</span>
            </div>

            <form class="tag-create" @submit.prevent="onCreateTag">
              <el-input v-model="newTagName" placeholder="输入标签名称" class="metadata-input" />
              <AppButton type="submit" variant="primary" :loading="tagStore.loading">添加标签</AppButton>
            </form>
          </div>

          <div class="tag-tools">
            <el-input v-model="tagSearch" clearable placeholder="搜索标签" class="tag-search" aria-label="搜索标签" />
          </div>

          <div v-if="filteredTags.length" class="tag-list">
            <article v-for="tag in filteredTags" :key="tag.id" class="tag-card">
              <span class="tag-card__name" :title="tag.name">{{ tag.name }}</span>
              <div class="tag-card__actions">
                <AppButton
                  variant="text"
                  class="tag-action"
                  :aria-label="`编辑标签 ${tag.name}`"
                  @click="startEditTag(tag)"
                >
                  编辑
                </AppButton>
                <span class="tag-card__divider" aria-hidden="true"></span>
                <AppButton
                  variant="text"
                  class="tag-action tag-action--delete"
                  :aria-label="`删除标签 ${tag.name}`"
                  @click="onDeleteTag(tag)"
                >
                  删除
                </AppButton>
              </div>
            </article>
          </div>
          <div v-else class="tag-empty">
            <span class="tag-empty__mark" aria-hidden="true">#</span>
            <strong>{{ tagSearch ? '没有找到匹配的标签' : '还没有标签' }}</strong>
            <span>{{ tagSearch ? '试试其他关键词' : '添加标签后，会显示在这里' }}</span>
          </div>
        </ManagementPanel>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="categoryEditVisible" title="编辑分类" width="400px">
      <el-input v-model="editCategoryName" placeholder="分类名称" />
      <template #footer>
        <AppButton variant="secondary" @click="categoryEditVisible = false">取消</AppButton>
        <AppButton variant="primary" @click="onUpdateCategory">保存</AppButton>
      </template>
    </el-dialog>

    <el-dialog v-model="tagEditVisible" title="编辑标签" width="400px">
      <el-input v-model="editTagName" placeholder="标签名称" @keyup.enter="onUpdateTag" />
      <template #footer>
        <AppButton variant="secondary" @click="tagEditVisible = false">取消</AppButton>
        <AppButton variant="primary" :loading="tagStore.loading" @click="onUpdateTag">保存</AppButton>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { ManagementPanel, StatGrid } from '@/shared/ui/management-panel'
import { StatCard } from '@/shared/ui/management-panel'
import { PageHeader } from '@/shared/ui/page-header'
import { computed, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getApiErrorMessage } from '@/shared/api/http'
import { useCategoryStore } from '@/entities/category'
import { useTagStore } from '@/entities/tag'
import type { CategoryDTO } from '@/entities/category'
import type { TagDTO } from '@/entities/tag'

const activeTab = ref('category')
const categoryStore = useCategoryStore()
const tagStore = useTagStore()

const newCategoryName = ref('')
const categoryEditVisible = ref(false)
const editCategoryId = ref<number | null>(null)
const editCategoryName = ref('')

const newTagName = ref('')
const tagSearch = ref('')
const tagEditVisible = ref(false)
const editTagId = ref<number | null>(null)
const editTagName = ref('')
const metadataHealthy = computed(() => !categoryStore.error && !tagStore.error)
const metadataStatusLabel = computed(() => (metadataHealthy.value ? '正常' : '接口异常'))
const metadataStatusHint = computed(() => {
  if (metadataHealthy.value) return '接口同步可用'
  return categoryStore.error || tagStore.error || '请稍后重试'
})
const filteredTags = computed(() => {
  const keyword = tagSearch.value.trim().toLocaleLowerCase()
  if (!keyword) return tagStore.list
  return tagStore.list.filter((tag) => tag.name.toLocaleLowerCase().includes(keyword))
})

onMounted(() => {
  categoryStore.fetchList()
  tagStore.fetchList()
})

async function onCreateCategory() {
  const name = newCategoryName.value.trim()
  if (!name) return
  try {
    await categoryStore.create(name)
    ElMessage.success('分类已添加')
    newCategoryName.value = ''
  } catch (err: unknown) {
    ElMessage.error(getApiErrorMessage(err, '添加分类失败'))
  }
}

function startEditCategory(row: CategoryDTO | null | undefined) {
  if (!row) return
  editCategoryId.value = row.id
  editCategoryName.value = row.name
  categoryEditVisible.value = true
}

async function onUpdateCategory() {
  if (!editCategoryId.value) return
  const name = editCategoryName.value.trim()
  if (!name) return
  try {
    await categoryStore.update(editCategoryId.value, name)
    ElMessage.success('分类已更新')
    categoryEditVisible.value = false
  } catch (err: unknown) {
    ElMessage.error(getApiErrorMessage(err, '更新分类失败'))
  }
}

async function onDeleteCategory(id: number | null | undefined) {
  if (id == null) return
  try {
    await ElMessageBox.confirm('确定删除该分类？', '删除分类', { type: 'warning' })
    await categoryStore.remove(id)
    ElMessage.success('分类已删除')
  } catch (err: unknown) {
    if (err === 'cancel') return
    ElMessage.error(getApiErrorMessage(err, '删除分类失败'))
  }
}

async function onCreateTag() {
  const name = newTagName.value.trim()
  if (!name) return
  try {
    await tagStore.create(name)
    ElMessage.success('标签已添加')
    newTagName.value = ''
  } catch (err: unknown) {
    ElMessage.error(getApiErrorMessage(err, '添加标签失败'))
  }
}

async function onDeleteTag(tag: TagDTO | null | undefined) {
  if (!tag || tag.id == null) return
  try {
    await ElMessageBox.confirm('确定删除该标签？', '删除标签', { type: 'warning' })
    await tagStore.delete(tag.id)
    ElMessage.success('标签已删除')
  } catch (err: unknown) {
    if (err === 'cancel') return
    ElMessage.error(getApiErrorMessage(err, '删除标签失败'))
  }
}

function startEditTag(tag: TagDTO) {
  editTagId.value = tag.id
  editTagName.value = tag.name
  tagEditVisible.value = true
}

async function onUpdateTag() {
  if (editTagId.value == null) return
  const name = editTagName.value.trim()
  if (!name) return
  try {
    await tagStore.update(editTagId.value, name)
    ElMessage.success('标签已更新')
    tagEditVisible.value = false
  } catch (err: unknown) {
    ElMessage.error(getApiErrorMessage(err, '更新标签失败'))
  }
}
</script>

<style scoped>
.metadata-page {
  width: 100%;
  max-width: none;
  --el-border-color: var(--border);
  --el-border-color-hover: var(--border-strong);
  --el-border-color-light: var(--border);
  --el-border-color-lighter: var(--border);
}

.tab-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-bottom: var(--space-base);
}

.metadata-input {
  flex: 0 1 240px;
  width: 240px;
  min-width: 180px;
}

.metadata-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--space-base);
}

.metadata-tabs :deep(.el-tabs__nav-wrap::after) {
  background-color: var(--border);
}

.tag-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 230px), 1fr));
  gap: 10px;
}

.tag-toolbar,
.tag-heading__title-row,
.tag-tools,
.tag-create,
.tag-card,
.tag-card__actions {
  display: flex;
  align-items: center;
}

.tag-toolbar {
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--border);
}

.tag-total {
  display: flex;
  align-items: baseline;
  gap: 8px;
  color: var(--text-muted);
  font-size: var(--text-sm);
}

.tag-total strong {
  color: var(--text-primary);
  font-size: 30px;
  font-weight: 750;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.06em;
  line-height: 1;
}

.tag-create {
  gap: 9px;
}

.tag-create .metadata-input {
  width: min(260px, 32vw);
}

.tag-tools {
  justify-content: space-between;
  gap: 16px;
  padding: 20px 0 14px;
}

.tag-search {
  width: min(240px, 48%);
}

.tag-card {
  min-width: 0;
  min-height: 46px;
  justify-content: space-between;
  gap: 10px;
  padding: 0 12px 0 14px;
  border: 1px solid var(--border);
  border-radius: var(--card-radius);
  background: var(--bg-primary);
  transition:
    border-color 160ms ease,
    background 160ms ease,
    transform 160ms ease;
}

.tag-card:hover {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--accent) 46%, var(--border));
  background: color-mix(in srgb, var(--accent) 4%, var(--bg-primary));
}

.tag-card__name {
  overflow: hidden;
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-weight: 560;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-card__actions {
  flex: 0 0 auto;
  gap: 8px;
}

.tag-action {
  padding: 4px 0;
  border: 0;
  color: var(--text-muted);
  background: transparent;
  font: inherit;
  font-size: var(--text-xs);
  cursor: pointer;
  transition: color 140ms ease;
}

.tag-action:hover,
.tag-action:focus-visible {
  color: var(--accent);
}

.tag-action:focus-visible {
  outline: 2px solid var(--control-focus-border);
  outline-offset: 3px;
  border-radius: var(--radius-xs);
}

.tag-action--delete:hover,
.tag-action--delete:focus-visible {
  color: var(--danger);
}

.tag-card__divider {
  width: 1px;
  height: 13px;
  background: var(--border);
}

.tag-empty {
  display: grid;
  min-height: 180px;
  justify-items: center;
  align-content: center;
  gap: 7px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-lg);
  color: var(--text-muted);
  font-size: var(--text-sm);
}

.tag-empty strong {
  color: var(--text-secondary);
  font-size: var(--text-sm);
}

.tag-empty__mark {
  display: grid;
  width: 34px;
  height: 34px;
  margin-bottom: 3px;
  place-items: center;
  border-radius: var(--card-radius);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
  font-size: 20px;
  font-weight: 700;
}

@media (max-width: 560px) {
  .metadata-input {
    flex-basis: 100%;
    width: 100%;
  }

  .tag-toolbar {
    align-items: stretch;
    flex-direction: column;
    gap: 18px;
  }

  .tag-create .metadata-input {
    width: auto;
    flex: 1;
    min-width: 0;
  }

  .tag-workspace {
    padding: 16px;
  }

  .tag-list {
    display: grid;
    grid-template-columns: 1fr;
  }
}
</style>
