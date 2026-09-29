import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'
import ManagementPanel from './ManagementPanel.vue'

describe('ManagementPanel 公共契约', () => {
  it('支持不同内边距和 flush 展示', async () => {
    const renderedMarkup = await renderToString(createSSRApp(ManagementPanel, { padding: 'compact', flush: true }))

    expect(renderedMarkup).toContain('management-panel--compact')
    expect(renderedMarkup).toContain('management-panel--flush')
  })

  it('默认使用标准间距', async () => {
    const renderedMarkup = await renderToString(createSSRApp(ManagementPanel))

    expect(renderedMarkup).toContain('management-panel--default')
  })
})
