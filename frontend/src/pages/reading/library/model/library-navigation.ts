let savedLibraryScrollY: number | null = null

/** 离开漫画库进入详情时暂存窗口位置，供返回漫画库时恢复。 */
export function saveLibraryScrollPosition(scrollY: number): void {
  savedLibraryScrollY = Math.max(0, scrollY)
}

/** 读取并清除暂存位置，避免之后重新进入漫画库时跳到旧位置。 */
export function consumeLibraryScrollPosition(): number | null {
  const scrollY = savedLibraryScrollY
  savedLibraryScrollY = null
  return scrollY
}
