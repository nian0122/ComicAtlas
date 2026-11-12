export function comicCoverUrl(id: string) {
  return `/api/v1/comics/${encodeURIComponent(id)}/cover`
}
