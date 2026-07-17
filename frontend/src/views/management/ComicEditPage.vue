<template>
  <div class="comic-edit-page fade-in">
    <div class="edit-header">
      <div class="header-inner">
        <button class="back-btn" @click="goBack">
          <el-icon :size="18"><ArrowLeft /></el-icon>
          <span>返回</span>
        </button>
        <h1 class="page-title">编辑漫画信息</h1>
        <div class="header-spacer" />
      </div>
    </div>

    <div class="edit-body">
      <div class="edit-card">
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="edit-form"
        >
          <el-form-item label="标题" prop="title">
            <el-input
              v-model="form.title"
              placeholder="输入漫画标题"
              maxlength="255"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="作者" prop="author">
            <el-input
              v-model="form.author"
              placeholder="输入作者名（可选）"
              maxlength="128"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="描述" prop="description">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="4"
              placeholder="输入漫画描述（可选）"
              maxlength="4000"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="分类" prop="categoryId">
            <el-select v-model="form.categoryId" placeholder="选择分类" clearable style="width: 240px">
              <el-option
                v-for="cat in categoryStore.list"
                :key="cat.id"
                :label="cat.name"
                :value="cat.id"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="标签" prop="tags">
            <div class="tag-block">
              <el-tag
                v-for="tag in selectedTags"
                :key="tag.id"
                closable
                class="selected-tag"
                @close="removeTag(tag.id)"
              >
                {{ tag.name }}
              </el-tag>
              <el-select
                v-model="tagInput"
                filterable
                default-first-option
                placeholder="选择或输入标签"
                class="tag-select"
                @change="onExistingTagSelect"
              >
                <el-option
                  v-for="tag in availableTags"
                  :key="tag.id"
                  :label="tag.name"
                  :value="tag.id"
                />
              </el-select>
              <el-input
                v-model="newTagName"
                placeholder="新标签"
                class="new-tag-input"
                @keyup.enter="onCreateTag"
              />
              <el-button type="primary" text @click="onCreateTag">
                添加
              </el-button>
            </div>
          </el-form-item>

          <el-form-item label="封面">
            <div class="cover-block">
              <el-button @click="openCoverSelector">选择封面</el-button>
            </div>
          </el-form-item>

          <div class="form-actions">
            <el-button @click="goBack">取消</el-button>
            <el-button type="primary" :loading="saving" @click="handleSave">
              保存
            </el-button>
          </div>
        </el-form>
      </div>
    </div>

    <el-dialog
      v-model="coverDialogVisible"
      title="选择封面"
      width="800px"
      class="cover-dialog"
      destroy-on-close
    >
      <div v-if="coverLoading" class="state loading small">
        <div class="spinner" />
        <span>加载候选封面...</span>
      </div>
      <div v-else-if="coverCandidates.length === 0" class="state empty small">
        <span>暂无可用封面</span>
      </div>
      <div v-else class="cover-grid">
        <div
          v-for="candidate in coverCandidates"
          :key="candidate.pageId"
          class="cover-item"
          :class="{ active: selectedCoverPageId === candidate.pageId }"
          @click="selectedCoverPageId = candidate.pageId"
        >
          <img :src="candidate.url" :alt="candidate.chapterTitle">
          <span class="cover-label">{{ candidate.chapterTitle }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="coverDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!selectedCoverPageId"
          @click="saveCover"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { comicApi, tagApi } from '@/services/management'
import { useCategoryStore } from '@/stores/management/category'
import type {
  ComicMetadataDTO,
  ComicMetadataUpdateDTO,
  TagDTO,
  TagCreateDTO,
  ComicTagUpdateDTO,
  CoverCandidateDTO,
} from '@/types'

const route = useRoute()
const router = useRouter()
const categoryStore = useCategoryStore()

const comicId = Number(route.params.id)
const formRef = ref()
const loading = ref(false)
const saving = ref(false)

const form = ref<ComicMetadataUpdateDTO>({
  title: '',
  author: '',
  description: '',
  categoryId: null,
})

const selectedTagIds = ref<number[]>([])
const allTags = ref<TagDTO[]>([])
const tagInput = ref<number | undefined>(undefined)
const newTagName = ref('')

const coverDialogVisible = ref(false)
const coverLoading = ref(false)
const coverCandidates = ref<CoverCandidateDTO[]>([])
const selectedCoverPageId = ref<number | null>(null)

const selectedTags = computed<TagDTO[]>(() => {
  return selectedTagIds.value
    .map((id) => allTags.value.find((t) => t && t.id === id))
    .filter((t): t is TagDTO => !!t && typeof t.id === 'number')
})

const availableTags = computed<TagDTO[]>(() => {
  return allTags.value.filter((t) => t && t.id !== undefined && !selectedTagIds.value.includes(t.id))
})

const rules = {
  title: [
    { required: true, message: '标题不能为空', trigger: 'blur' },
    { max: 255, message: '标题长度不能超过 255 个字符', trigger: 'blur' },
  ],
  author: [
    { max: 128, message: '作者长度不能超过 128 个字符', trigger: 'blur' },
  ],
}

async function loadData() {
  if (!comicId) {
    ElMessage.error('参数不完整')
    router.push('/manage/comics')
    return
  }
  loading.value = true
  try {
    const [metadataRes, tagsRes, allTagsRes] = await Promise.all([
      comicApi.getMetadata(comicId),
      comicApi.getTags(comicId),
      tagApi.list(),
      categoryStore.fetchList(),
    ])
    const metadata = metadataRes.data as ComicMetadataDTO
    form.value = {
      title: metadata.title || '',
      author: metadata.author || '',
      description: metadata.description || '',
      categoryId: metadata.categoryId ?? null,
    }
    selectedTagIds.value = (tagsRes.data as number[]) || []
    allTags.value = (allTagsRes.data as TagDTO[]) || []
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '加载漫画信息失败')
    router.push('/manage/comics')
  } finally {
    loading.value = false
  }
}

