<template>
  <div class="metadata-page">
    <header class="page-header">
      <h1 class="page-title">元数据管理</h1>
    </header>

    <el-tabs v-model="activeTab" class="metadata-tabs">
      <el-tab-pane label="分类" name="category">
        <div class="tab-toolbar">
          <el-input
            v-model="newCategoryName"
            placeholder="新分类名称"
            style="width: 240px"
            @keyup.enter="onCreateCategory"
          />
          <el-button type="primary" :loading="categoryStore.loading" @click="onCreateCategory">
            添加分类
          </el-button>
        </div>

        <el-table :data="categoryStore.list" style="width: 100%" v-loading="categoryStore.loading">
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
          <el-input
            v-model="newTagName"
            placeholder="新标签名称"
            style="width: 240px"
            @keyup.enter="onCreateTag"
          />
          <el-button type="primary" :loading="tagStore.loading" @click="onCreateTag">
            添加标签
          </el-button>
        </div>

        <div class="tag-list">
          <el-tag
            v-for="tag in tagStore.list"
            :key="tag.id"
            closable
            class="tag-item"
            @close="onDeleteTag(tag.id)"
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
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCategoryStore } from '@/stores/management/category'
import { useTagStore } from '@/stores/tag-store'
import type { CategoryDTO } from '@/types'

const activeTab = ref('category')
const categoryStore = useCategoryStore()
const tagStore = useTagStore()

const newCategoryName = ref('')
const categoryEditVisible = ref(false)
const editCategoryId = ref<number | null>(null)
const editCategoryName = ref('')

const newTagName = ref('')

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
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '添加分类失败')
  }
}

function startEditCategory(row: CategoryDTO) {
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
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '更新分类失败')
  }
}

async function onDeleteCategory(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该分类？', '删除分类', { type: 'warning' })
    await categoryStore.remove(id)
    ElMessage.success('分类已删除')
  } catch (err: unknown) {
    if (err === 'cancel') return
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '删除分类失败')
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
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '添加标签失败')
  }
}

async function onDeleteTag(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该标签？', '删除标签', { type: 'warning' })
    await tagStore.delete(id)
    ElMessage.success('标签已删除')
  } catch (err: unknown) {
    if (err === 'cancel') return
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '删除标签失败')
  }
}
</script>

<style scoped>
.metadata-page {
  max-width: 960px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-xl);
}

.tab-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  margin-bottom: var(--space-lg);
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.tag-item {
  font-size: 13px;
  padding: 6px 10px;
}
</style>
