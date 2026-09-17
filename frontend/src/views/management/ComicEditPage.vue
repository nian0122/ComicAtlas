<template>
  <div v-loading="loading" class="comic-edit-page fade-in">
    <div class="edit-intro">
      <div>
        <p class="edit-eyebrow">IDENTITY / METADATA</p>
        <h2>编辑漫画信息</h2>
        <p>维护阅读端展示的标题、归属和检索标签。</p>
      </div>
      <div class="edit-ref">
        <span>RECORD</span><strong>#{{ comicId }}</strong>
      </div>
    </div>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="edit-form">
      <div class="edit-main-column">
        <section class="edit-panel edit-panel--primary">
          <PanelHeader
            class="edit-panel-heading"
            level="h3"
            title="基本信息"
            description="这些字段会直接影响漫画在列表和详情页中的呈现。"
            ><template #leading>01</template></PanelHeader
          >
          <el-form-item label="标题" prop="title" class="title-field">
            <el-input v-model="form.title" placeholder="输入漫画标题" maxlength="255" show-word-limit size="large" />
          </el-form-item>
          <div class="field-grid">
            <el-form-item label="作者" prop="author">
              <el-input v-model="form.author" placeholder="输入作者名（可选）" maxlength="128" show-word-limit />
            </el-form-item>
            <el-form-item label="分类" prop="categoryId">
              <el-select v-model="form.categoryId" placeholder="选择分类" clearable>
                <el-option v-for="cat in categoryStore.list" :key="cat.id" :label="cat.name" :value="cat.id" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="描述" prop="description">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="5"
              placeholder="写下这部漫画的简介、备注或阅读提示（可选）"
              maxlength="4000"
              show-word-limit
            />
          </el-form-item>
        </section>

        <section class="edit-panel archive-panel">
          <PanelHeader
            class="edit-panel-heading"
            level="h3"
            title="归档与检索"
            description="用分类和标签建立你的漫画索引。"
            ><template #leading>02</template></PanelHeader
          >
          <el-form-item label="标签" prop="tags">
            <div class="tag-editor">
              <div v-if="selectedTags.length" class="selected-tags">
                <el-tag
                  v-for="tag in selectedTags"
                  :key="tag.id"
                  closable
                  class="selected-tag"
                  @close="removeTag(tag.id)"
                  >{{ tag.name }}</el-tag
                >
              </div>
              <div class="tag-add-row">
                <el-select
                  v-model="tagInput"
                  filterable
                  default-first-option
                  placeholder="搜索或选择标签"
                  class="tag-select"
                  popper-class="comic-tag-popper"
                  @change="onExistingTagSelect"
                >
                  <template #prefix
                    ><el-icon><Search /></el-icon
                  ></template>
                  <el-option v-for="tag in availableTags" :key="tag.id" :label="tag.name" :value="tag.id" />
                </el-select>
                <span class="or-divider">或</span>
                <el-input
                  v-model="newTagName"
                  placeholder="创建新标签"
                  class="new-tag-input"
                  @keyup.enter="onCreateTag"
                />
                <el-button type="primary" plain @click="onCreateTag">添加</el-button>
              </div>
              <small class="field-hint"
                ><el-icon><Search /></el-icon>可输入关键词搜索已有标签；标签只用于搜索和筛选，不会改变原始文件。</small
              >
            </div>
          </el-form-item>
        </section>
      </div>

      <aside class="edit-side-column">
        <section class="edit-panel source-panel">
        <PanelHeader
          class="edit-panel-heading"
          level="h3"
          title="来源记录"
          description="来源信息由导入流程生成，仅供追溯。"
          ><template #leading>03</template></PanelHeader
        >
        <div class="source-display">
          <span v-if="sourceType" class="source-tag">{{ sourceTypeLabel(sourceType) }}</span>
          <span v-if="sourceRef" class="source-ref">{{ sourceRef }}</span>
          <span v-if="!sourceType && !sourceRef" class="source-empty">暂无来源记录</span>
        </div>
        </section>

        <section v-if="comicInfo" class="edit-panel comicinfo-panel">
        <PanelHeader
          class="edit-panel-heading"
          level="h3"
          title="ComicInfo.xml 元数据"
          description="从导入文件中解析的标准漫画元数据，只读展示。"
          ><template #leading>04</template></PanelHeader
        >
        <div class="comicinfo-grid">
          <div v-if="comicInfo.series" class="comicinfo-item">
            <span>Series</span><strong>{{ comicInfo.series }}</strong>
          </div>
          <div v-if="comicInfo.title" class="comicinfo-item">
            <span>Title</span><strong>{{ comicInfo.title }}</strong>
          </div>
          <div v-if="comicInfo.number" class="comicinfo-item">
            <span>Number</span><strong>{{ comicInfo.number }}</strong>
          </div>
          <div v-if="comicInfo.writer" class="comicinfo-item">
            <span>Writer</span><strong>{{ comicInfo.writer }}</strong>
          </div>
        </div>
        <p v-if="comicInfo.summary" class="comicinfo-summary">{{ comicInfo.summary }}</p>
        <div v-if="comicInfo.tags.length" class="comicinfo-tags">
          <span v-for="tag in comicInfo.tags" :key="tag">{{ tag }}</span>
        </div>
        </section>
      </aside>

      <div class="form-actions">
        <el-button @click="goBack">取消</el-button>
        <el-button type="primary" size="large" :loading="saving" @click="handleSave">保存修改</el-button>
      </div>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import PanelHeader from '@/components/management/PanelHeader.vue'
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { getApiErrorMessage } from '@/services/http'
import { managementComicApi } from '@/features/comic/management-api'
import { managementTagApi } from '@/features/tag/api'
import { useCategoryStore } from '@/features/category/store'
import { sourceTypeLabel } from '@/features/comic/source-format'
import type { ComicInfoVO } from '@/entities/comic/types'
import type { ComicMetadataUpdateDTO } from '@/entities/comic/management-types'
import type { ComicTagUpdateDTO } from '@/entities/tag/types'
import { useComicEditTags } from './composables/useComicEditTags'

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

