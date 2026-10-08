import { onBeforeUnmount, onMounted } from 'vue'

interface ShortVideoKeyboardOptions {
  isVideo: () => boolean
  isDialogOpen: () => boolean
  move: (direction: -1 | 1) => void
  togglePlayback: () => void
  seek: (seconds: number) => void
  toggleMute: () => void
  toggleFullscreen: () => void
  closeDialog: () => void
  showControls: () => void
}

/** 全局快捷键不覆盖文本编辑、组合输入和局部控件已经处理的按键。 */
export function createShortVideoKeyboardHandler(options: ShortVideoKeyboardOptions) {
  return (event: KeyboardEvent): void => {
    if (event.defaultPrevented || event.isComposing || event.ctrlKey || event.metaKey || event.altKey) return
    if (
      event.target instanceof Element &&
      event.target.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"])')
    )
      return

    if (options.isDialogOpen()) {
      if (event.key === 'Escape') {
        event.preventDefault()
        options.closeDialog()
      }
      return
    }

    const key = event.key.toLowerCase()
    if (event.repeat && !['arrowleft', 'arrowright'].includes(key)) return
    let action: (() => void) | undefined
    if (key === 'arrowdown') action = () => options.move(1)
    else if (key === 'arrowup') action = () => options.move(-1)
    else if (key === 'f') action = options.toggleFullscreen
    else if (options.isVideo()) {
      if (key === ' ' || key === 'k' || event.code === 'Space') action = options.togglePlayback
      else if (key === 'arrowleft') action = () => options.seek(event.shiftKey ? -10 : -5)
      else if (key === 'arrowright') action = () => options.seek(event.shiftKey ? 10 : 5)
      else if (key === 'm') action = options.toggleMute
    }
    if (!action) return
    // 消费空格，避免按钮保留焦点时又触发一次原生 click。
    event.preventDefault()
    options.showControls()
    action()
  }
}

export function useShortVideoKeyboard(options: ShortVideoKeyboardOptions): void {
  const handleKeydown = createShortVideoKeyboardHandler(options)
  onMounted(() => document.addEventListener('keydown', handleKeydown))
  onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))
}
