import type { Ref } from 'vue'
import type { useReaderSettingsStore } from '@/features/reader/settings-store'
import type { useReaderStore } from '@/features/reader/store'
import { isReaderInteractiveTarget } from '@/features/reader/composables/useReaderGesture'

type ReaderStore = ReturnType<typeof useReaderStore>
type ReaderSettingsStore = ReturnType<typeof useReaderSettingsStore>

export function useReaderShortcuts(options: {
  isPagedMode: Readonly<Ref<boolean>>
  readerStore: ReaderStore
  readerSettings: ReaderSettingsStore
  forceHqPages: Set<number>
  onPageRequest: (direction: 'next' | 'prev') => void
}) {
  const { isPagedMode, readerStore, readerSettings, forceHqPages, onPageRequest } = options

  function onKeydown(event: KeyboardEvent) {
    if (isReaderInteractiveTarget(event.target)) return

    if (event.key === 'ArrowRight' || event.key === 'ArrowDown' || event.key === ' ') {
      event.preventDefault()
      if (isPagedMode.value) onPageRequest('next')
      else readerStore.nextPage()
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (isPagedMode.value) onPageRequest('prev')
      else readerStore.prevPage()
    } else if (event.key === '+' || event.key === '=') {
      event.preventDefault()
      readerSettings.zoomIn()
    } else if (event.key === '-') {
      event.preventDefault()
      readerSettings.zoomOut()
    } else if (event.key === '0') {
      event.preventDefault()
      readerSettings.resetZoom()
    }
  }

  function onWheel(event: WheelEvent) {
    if (isReaderInteractiveTarget(event.target)) return
    if (!event.ctrlKey && !event.metaKey) return
    event.preventDefault()
    if (event.deltaY < 0) readerSettings.zoomIn()
    else readerSettings.zoomOut()
  }

  function onDblClick(event: MouseEvent) {
    if (isReaderInteractiveTarget(event.target) || !(event.target instanceof Element)) return
    const targetElement = event.target
    const isViewport = targetElement.closest('.reader-viewport') || targetElement.closest('.paged-viewport')
    const isImage = targetElement.closest('.reader-image-item')

    if (isImage) {
      const pageIndex = readerStore.currentPage - 1
      if (forceHqPages.has(pageIndex)) forceHqPages.delete(pageIndex)
      else forceHqPages.add(pageIndex)
    } else if (isViewport) {
      readerSettings.resetZoom()
    }
  }

  return { onKeydown, onWheel, onDblClick }
}
