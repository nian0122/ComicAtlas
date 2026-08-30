import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'
import { getApiErrorMessage } from '@/services/http'
import { managementCategoryApi } from '@/features/comic/management-api'
import type { CategoryDTO } from '@/entities/comic/types'

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
      const res = await managementCategoryApi.list()
      state.list = res.data
    } catch (err: unknown) {
      state.error = getApiErrorMessage(err, '加载分类失败')
      state.list = []
    } finally {
      state.loading = false
    }
  }

  async function create(name: string) {
    const res = await managementCategoryApi.create(name)
    const dto = res.data
    if (dto) state.list.push(dto)
    return dto
  }

  async function update(id: number, name: string) {
    const res = await managementCategoryApi.update(id, name)
    const updated = res.data
    if (!updated) return
    const idx = state.list.findIndex((c) => c && c.id === id)
    if (idx >= 0) {
      state.list[idx] = updated
    }
    return updated
  }

  async function remove(id: number) {
    await managementCategoryApi.delete(id)
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
