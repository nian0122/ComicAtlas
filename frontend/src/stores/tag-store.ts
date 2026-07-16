import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'
import { tagApi } from '@/services/management'
import type { TagDTO } from '@/types'

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
      const res = await tagApi.list()
      state.list = (res.data as TagDTO[]) || []
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      state.error = msg || '加载标签失败'
      state.list = []
    } finally {
      state.loading = false
    }
  }

  async function create(name: string) {
    const res = await tagApi.create({ name })
    state.list.push(res.data as TagDTO)
    return res.data as TagDTO
  }

  async function deleteTag(id: number) {
    await tagApi.delete(id)
    state.list = state.list.filter((t) => t.id !== id)
  }

  return {
    ...toRefs(state),
    fetchList,
    create,
    delete: deleteTag,
  }
})
