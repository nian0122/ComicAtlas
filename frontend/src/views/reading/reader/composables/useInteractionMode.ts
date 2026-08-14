import { computed } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { BREAKPOINTS, useBreakpoint } from '@/composables/useBreakpoint'
import { useMediaQuery } from '@/composables/useMediaQuery'
import { isMobileBrowserUserAgent } from '@/utils/device'

/** 阅读器交互模式：desktop（鼠标/键盘）或 mobile（触摸） */
export type InteractionMode = 'desktop' | 'mobile'

/**
 * 交互能力上下文。
 *
 * 提供能力描述而非设备标签，组件可按需取用
 * （如 Library 只需 supportsHover，不需 mode）。
 */
export interface InteractionContext {
  /** 当前交互模式，随视口宽度与指针类型响应式更新 */
  mode: ComputedRef<InteractionMode>
  /** 主指针是否为粗糙指针（触摸屏） */
  coarsePointer: Ref<boolean>
  /** 设备是否支持 hover 悬停 */
  supportsHover: Ref<boolean>
}

/**
 * 检测阅读器交互模式。
 *
 * 视口宽度 ≤ BREAKPOINTS.tablet、pointer: coarse 且移动端 User-Agent 命中时为 'mobile'。
 * Safari 请求桌面版网站后会发送桌面 User-Agent，因此自动使用 'desktop'。
 */
export function useInteractionMode(): InteractionContext {
  const width = useBreakpoint()
  const coarsePointer = useMediaQuery('(pointer: coarse)')
  const supportsHover = useMediaQuery('(hover: hover)')

  const mode = computed<InteractionMode>(() =>
    width.value <= BREAKPOINTS.tablet &&
    coarsePointer.value &&
    isMobileBrowserUserAgent()
      ? 'mobile'
      : 'desktop',
  )

  return { mode, coarsePointer, supportsHover }
}
