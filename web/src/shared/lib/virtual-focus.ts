import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 虚拟列表会回收列表项的 DOM，正在移动的焦点会落回 body。
 * VirtualGrid、管理端页块和管理端目录树共用这一套恢复规则：
 * 只在没有指针操作、焦点目标为空、元素仍属于同一 item 且仍在列表内时，于下一帧恢复焦点，
 * 不抢用户主动点击或 Tab 离开后的焦点。
 */
export function useVirtualFocus(options: {
  keyOf: (element: HTMLElement) => string
  within: () => HTMLElement | undefined
}) {
  let element: HTMLElement | null = null,
    key = '',
    lastPointerDown = 0
  function onPointerDown() {
    lastPointerDown = performance.now()
  }
  function onFocusIn(event: FocusEvent) {
    if (!(event.target instanceof HTMLElement)) return
    const next = options.keyOf(event.target)
    if (!next) {
      element = null
      return
    }
    element = event.target
    key = next
  }
  function onFocusOut(event: FocusEvent) {
    const current = event.target as HTMLElement
    if (current !== element) return
    const expected = key
    element = null
    if (event.relatedTarget || !document.hasFocus()) return
    if (performance.now() - lastPointerDown < 300) return
    requestAnimationFrame(() => {
      if (document.activeElement !== document.body || !current.isConnected) return
      if (options.keyOf(current) !== expected) return
      if (!options.within()?.contains(current)) return
      current.focus({ preventScroll: true })
    })
  }
  onMounted(() => document.addEventListener('pointerdown', onPointerDown, true))
  onBeforeUnmount(() => document.removeEventListener('pointerdown', onPointerDown, true))
  return { onFocusIn, onFocusOut }
}
