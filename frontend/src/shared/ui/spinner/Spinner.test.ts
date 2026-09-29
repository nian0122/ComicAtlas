import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'
import Spinner from './Spinner.vue'

describe('Spinner 公共契约', () => {
  it('输出指定尺寸和颜色的装饰性加载指示器', async () => {
    const renderedMarkup = await renderToString(createSSRApp(Spinner, { size: 'small', color: 'current' }))

    expect(renderedMarkup).toContain('app-spinner--small')
    expect(renderedMarkup).toContain('app-spinner--current')
    expect(renderedMarkup).toContain('aria-hidden="true"')
  })

  it('默认使用 medium 尺寸和 accent 色', async () => {
    const renderedMarkup = await renderToString(createSSRApp(Spinner))

    expect(renderedMarkup).toContain('app-spinner--medium')
    expect(renderedMarkup).toContain('app-spinner--accent')
  })
})
