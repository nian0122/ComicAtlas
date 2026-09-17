import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getApiErrorMessage } from '@/shared/api/http'
import { managementTagApi } from '@/entities/tag'
import type { TagDTO } from '@/entities/tag'

/** 编辑页标签选择与创建逻辑，页面只负责表单编排。 */
export function useComicEditTags() {
  const selectedTagIds = ref<number[]>([])
  const allTags = ref<TagDTO[]>([])
  const tagInput = ref<number | undefined>(undefined)
  const newTagName = ref('')
  const selectedTags = computed(() =>
    selectedTagIds.value.map((id) => allTags.value.find((tag) => tag?.id === id)).filter((tag): tag is TagDTO => !!tag),
  )
  const availableTags = computed(() =>
    allTags.value.filter((tag) => tag?.id !== undefined && !selectedTagIds.value.includes(tag.id)),
  )

  function removeTag(id: number): void {
    selectedTagIds.value = selectedTagIds.value.filter((tagId) => tagId !== id)
  }
  function onExistingTagSelect(value: number | undefined | null): void {
    if (value !== undefined && value !== null && !selectedTagIds.value.includes(value)) selectedTagIds.value.push(value)
    tagInput.value = undefined
  }
  async function onCreateTag(): Promise<void> {
    const name = newTagName.value.trim()
    if (!name) return
    const existingTag = allTags.value.find((tag) => tag?.name === name)
    if (existingTag) {
      if (!selectedTagIds.value.includes(existingTag.id)) selectedTagIds.value.push(existingTag.id)
    } else {
      try {
        const createdTag = (await managementTagApi.create({ name })).data
        allTags.value.push(createdTag)
        selectedTagIds.value.push(createdTag.id)
      } catch (error: unknown) {
        ElMessage.error(getApiErrorMessage(error, '创建标签失败'))
        return
      }
    }
    newTagName.value = ''
  }
  return {
    selectedTagIds,
    allTags,
    tagInput,
    newTagName,
    selectedTags,
    availableTags,
    removeTag,
    onExistingTagSelect,
    onCreateTag,
  }
}
