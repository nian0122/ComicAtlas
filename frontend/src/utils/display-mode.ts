import { BREAKPOINTS } from '@/composables/useBreakpoint'

export type DisplayMode = 'auto' | 'mobile' | 'desktop'

export const DISPLAY_MODE_STORAGE_KEY = 'comic-atlas.display-mode'

const DISPLAY_MODES: ReadonlySet<DisplayMode> = new Set(['auto', 'mobile', 'desktop'])

export function getStoredDisplayMode(): DisplayMode {
  if (typeof window === 'undefined') return 'auto'
  const storedMode = window.localStorage.getItem(DISPLAY_MODE_STORAGE_KEY)
  return storedMode !== null && DISPLAY_MODES.has(storedMode as DisplayMode)
    ? (storedMode as DisplayMode)
    : 'auto'
}

export function isMobileDisplayMode(mode: DisplayMode): boolean {
  if (mode === 'mobile') return true
  if (mode === 'desktop') return false
  if (typeof window === 'undefined') return false
  return window.matchMedia('(pointer: coarse)').matches && window.innerWidth <= BREAKPOINTS.tablet
}
