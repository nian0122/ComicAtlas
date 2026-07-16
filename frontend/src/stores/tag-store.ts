import { defineStore } from 'pinia'
import { ref } from 'vue'
import { tagApi } from '@/services/management'

export const useTagStore = defineStore('tag', () => {
  const tags = ref<any[]>([])
  async function fetch() { const res: any = await tagApi.list(); tags.value = res.data || [] }
  return { tags, fetch }
})
