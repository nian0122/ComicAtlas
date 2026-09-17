/**
 * 漫画业务状态 → ComicPoster 展示状态的映射。
 *
 * 规则来源：docs/superpowers/specs/2026-07-13-netflix-frontend-design.md §7.3
 */
export interface PosterProps {
  id: string | number
  coverUrl: string
  title: string
  subtitle?: string
  progress?: number
  status?: 'ready' | 'importing' | 'pending' | 'failed'
  size: 'sm' | 'md' | 'lg'
  showProgress?: boolean
  showSubtitle?: boolean
  showHover?: boolean
  showButtons?: boolean
}

export type PosterStatus = PosterProps['status']

export function toPosterStatus(comicStatus: string): PosterStatus {
  switch (comicStatus) {
    case 'SUCCESS':
      return 'ready'
    case 'PENDING':
    case 'PARSING':
      return 'pending'
    case 'IMPORTING':
    case 'DOWNLOADING':
    case 'EXTRACTING':
      return 'importing'
    case 'FAILED':
    case 'CANCELLED':
      return 'failed'
    default:
      return 'ready'
  }
}
