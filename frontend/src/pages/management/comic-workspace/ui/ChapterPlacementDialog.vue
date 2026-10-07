<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElTree } from 'element-plus'
import { chapterManagementApi, type CatalogNode, type StructureRow } from '@/entities/comic'
import { AppButton } from '@/shared/ui/button'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{
  mode: 'create' | 'move'
  comicId: number
  comicTitle: string
  catalogs: readonly CatalogNode[]
  chapter: StructureRow | null
  mediaCount: number
  initialCatalogId: number | null
}>()
const emit = defineEmits<{ completed: [chapterId: number, mode: 'create' | 'move'] }>()

interface DirectoryChoice {
  key: number | 'root'
  catalogId: number | null
  title: string
  path: string
  children: DirectoryChoice[]
}

const form = reactive({ title: '', chapterNo: '', catalogId: null as number | null })
const keyword = ref('')
const submitting = ref(false)
const submissionError = ref('')
const hasChosenDestination = ref(false)
const titleInput = ref<{ focus: () => void }>()
const directoryTree = ref<InstanceType<typeof ElTree>>()
const isCreating = computed(() => props.mode === 'create')

function directoryChoices(nodes: readonly CatalogNode[], parentPath: string): DirectoryChoice[] {
  return nodes.flatMap((node) => {
    if (node.id === null) return directoryChoices(node.children, parentPath)
    const title = node.title || '未命名目录'
    const path = `${parentPath} / ${title}`
    return [{ key: node.id, catalogId: node.id, title, path, children: directoryChoices(node.children, path) }]
  })
}

const choices = computed<DirectoryChoice[]>(() => [
  {
    key: 'root',
    catalogId: null,
    title: '漫画根目录',
    path: '漫画根目录',
    children: directoryChoices(props.catalogs, '漫画根目录'),
  },
])
function findDirectory(nodes: readonly DirectoryChoice[], catalogId: number | null): DirectoryChoice | undefined {
  for (const node of nodes) {
    if (node.catalogId === catalogId) return node
    const child = findDirectory(node.children, catalogId)
    if (child) return child
  }
  return undefined
}
const currentPath = computed(() => findDirectory(choices.value, props.chapter?.parentCatalogId ?? null)?.path)
const destinationPath = computed(() => findDirectory(choices.value, form.catalogId)?.path ?? '漫画根目录')
const canSubmit = computed(() =>
  isCreating.value
    ? Boolean(form.title.trim())
    : Boolean(
        props.chapter && hasChosenDestination.value && form.catalogId !== (props.chapter.parentCatalogId ?? null),
      ),
)

