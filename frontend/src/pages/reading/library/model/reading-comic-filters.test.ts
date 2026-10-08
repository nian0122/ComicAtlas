import { effectScope, type EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useReadingComicFilters } from './reading-comic-filters'

let scope: EffectScope
beforeEach(() => {
  scope = effectScope()
  vi.useFakeTimers()
})
afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})
function setup() {
  const onApply = vi.fn()
  return { onApply, ...scope.run(() => useReadingComicFilters(onApply))! }
}

describe('阅读端专属筛选', () => {
  it('始终限制可阅读状态，不携带管理端文件和来源条件', () => {
    const model = setup()
    model.restoreFilters({ status: 'TRASHED', sourceType: 'ZIP', keyword: ' 漫画 ', tags: ['热血'], tagMode: 'NOT' })
    const query = model.buildQuery()
    expect(query).toMatchObject({ status: 'READY', keyword: '漫画', tagMode: 'NOT' })
    expect(query).not.toHaveProperty('hqStatus')
    expect(query).not.toHaveProperty('lqStatus')
    expect(query).not.toHaveProperty('sourceType')
  })

  it('桌面多选和移动标签按钮遵循相同的无标签互斥规则', () => {
    const model = setup()
    model.filters.selectedTags = ['热血']
    model.filters.selectedTags = ['热血', '_NONE']
    expect(model.filters.selectedTags).toEqual(['_NONE'])
    model.filters.selectedTags = ['_NONE', '冒险']
    expect(model.filters.selectedTags).toEqual(['冒险'])
    model.toggleTag('_NONE')
    model.toggleTag('热血')
    expect(model.filters.selectedTags).toEqual(['热血'])
  })

  it('清除筛选取消待搜索关键词，并恢复默认排序', () => {
    const model = setup()
    model.filters.keyword = '旧搜索'
    model.filters.sort = 'lastReadTime'
    model.scheduleKeywordSearch()
    model.clearFilters()
    vi.advanceTimersByTime(400)
    expect(model.onApply).toHaveBeenCalledOnce()
    expect(model.buildQuery()).toMatchObject({ keyword: undefined, sort: 'createdAt', order: 'desc', status: 'READY' })
  })

  it('恢复非法路由参数时使用默认值，卸载后取消搜索', () => {
    const model = setup()
    model.restoreFilters({ sort: 'unknown' as 'title', tags: ['_NONE', '热血'], tagMode: 'NOT' })
    expect(model.filters.sort).toBe('createdAt')
    expect(model.filters.selectedTags).toEqual(['_NONE'])
    expect(model.filters.tagMode).toBe('OR')
    model.scheduleKeywordSearch()
    scope.stop()
    vi.advanceTimersByTime(400)
    expect(model.onApply).not.toHaveBeenCalled()
  })
})
