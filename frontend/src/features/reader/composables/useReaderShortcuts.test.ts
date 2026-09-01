import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useReaderSettingsStore } from '@/features/reader/settings-store'
import { useReaderStore } from '@/features/reader/store'
import { useReaderShortcuts } from './useReaderShortcuts'

class ReaderImageElement {
  closest(selector: string): ReaderImageElement | null {
    return selector === '.reader-image-item' ? this : null
  }
}

describe('阅读器双击原图快捷操作', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.stubGlobal('Element', ReaderImageElement)
  })

  it('重复双击当前图片只保留 HQ 强制状态，不切回 LQ', () => {
    const readerStore = useReaderStore()
    const readerSettings = useReaderSettingsStore()
    const forceHqPages = new Set<number>()
    readerStore.currentPage = 3
    const { onDblClick } = useReaderShortcuts({
      isPagedMode: ref(false),
      readerStore,
      readerSettings,
      forceHqPages,
      onPageRequest: vi.fn(),
    })
    const event = { target: new ReaderImageElement() } as unknown as MouseEvent

    onDblClick(event)
    onDblClick(event)

    expect(forceHqPages).toEqual(new Set([2]))
  })
})
