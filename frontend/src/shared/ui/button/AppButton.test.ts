import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const componentSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'AppButton.vue'), 'utf8')

describe('AppButton 公共契约', () => {
  it('声明完整 variant、尺寸和交互属性', () => {
    expect(componentSource).toContain("'primary' | 'secondary' | 'ghost' | 'danger' | 'text' | 'overlay'")
    expect(componentSource).toContain("'sm' | 'default' | 'lg'")
    expect(componentSource).toContain('loading?: boolean')
    expect(componentSource).toContain('iconOnly?: boolean')
    expect(componentSource).toContain(':disabled="disabled || loading"')
  })

  it('保留插槽内容、加载语义和键盘焦点样式', () => {
    expect(componentSource).toContain('<slot />')
    expect(componentSource).toContain(':aria-busy="loading || undefined"')
    expect(componentSource).toContain('.app-button:focus-visible')
  })
})
