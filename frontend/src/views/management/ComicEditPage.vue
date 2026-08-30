<template>
  <div v-loading="loading" class="comic-edit-page fade-in">
    <div class="edit-intro">
      <div>
        <p class="edit-eyebrow">IDENTITY / METADATA</p>
        <h2>编辑漫画信息</h2>
        <p>维护阅读端展示的标题、归属和检索标签。</p>
      </div>
      <div class="edit-ref"><span>RECORD</span><strong>#{{ comicId }}</strong></div>
    </div>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="edit-form">
      <section class="edit-panel edit-panel--primary">
        <div class="panel-heading"><span class="panel-number">01</span><div><h3>基本信息</h3><p>这些字段会直接影响漫画在列表和详情页中的呈现。</p></div></div>
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
          <el-input v-model="form.description" type="textarea" :rows="5" placeholder="写下这部漫画的简介、备注或阅读提示（可选）" maxlength="4000" show-word-limit />
        </el-form-item>
      </section>

      <section class="edit-panel archive-panel">
        <div class="panel-heading"><span class="panel-number">02</span><div><h3>归档与检索</h3><p>用分类和标签建立你的漫画索引。</p></div></div>
        <el-form-item label="标签" prop="tags">
          <div class="tag-editor">
            <div v-if="selectedTags.length" class="selected-tags">
              <el-tag v-for="tag in selectedTags" :key="tag.id" closable class="selected-tag" @close="removeTag(tag.id)">{{ tag.name }}</el-tag>
            </div>
            <div class="tag-add-row">
              <el-select v-model="tagInput" filterable default-first-option placeholder="搜索或选择标签" class="tag-select" popper-class="comic-tag-popper" @change="onExistingTagSelect">
                <template #prefix><el-icon><Search /></el-icon></template>
                <el-option v-for="tag in availableTags" :key="tag.id" :label="tag.name" :value="tag.id" />
              </el-select>
              <span class="or-divider">或</span>
              <el-input v-model="newTagName" placeholder="创建新标签" class="new-tag-input" @keyup.enter="onCreateTag" />
              <el-button type="primary" plain @click="onCreateTag">添加</el-button>
            </div>
            <small class="field-hint"><el-icon><Search /></el-icon>可输入关键词搜索已有标签；标签只用于搜索和筛选，不会改变原始文件。</small>
          </div>
        </el-form-item>
      </section>

      <section class="edit-panel source-panel">
        <div class="panel-heading"><span class="panel-number">03</span><div><h3>来源记录</h3><p>来源信息由导入流程生成，仅供追溯。</p></div></div>
        <div class="source-display">
          <span v-if="sourceType" class="source-tag">{{ sourceTypeLabel(sourceType) }}</span>
          <span v-if="sourceRef" class="source-ref">{{ sourceRef }}</span>
          <span v-if="!sourceType && !sourceRef" class="source-empty">暂无来源记录</span>
        </div>
      </section>

      <section v-if="comicInfo" class="edit-panel comicinfo-panel">
        <div class="panel-heading"><span class="panel-number">04</span><div><h3>ComicInfo.xml 元数据</h3><p>从导入文件中解析的标准漫画元数据，只读展示。</p></div></div>
        <div class="comicinfo-grid">
          <div v-if="comicInfo.series" class="comicinfo-item"><span>Series</span><strong>{{ comicInfo.series }}</strong></div>
          <div v-if="comicInfo.title" class="comicinfo-item"><span>Title</span><strong>{{ comicInfo.title }}</strong></div>
          <div v-if="comicInfo.number" class="comicinfo-item"><span>Number</span><strong>{{ comicInfo.number }}</strong></div>
          <div v-if="comicInfo.writer" class="comicinfo-item"><span>Writer</span><strong>{{ comicInfo.writer }}</strong></div>
        </div>
        <p v-if="comicInfo.summary" class="comicinfo-summary">{{ comicInfo.summary }}</p>
        <div v-if="comicInfo.tags.length" class="comicinfo-tags">
          <span v-for="tag in comicInfo.tags" :key="tag">{{ tag }}</span>
        </div>
      </section>

      <div class="form-actions">
        <el-button text @click="goBack">取消</el-button>
        <el-button type="primary" size="large" :loading="saving" @click="handleSave">保存修改</el-button>
      </div>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { managementComicApi, managementTagApi } from '@/entities/comic/api'
