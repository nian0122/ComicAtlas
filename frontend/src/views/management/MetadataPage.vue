<template>
  <div class="metadata-page">
    <ManagementPageHeader spaced title="元数据管理" />

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
          <el-button type="primary" :loading="categoryStore.loading" @click="onCreateCategory"> 添加分类 </el-button>
        </div>

        <el-table v-loading="categoryStore.loading" :data="categoryStore.list" style="width: 100%">
          <el-table-column prop="name" label="名称" />
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button link type="primary" @click="startEditCategory(row)">编辑</el-button>
              <el-button link type="danger" @click="onDeleteCategory(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="标签" name="tag">
        <div class="tab-toolbar">
          <el-input v-model="newTagName" placeholder="新标签名称" class="metadata-input" @keyup.enter="onCreateTag" />
          <el-button type="primary" :loading="tagStore.loading" @click="onCreateTag"> 添加标签 </el-button>
        </div>

        <div class="tag-list">
          <el-tag
            v-for="tag in tagStore.list"
            :key="tag?.id ?? Math.random()"
            closable
            class="tag-item"
            @close="onDeleteTag(tag)"
          >
            {{ tag.name }}
          </el-tag>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="categoryEditVisible" title="编辑分类" width="400px">
      <el-input v-model="editCategoryName" placeholder="分类名称" />
      <template #footer>
        <el-button @click="categoryEditVisible = false">取消</el-button>
        <el-button type="primary" @click="onUpdateCategory">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import StatGrid from '@/components/management/StatGrid.vue'
import StatCard from '@/components/management/StatCard.vue'
import ManagementPageHeader from '@/components/management/ManagementPageHeader.vue'
import { computed, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getApiErrorMessage } from '@/services/http'
import { useCategoryStore } from '@/features/category/store'
import { useTagStore } from '@/features/tag/store'
import type { CategoryDTO } from '@/entities/comic/types'
import type { TagDTO } from '@/entities/tag/types'

const activeTab = ref('category')
const categoryStore = useCategoryStore()
const tagStore = useTagStore()

const newCategoryName = ref('')
const categoryEditVisible = ref(false)
const editCategoryId = ref<number | null>(null)
const editCategoryName = ref('')

const newTagName = ref('')
const metadataHealthy = computed(() => !categoryStore.error && !tagStore.error)
const metadataStatusLabel = computed(() => (metadataHealthy.value ? '正常' : '接口异常'))
const metadataStatusHint = computed(() => {
  if (metadataHealthy.value) return '接口同步可用'
  return categoryStore.error || tagStore.error || '请稍后重试'
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

.metadata-page :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--border) inset;
}

.metadata-page :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--border-strong) inset;
}

.metadata-page :deep(.el-input__wrapper.is-focus) {
  box-shadow: inset 0 0 0 1px var(--accent), var(--shadow-sm) !important;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.tag-item {
  font-size: 13px;
  padding: 4px 9px;
}

@media (max-width: 560px) {
  .metadata-input {
    flex-basis: 100%;
    width: 100%;
  }
}
</style>
