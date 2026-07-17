import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'
import { categoryApi } from '@/services/management'
import type { CategoryDTO } from '@/types'

export interface CategoryState {
  list: CategoryDTO[]
  loading: boolean
  error: string | null
}

export const useCategoryStore = defineStore('category', () => {
  const state = reactive<CategoryState>({
    list: [],
    loading: false,
    error: null,
  })

  async function fetchList() {
    state.loading = true
    state.error = null
    try {
      const res = await categoryApi.list()
      state.list = ((res.data as CategoryDTO[]) || []).filter((c): c is CategoryDTO => c != null)
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      state.error = msg || '加载分类失败'
      state.list = []
    } finally {
      state.loading = false
    }
  }

  async function create(name: string) {
    const res = await categoryApi.create(name)
    const dto = res.data as CategoryDTO | null
    if (dto) state.list.push(dto)
    return dto
  }

  async function update(id: number, name: string) {
    const res = await categoryApi.update(id, name)
    const updated = res.data as CategoryDTO | null
    if (!updated) return
    const idx = state.list.findIndex((c) => c && c.id === id)
    if (idx >= 0) {
      state.list[idx] = updated
    }
    return updated
  }

  async function remove(id: number) {
    await categoryApi.delete(id)
    state.list = state.list.filter((c) => c && c.id !== id)
  }

  return {
    ...toRefs(state),
    fetchList,
    create,
    update,
    remove,
  }
})