function isCurrentDirectory(node: DirectoryChoice): boolean {
  return !isCreating.value && node.catalogId === (props.chapter?.parentCatalogId ?? null)
}
function chooseDirectory(node: DirectoryChoice): void {
  if (submitting.value || isCurrentDirectory(node)) return
  form.catalogId = node.catalogId
  hasChosenDestination.value = true
  submissionError.value = ''
  directoryTree.value?.setCurrentKey(node.key)
}
function filterDirectory(search: string, node: Record<string, unknown>): boolean {
  return (
    node.key === 'root' ||
    (typeof node.path === 'string' && node.path.toLocaleLowerCase().includes(search.toLocaleLowerCase().trim()))
  )
}
watch(keyword, (search) => directoryTree.value?.filter(search))
watch(visible, (isVisible) => {
  if (!isVisible) return
  form.title = ''
  form.chapterNo = ''
  form.catalogId = isCreating.value ? props.initialCatalogId : (props.chapter?.parentCatalogId ?? null)
  keyword.value = ''
  submissionError.value = ''
  hasChosenDestination.value = isCreating.value
})
async function focusForm(): Promise<void> {
  await nextTick()
  directoryTree.value?.setCurrentKey(form.catalogId ?? 'root')
  if (isCreating.value) titleInput.value?.focus()
}
function closeDialog(done: () => void): void {
  if (!submitting.value) done()
}
async function submitPlacement(): Promise<void> {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  submissionError.value = ''
  try {
    const response = isCreating.value
      ? await chapterManagementApi.create(props.comicId, {
          title: form.title.trim(),
          chapterNo: form.chapterNo.trim() || undefined,
          catalogId: form.catalogId,
        })
      : await chapterManagementApi.move(props.comicId, props.chapter!.id, { catalogId: form.catalogId })
    ElMessage.success(isCreating.value ? '章节已创建' : '章节已移动')
    visible.value = false
    emit('completed', response.data.id, props.mode)
  } catch (reason: unknown) {
    submissionError.value = reason instanceof Error ? reason.message : '操作失败，请重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="isCreating ? '新建章节' : '移动章节'"
    width="min(600px, calc(100vw - 32px))"
    class="chapter-placement-dialog"
    align-center
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    :before-close="closeDialog"
    @opened="focusForm"
  >
    <div class="placement-body">
      <p class="placement-comic">{{ comicTitle || `漫画 #${comicId}` }}</p>
      <div v-if="!isCreating" class="placement-source">
        <strong>{{ chapter?.title }}</strong>
        <span>{{ mediaCount }} 个媒体 · 当前位于 {{ currentPath }}</span>
      </div>
      <el-alert v-if="submissionError" :title="submissionError" type="error" :closable="false" show-icon />
      <el-form :disabled="submitting" label-position="top" @submit.prevent="submitPlacement">
        <template v-if="isCreating">
          <el-form-item label="章节标题" required>
            <el-input ref="titleInput" v-model="form.title" maxlength="255" placeholder="输入新章节标题" />
          </el-form-item>
          <el-form-item label="原始章节编号（可选）">
            <el-input v-model="form.chapterNo" maxlength="32" placeholder="如 01、番外；可留空" />
          </el-form-item>
        </template>
        <div class="placement-destination">
          <label for="chapter-directory-search">{{ isCreating ? '所属目录' : '目标目录' }}</label>
          <el-input id="chapter-directory-search" v-model="keyword" clearable placeholder="搜索目录名称或路径" />
          <div class="placement-tree-scroll">
            <el-tree
              ref="directoryTree"
              :data="choices"
              node-key="key"
              :props="{ label: 'title', children: 'children' }"
              :filter-node-method="filterDirectory"
              :expand-on-click-node="false"
              default-expand-all
              empty-text="没有匹配的目录"
              class="placement-tree"
            >
              <template #default="{ data }">
                <AppButton
                  variant="ghost"
                  type="button"
                  class="placement-directory"
                  :class="{ 'is-selected': hasChosenDestination && form.catalogId === data.catalogId }"
                  :disabled="submitting || isCurrentDirectory(data)"
                  :aria-pressed="hasChosenDestination && form.catalogId === data.catalogId"
                  :title="data.path"
                  @click.stop="chooseDirectory(data)"
                >
                  <span class="placement-directory-content"
                    ><span>{{ data.title }}</span>
                    <small v-if="isCurrentDirectory(data)">当前位置</small>
                    <span v-else-if="hasChosenDestination && form.catalogId === data.catalogId" aria-hidden="true"
                      >✓</span
                    >
                  </span></AppButton
                >
              </template>
            </el-tree>
          </div>
        </div>
      </el-form>
      <div class="placement-preview" aria-live="polite">
        <span>{{ isCreating ? '将在此目录创建' : '移动路径' }}</span>
        <p v-if="!isCreating">{{ currentPath }} <span aria-hidden="true">→</span></p>
        <strong>{{ hasChosenDestination ? destinationPath : '请选择目标目录' }}</strong>
      </div>
      <p class="placement-hint">
        {{
          isCreating
            ? '新章节会追加到所选目录末尾，不修改现有章节。'
            : '本章媒体随章节一起移动，章节将追加到目标目录末尾。'
        }}
      </p>
    </div>
    <template #footer
      ><div class="placement-footer">
        <AppButton :disabled="submitting" @click="visible = false">取消</AppButton>
        <AppButton variant="primary" :loading="submitting" :disabled="!canSubmit" @click="submitPlacement">
          {{ isCreating ? '创建章节' : '确认移动' }}
        </AppButton>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped src="./chapter-placement.css"></style>
