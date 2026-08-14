import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  DISPLAY_MODE_STORAGE_KEY,
  getStoredDisplayMode,
  isMobileDisplayMode,
  type DisplayMode,
} from '@/utils/display-mode'

export const useDisplayModeStore = defineStore('display-mode', () => {
  const mode = ref<DisplayMode>(getStoredDisplayMode())
  const isMobile = computed(() => isMobileDisplayMode(mode.value))
  const isDesktop = computed(() => !isMobile.value)

  function applyMode(nextMode: DisplayMode): void {
    mode.value = nextMode
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(DISPLAY_MODE_STORAGE_KEY, nextMode)
      document.documentElement.dataset.displayMode = nextMode
    }
  }

  function toggleExplicitMode(): void {
    applyMode(isDesktop.value ? 'mobile' : 'desktop')
  }

  if (typeof document !== 'undefined') {
    document.documentElement.dataset.displayMode = mode.value
  }

  return { mode, isMobile, isDesktop, applyMode, toggleExplicitMode }
})
