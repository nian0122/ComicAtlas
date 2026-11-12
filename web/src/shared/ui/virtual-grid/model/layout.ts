export interface VirtualGridLayout {
  columns: number
  columnGap: number
  rowGap: number
  columnWidth: number
  rowHeight: number
  stride: number
}

export interface VirtualGridLayoutOptions {
  width: number
  compact: boolean
  columns: number
  compactColumns: number
  aspectRatio: number
  columnGap: number
  rowGap: number
  compactColumnGap: number
  compactRowGap: number
}

/**
 * 封面网格的尺寸换算集中在纯函数里，组件只负责测量容器宽度和视口断点。
 * 行高包含行间距，itemSize 因此可直接作为 RecycleScroller 的行高。
 */
export function computeVirtualGridLayout(options: VirtualGridLayoutOptions): VirtualGridLayout | null {
  if (!Number.isFinite(options.width) || options.width <= 0) return null
  const columns = Math.max(1, Math.floor(options.compact ? options.compactColumns : options.columns))
  const columnGap = Math.max(0, options.compact ? options.compactColumnGap : options.columnGap)
  const rowGap = Math.max(0, options.compact ? options.compactRowGap : options.rowGap)
  const columnWidth = Math.max(0, (options.width - columnGap * (columns - 1)) / columns)
  return {
    columns,
    columnGap,
    rowGap,
    columnWidth,
    rowHeight: columnWidth * options.aspectRatio + rowGap,
    stride: columnWidth + columnGap,
  }
}
