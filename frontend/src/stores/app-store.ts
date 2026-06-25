import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAppStore = defineStore('app', () => {
  const theme = ref<'light' | 'dark'>('dark')
  const sidebarCollapsed = ref(false)
  const globalLoading = ref(false)
  const hqMode = ref(false)
  return { theme, sidebarCollapsed, globalLoading, hqMode }
})