import { useCategoryStore } from '@/features/category/store'
import { sourceTypeLabel } from '@/features/comic/source-format'
import type { ComicDetailVO, ComicInfoVO } from '@/entities/comic/types'
import type { ComicMetadataDTO, ComicMetadataUpdateDTO } from '@/features/comic/management-types'
import type { ComicTagUpdateDTO, TagCreateDTO, TagDTO } from '@/entities/tag/types'

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

const sourceType = ref('')
const sourceRef = ref('')
const comicInfo = ref<ComicInfoVO | null>(null)

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
      managementComicApi.getMetadata(comicId),
      managementComicApi.getTags(comicId),
      managementTagApi.list(),
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
    try {
      const detailRes = await managementComicApi.detail(comicId)
      const detail = detailRes.data as ComicDetailVO
      sourceType.value = detail.sourceType || ''
      sourceRef.value = detail.sourceRef || ''
      comicInfo.value = detail.comicInfo ?? null
    } catch { /* non-critical */ }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '加载漫画信息失败')
    router.push('/manage/comics')
  } finally {
    loading.value = false
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
      const res = await managementTagApi.create({ name } as TagCreateDTO)
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
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '保存失败')
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push(`/manage/comics/${comicId}?tab=operations`)
}

onMounted(loadData)
</script>