async function openCoverSelector() {
  coverDialogVisible.value = true
  coverLoading.value = true
  selectedCoverPageId.value = null
  try {
    const res = await comicApi.listCoverCandidates(comicId)
    coverCandidates.value = (res.data || []) as CoverCandidateDTO[]
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '加载封面候选失败')
    coverCandidates.value = []
  } finally {
    coverLoading.value = false
  }
}

async function saveCover() {
  if (!selectedCoverPageId.value) return
  try {
    await comicApi.updateCover(comicId, { pageId: selectedCoverPageId.value })
    ElMessage.success('封面已更新')
    coverDialogVisible.value = false
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '封面更新失败')
  }
}

function removeTag(id: number) {
  selectedTagIds.value = selectedTagIds.value.filter((tid) => tid !== id)
}

function onExistingTagSelect(value: number | undefined | null) {
  if (value === undefined || value === null) return
  if (!selectedTagIds.value.includes(value)) {
    selectedTagIds.value.push(value)
  }
  tagInput.value = undefined
}

async function onCreateTag() {
  const name = newTagName.value.trim()
  if (!name) return

  const existing = allTags.value.find((t) => t && t.name === name)
  if (existing) {
    if (!selectedTagIds.value.includes(existing.id)) {
      selectedTagIds.value.push(existing.id)
    }
  } else {
    try {
      const res = await tagApi.create({ name } as TagCreateDTO)
      const newTag = res.data as TagDTO
      allTags.value.push(newTag)
      selectedTagIds.value.push(newTag.id)
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      ElMessage.error(msg || '创建标签失败')
      return
    }
  }

  newTagName.value = ''
}

async function handleSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    await Promise.all([
      comicApi.updateMetadata(comicId, {
        title: form.value.title.trim(),
        author: form.value.author?.trim() || '',
        description: form.value.description?.trim() || '',
      }),
      comicApi.updateTags(comicId, { tagIds: selectedTagIds.value } as ComicTagUpdateDTO),
    ])
    ElMessage.success('保存成功')
    router.push('/manage/comics')
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '保存失败')
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push('/manage/comics')
}

onMounted(loadData)
</script>

<style scoped>
.comic-edit-page {
  min-height: calc(100vh - var(--nav-height));
  background: var(--bg-primary);
  color: var(--text-primary);
}

.edit-header {
  padding: var(--space-lg) var(--page-padding);
  background: linear-gradient(to bottom, rgba(0, 0, 0, 0.6), transparent);
}

.header-inner {
  max-width: var(--page-width);
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 8px 14px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.back-btn:hover {
  background: rgba(255, 255, 255, 0.18);
}

.page-title {
  font-family: var(--heading);
  font-size: 20px;
  font-weight: 700;
  margin: 0;
}

.header-spacer {
  width: 80px;
}

.edit-body {
  padding: var(--space-2xl) var(--page-padding);
}

.edit-card {
  max-width: 640px;
  margin: 0 auto;
  padding: var(--space-xl);
  background: var(--bg-surface);
  border-radius: var(--card-radius);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.edit-form :deep(.el-form-item__label) {
  color: var(--text-secondary);
  font-weight: 600;
}

.edit-form :deep(.el-input__wrapper) {
  background: var(--bg-secondary);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.1) inset;
}

.edit-form :deep(.el-input__inner) {
  color: var(--text-primary);
}

.tag-block {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm);
  background: var(--bg-secondary);
  border-radius: var(--radius-sm);
  border: 1px solid rgba(255, 255, 255, 0.08);
  min-height: 40px;
}

.selected-tag {
  background: var(--accent-bg);
  color: var(--text-primary);
  border: none;
}

.tag-select {
  min-width: 160px;
  flex: 1;
}

.tag-select :deep(.el-input__wrapper) {
  background: transparent;
  box-shadow: none;
}

.new-tag-input {
  width: 140px;
}

.new-tag-input :deep(.el-input__wrapper) {
  background: transparent;
  box-shadow: none;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-base);
  margin-top: var(--space-xl);
}

@media (max-width: 768px) {
  .header-inner {
    flex-wrap: wrap;
    gap: var(--space-base);
  }

  .header-spacer {
    display: none;
  }

  .page-title {
    order: 3;
    width: 100%;
    text-align: center;
  }
}

.cover-block {
  padding: var(--space-sm);
  background: var(--bg-secondary);
  border-radius: var(--radius-sm);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.cover-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--space-base);
  max-height: 480px;
  overflow-y: auto;
  padding: var(--space-sm);
}

.cover-item {
  position: relative;
  cursor: pointer;
  border-radius: var(--radius-sm);
  overflow: hidden;
  border: 2px solid transparent;
  transition: border-color var(--transition-fast);
  background: var(--bg-secondary);
}

.cover-item:hover {
  border-color: var(--border-strong);
}

.cover-item.active {
  border-color: var(--accent);
}

.cover-item img {
  width: 100%;
  height: 180px;
  object-fit: cover;
  display: block;
}

.cover-label {
  display: block;
  padding: var(--space-xs) var(--space-sm);
  font-size: 12px;
  color: var(--text-secondary);
  text-align: center;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cover-dialog :deep(.el-dialog__header) {
  margin-right: 0;
}

.cover-dialog :deep(.el-dialog__body) {
  padding: var(--space-base);
}

.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-base);
  padding: var(--space-xl) 0;
  color: var(--text-secondary);
}

.state.loading .spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--border-strong);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
