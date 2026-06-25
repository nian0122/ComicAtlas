export function buildHqUrl(comicId: number, chapterNo: string, imageName: string): string {
  return `/comic/hq/${comicId}/${chapterNo}/${imageName}`
}

export function buildLqUrl(comicId: number, chapterNo: string, baseName: string): string {
  return `/comic/lq/${comicId}/${chapterNo}/${baseName}.webp`
}
