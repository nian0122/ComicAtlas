export interface HqReleasePreview {
  scopeType: 'comic' | 'chapter'
  scopeId: string
  allowed: boolean
  images: number
  available: number
  released: number
  originalBytes: number
  truncated: boolean
  blockers: string[]
}
