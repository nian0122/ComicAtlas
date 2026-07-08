import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'

export type QualityMode = 'AUTO' | 'HQ_ONLY' | 'LQ_ONLY'
export type FitMode = 'AUTO' | 'WIDTH' | 'HEIGHT' | 'ORIGINAL'
export type ReadingDirection = 'ltr' | 'rtl' | 'vertical' | 'horizontal'

export const ZOOM_LEVELS = [50, 75, 100, 125, 150, 200] as const

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
    fitMode: saved.fitMode ?? 'AUTO',
    zoom: saved.zoom ?? 100,
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
    const nearest = ZOOM_LEVELS.reduce((prev, curr) =>
      Math.abs(curr - value) < Math.abs(prev - value) ? curr : prev
    )
    state.zoom = nearest
    save()
  }

  function zoomIn() {
    const current = state.zoom
    const next = ZOOM_LEVELS.find((level) => level > current)
    if (next) {
      state.zoom = next
      save()
    }
  }

  function zoomOut() {
    const current = state.zoom
    const prev = [...ZOOM_LEVELS].reverse().find((level) => level < current)
    if (prev) {
      state.zoom = prev
      save()
    }
  }

  function resetZoom() {
    state.zoom = 100
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
    zoomIn,
    zoomOut,
    resetZoom,
    setReadingDirection,
    toggleToolbar,
    togglePreload,
    toggleProgressiveImage,
  }
})
