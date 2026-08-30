import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'
import { managementTagApi } from '@/entities/comic/api'
import type { TagDTO } from '@/entities/tag/types'

export interface TagState {
  list: TagDTO[]
  loading: boolean
  error: string | null
}

export const useTagStore = defineStore('tag', () => {
  const state = reactive<TagState>({
    list: [],
    loading: false,
    error: null,
  })

  async function fetchList() {
    state.loading = true
    state.error = null
    try {
      const res = await managementTagApi.list()
      state.list = ((res.data as TagDTO[]) || []).filter((t): t is TagDTO => t != null)
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      state.error = msg || '加载标签失败'
      state.list = []
    } finally {
      state.loading = false
    }
  }

  async function create(name: string) {
    const res = await managementTagApi.create({ name })
    const dto = res.data as TagDTO | null
    if (dto) state.list.push(dto)
    return dto
  }

  async function deleteTag(id: number) {
    await managementTagApi.delete(id)
    state.list = state.list.filter((t) => t && t.id !== id)
  }

  return {
    ...toRefs(state),
    fetchList,
    create,
    delete: deleteTag,
  }
})
