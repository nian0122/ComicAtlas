import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'

export type QualityMode = 'AUTO' | 'HQ_ONLY' | 'LQ_ONLY'
export type FitMode = 'auto' | 'fit-width' | 'fit-height' | 'fit-screen' | 'original'
export type ReadingDirection = 'ltr' | 'rtl' | 'vertical'

export interface ReaderSettingsState {
  qualityMode: QualityMode
  fitMode: FitMode
  zoom: number
  readingDirection: ReadingDirection
  showToolbar: boolean
  preloadWindow: number
  enablePreload: boolean
  enableProgressiveImage: boolean
}

const STORAGE_KEY = 'comicatlas.reader.settings'

function loadSettings(): Partial<ReaderSettingsState> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

export const useReaderSettingsStore = defineStore('reader-settings', () => {
  const saved = loadSettings()

  const state = reactive<ReaderSettingsState>({
    qualityMode: saved.qualityMode ?? 'AUTO',
    fitMode: saved.fitMode ?? 'auto',
    zoom: saved.zoom ?? 1,
    readingDirection: saved.readingDirection ?? 'ltr',
    showToolbar: saved.showToolbar ?? true,
    preloadWindow: saved.preloadWindow ?? 2,
    enablePreload: saved.enablePreload ?? true,
    enableProgressiveImage: saved.enableProgressiveImage ?? true,
  })

  function save() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        qualityMode: state.qualityMode,
        fitMode: state.fitMode,
        zoom: state.zoom,
        readingDirection: state.readingDirection,
        showToolbar: state.showToolbar,
        preloadWindow: state.preloadWindow,
        enablePreload: state.enablePreload,
        enableProgressiveImage: state.enableProgressiveImage,
      }))
    } catch {
      // ignore storage errors
    }
  }

  function setQualityMode(mode: QualityMode) {
    state.qualityMode = mode
    save()
  }

  function setFitMode(mode: FitMode) {
    state.fitMode = mode
    save()
  }

  function setZoom(value: number) {
    state.zoom = Math.max(0.25, Math.min(5, value))
    save()
  }

  function resetZoom() {
    state.zoom = 1
    save()
  }

  function setReadingDirection(direction: ReadingDirection) {
    state.readingDirection = direction
    save()
  }

  function toggleToolbar() {
    state.showToolbar = !state.showToolbar
    save()
  }

  function togglePreload() {
    state.enablePreload = !state.enablePreload
    save()
  }

  function toggleProgressiveImage() {
    state.enableProgressiveImage = !state.enableProgressiveImage
    save()
  }

  return {
    ...toRefs(state),
    setQualityMode,
    setFitMode,
    setZoom,
    resetZoom,
    setReadingDirection,
    toggleToolbar,
    togglePreload,
    toggleProgressiveImage,
  }
})
