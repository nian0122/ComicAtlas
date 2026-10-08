import { createPinia, setActivePinia } from 'pinia'
import { effectScope, type EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useManagementComicStore } from './management-comic-store'
import { useManagementComicListFilters } from './management-comic-filters'

let scope: EffectScope
beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  scope = effectScope()
})
afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})

function setup() {
  const store = useManagementComicStore()
  const search = vi.spyOn(store, 'search').mockResolvedValue()
  const onApply = vi.fn()
  const model = scope.run(() => useManagementComicListFilters(store, onApply))!
  return { store, search, onApply, ...model }
}

describe('管理端专属筛选', () => {
  it('默认不限制生命周期，支持文件完整性与来源组合', () => {
    const model = setup()
    expect(model.buildQuery().status).toBeUndefined()
    model.filters.hqStatus = 'PARTIAL_HQ'
    model.filters.lqStatus = 'ALL_LQ'
    model.filters.sourceType = 'ZIP'
    model.filters.keyword = ' 漫画 '
    void model.applyFilters()
    expect(model.search).toHaveBeenCalledWith(
      expect.objectContaining({
        keyword: '漫画',
        hqStatus: 'PARTIAL_HQ',
        lqStatus: 'ALL_LQ',
        sourceType: 'ZIP',
        status: undefined,
      }),
    )
    expect(model.onApply).toHaveBeenCalledOnce()
  })

  it('无标签与普通标签双向切换保留本次选择', () => {
    const model = setup()
    model.filters.tags = ['热血']
    model.filters.tags = ['热血', '_NONE']
    expect(model.filters.tags).toEqual(['_NONE'])
    model.filters.tags = ['_NONE', '冒险']
    expect(model.filters.tags).toEqual(['冒险'])
    model.filters.tagMode = 'NOT'
    model.filters.tags = []
    expect(model.buildQuery()).toMatchObject({ tags: undefined, tagMode: 'OR' })
  })

  it('立即应用和重置取消待执行搜索，重置保留分页大小', () => {
    const model = setup()
    model.store.updateQuery({ page: 4, size: 50 })
    model.filters.keyword = '旧搜索'
    model.scheduleKeywordSearch()
    model.filters.status = 'IMPORT_FAILED'
    void model.applyFilters()
    vi.advanceTimersByTime(400)
    expect(model.search).toHaveBeenCalledOnce()
    void model.resetFilters()
    expect(model.store.query.size).toBe(50)
    expect(model.search).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: undefined, status: undefined }))
  })

  it('恢复无标签时清除旧排除模式，卸载后不提交防抖请求', () => {
    const model = setup()
    model.store.updateQuery({ tags: ['_NONE'], tagMode: 'NOT', hqStatus: 'ALL_HQ' })
    model.restoreFilters()
    expect(model.filters.tagMode).toBe('OR')
    expect(model.filters.hqStatus).toBe('ALL_HQ')
    model.scheduleKeywordSearch()
    scope.stop()
    vi.advanceTimersByTime(400)
    expect(model.search).not.toHaveBeenCalled()
  })
})
