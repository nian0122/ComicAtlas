import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const componentSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'ContentState.vue'), 'utf8')

describe('ContentState 公共契约', () => {
  it('支持加载、错误、空态和 action/icon 插槽', () => {
    expect(componentSource).toContain("'loading' | 'error' | 'empty'")
    expect(componentSource).toContain('$slots.icon')
    expect(componentSource).toContain('$slots.default')
    expect(componentSource).toContain('content-state__spinner')
  })
})