<style scoped>
.comic-edit-page {
  display: grid;
  gap: var(--space-5);
  width: 100%;
  max-width: none;
  margin: 0;
  color: var(--text-primary);
}
.edit-intro {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-5);
  padding: 0 0 var(--space-5);
  border-bottom: 1px solid var(--border);
}
.edit-eyebrow, .panel-number { color: var(--accent); font: 800 11px ui-monospace, SFMono-Regular, Consolas, monospace; letter-spacing: .16em;
}
.edit-intro h2 { margin: var(--space-2) 0; font-family: Georgia, 'Times New Roman', serif; font-size: clamp(1.7rem, 3vw, 2.35rem); letter-spacing: -.04em;
}
.edit-intro p:last-child { margin: 0; color: var(--text-muted); font-size: var(--text-sm);
}
.edit-ref { display: grid; gap: 4px; min-width: 88px; padding: 9px 12px; border: 1px solid var(--border); text-align: right;
}
.edit-ref span { color: var(--text-muted); font-size: 10px; letter-spacing: .15em;
}
.edit-ref strong { color: var(--accent); font: 700 15px ui-monospace, SFMono-Regular, Consolas, monospace;
}
.edit-form { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(280px, .85fr); align-items: start; gap: var(--space-4); width: 100%; max-width: none; margin: 0;
}
.edit-panel { padding: clamp(var(--space-5), 4vw, var(--space-8)); border: 1px solid var(--border); background: var(--bg-surface); box-shadow: none;
}
.edit-panel--primary { grid-column: 1; grid-row: 1; }
.archive-panel { grid-column: 1; grid-row: 2; }
.panel-heading { display: flex; align-items: flex-start; gap: var(--space-4); margin-bottom: var(--space-6); }
.panel-heading h3 { margin: 0 0 5px; color: var(--text-primary); font-size: 17px; }
.panel-heading p { margin: 0; color: var(--text-muted); font-size: var(--text-sm); }
.panel-number { padding-top: 3px; }
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-5); }
.edit-form :deep(.el-form-item) { margin-bottom: var(--space-5); }
.edit-form :deep(.el-form-item__label) { color: var(--text-secondary); font-weight: 700; }
.edit-form :deep(.el-input__wrapper), .edit-form :deep(.el-textarea__inner) { background: color-mix(in srgb, var(--bg-primary) 72%, transparent); box-shadow: 0 0 0 1px var(--border) inset; }
.edit-form :deep(.el-textarea__inner:focus) { box-shadow: 0 0 0 1px var(--accent) inset, 0 0 0 3px var(--control-focus-ring); }
.edit-form :deep(.el-select) { width: 100%; }
.tag-editor { display: grid; gap: var(--space-3); }
.selected-tags { display: flex; flex-wrap: wrap; gap: var(--space-2); min-height: 26px; }
.selected-tag { border-color: var(--accent-border); background: var(--accent-bg); color: var(--text-primary); }
.tag-add-row { display: flex; align-items: center; gap: var(--space-3); }
.tag-select { flex: 1; min-width: 190px; }
.tag-select :deep(.el-select__wrapper) { width: 100%; background: linear-gradient(180deg, var(--control-bg), var(--bg-secondary)); box-shadow: inset 0 0 0 1px var(--border), var(--shadow-sm); }
.tag-select :deep(.el-select__wrapper.is-focused) { box-shadow: inset 0 0 0 1px var(--accent), 0 0 0 3px var(--control-focus-ring); }
.new-tag-input { flex: 1; min-width: 150px; }
.or-divider { color: var(--text-muted); font-size: var(--text-xs); }
.field-hint { display: inline-flex; align-items: center; gap: var(--space-1); color: var(--text-muted); }
.field-hint :deep(.el-icon) { color: var(--accent); }
.source-panel { grid-column: 2; grid-row: 1; background: var(--bg-surface); }
.comicinfo-panel { grid-column: 2; grid-row: 2; }
.comicinfo-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-4); }
.comicinfo-item { display: grid; gap: var(--space-1); min-width: 0; }
.comicinfo-item span { color: var(--text-muted); font-size: var(--text-xs); }
.comicinfo-item strong { overflow: hidden; color: var(--text-primary); font-size: var(--text-sm); text-overflow: ellipsis; white-space: nowrap; }
.comicinfo-summary { margin: var(--space-4) 0 0; color: var(--text-secondary); font-size: var(--text-sm); line-height: 1.7; white-space: pre-wrap; }
.comicinfo-tags { display: flex; flex-wrap: wrap; gap: var(--space-2); margin-top: var(--space-4); }
.comicinfo-tags span { padding: 4px 8px; border: 1px solid var(--accent-border); background: var(--accent-bg); color: var(--text-primary); font-size: var(--text-xs); }
.source-display { display: flex; align-items: center; gap: var(--space-3); min-height: 46px; padding: 0 var(--space-4); border: 1px dashed var(--border); color: var(--text-muted); }
.source-tag { padding: 4px 8px; background: var(--accent-bg); color: var(--accent); font-size: 11px; font-weight: 800; letter-spacing: .08em; }
.source-ref { overflow: hidden; color: var(--text-secondary); font-size: var(--text-sm); text-overflow: ellipsis; white-space: nowrap; }
.source-empty { font-size: var(--text-sm); }
:global(.comic-tag-popper) { background: var(--bg-surface); border: 1px solid var(--border); box-shadow: var(--card-shadow-hover); }
.form-actions { position: sticky; bottom: 0; z-index: 2; grid-column: 2; grid-row: 3; display: flex; justify-content: flex-end; gap: var(--space-3); padding: var(--space-4) 0 var(--space-2); background: linear-gradient(to bottom, transparent, var(--bg-primary) 28%); }
.form-actions :deep(.el-button--primary) { min-width: 132px; background: var(--accent); border-color: var(--accent); }
.form-actions :deep(.el-button--primary:hover) { background: var(--accent-hover); border-color: var(--accent-hover); }
@media (max-width: 820px) {
  .edit-form { grid-template-columns: 1fr; }
  .edit-panel--primary, .archive-panel, .source-panel, .comicinfo-panel, .form-actions { grid-column: 1; grid-row: auto; }
  .edit-intro { align-items: flex-start; }
  .edit-ref { min-width: auto; }
  .field-grid { grid-template-columns: 1fr; gap: 0; }
  .tag-add-row { align-items: stretch; flex-wrap: wrap; }
  .tag-select, .new-tag-input { min-width: calc(100% - 0px); flex-basis: 100%; }
  .or-divider { display: none; }
}
</style>
