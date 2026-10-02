import { test } from 'node:test'
import assert from 'node:assert/strict'
import { validateStyleContracts } from './check-style-contracts.mjs'

test('公共 token、运行时 viewport 与第三方变量均可解析', () => {
  assert.deepEqual(
    validateStyleContracts([
      { path: 'src/app/styles/tokens.css', source: ':root { --bg: #111; }' },
      { path: 'src/app/App.vue', source: "style.setProperty('--height', '100px')" },
      {
        path: 'src/shared/ui/Panel.vue',
        source: '.panel { color: var(--bg); height: var(--height); border-color: var(--el-border); }',
      },
    ]),
    [],
  )
})
test('缺失 token 即使带备用值也会阻断', () => {
  assert.match(
    validateStyleContracts([
      { path: 'src/pages/example/ui/page.css', source: '.page { color: var(--missing, red); }' },
    ])[0],
    /未定义的样式 token --missing/,
  )
})
test('页面布局可以覆盖，重复定义控件主题会阻断', () => {
  assert.deepEqual(
    validateStyleContracts([
      { path: 'src/pages/example/ui/page.css', source: '.form :deep(.el-select__wrapper) { width: 100%; }' },
    ]),
    [],
  )
  assert.match(
    validateStyleContracts([
      { path: 'src/pages/example/ui/page.css', source: '.form :deep(.el-select__wrapper) { background: red; }' },
    ])[0],
    /公共表单外观/,
  )
})
test('移动阅读筛选的独立外观不被统一成管理控件', () => {
  assert.deepEqual(
    validateStyleContracts([
      {
        path: 'src/pages/reading/library/ui/library.css',
        source: '.filter-select :deep(.el-select__wrapper) { border-radius: 999px; }',
      },
    ]),
    [],
  )
})