const {
  selectedTagIds,
  allTags,
  tagInput,
  newTagName,
  selectedTags,
  availableTags,
  removeTag,
  onExistingTagSelect,
  onCreateTag,
} = useComicEditTags()

const sourceType = ref('')
const sourceRef = ref('')
const comicInfo = ref<ComicInfoVO | null>(null)

const rules = {
  title: [
    { required: true, message: '标题不能为空', trigger: 'blur' },
    { max: 255, message: '标题长度不能超过 255 个字符', trigger: 'blur' },
  ],
  author: [{ max: 128, message: '作者长度不能超过 128 个字符', trigger: 'blur' }],
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
      managementComicApi.getMetadata(comicId),
      managementComicApi.getTags(comicId),
      managementTagApi.list(),
      categoryStore.fetchList(),
    ])
    const metadata = metadataRes.data
    form.value = {
      title: metadata.title || '',
      author: metadata.author || '',
      description: metadata.description || '',
      categoryId: metadata.categoryId ?? null,
    }
    selectedTagIds.value = tagsRes.data
    allTags.value = allTagsRes.data
    try {
      const detailRes = await managementComicApi.detail(comicId)
      const detail = detailRes.data
      sourceType.value = detail.sourceType || ''
      sourceRef.value = detail.sourceRef || ''
      comicInfo.value = detail.comicInfo ?? null
    } catch {
      /* non-critical */
    }
  } catch (err: unknown) {
    ElMessage.error(getApiErrorMessage(err, '加载漫画信息失败'))
    router.push('/manage/comics')
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    await Promise.all([
      managementComicApi.updateMetadata(comicId, {
        title: form.value.title.trim(),
        author: form.value.author?.trim() || '',
        description: form.value.description?.trim() || '',
        categoryId: form.value.categoryId,
      }),
      managementComicApi.updateTags(comicId, { tagIds: selectedTagIds.value } as ComicTagUpdateDTO),
    ])
    ElMessage.success('保存成功')
    router.push(`/manage/comics/${comicId}?tab=operations`)
  } catch (err: unknown) {
    ElMessage.error(getApiErrorMessage(err, '保存失败'))
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push(`/manage/comics/${comicId}?tab=operations`)
}

onMounted(loadData)
</script>

<style scoped src="@/styles/pages/management/comic-edit.css"></style>
