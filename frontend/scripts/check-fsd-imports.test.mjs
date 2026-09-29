import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import { boundaryViolation, checkArchitecture, importsOf } from './check-fsd-imports.mjs'

test('六层只允许向下依赖，同切片内部允许导入', () => {
  assert.equal(boundaryViolation('pages/reader/ui/Reader.vue', 'widgets/reader/index.ts'), null)
  assert.equal(boundaryViolation('features/upload/ui/Form.vue', 'features/upload/model/state.ts'), null)
  assert.match(boundaryViolation('widgets/reader/model/state.ts', 'pages/reader/index.ts'), /向上/)
  assert.match(boundaryViolation('features/upload/model/state.ts', 'features/import/index.ts'), /同层/)
  assert.match(boundaryViolation('shared/api/http.ts', 'entities/comic/index.ts'), /向上/)
})

test('页面分组不合并切片，reader 直属页面同样要求 public API', () => {
  assert.match(boundaryViolation('pages/reading/home/ui/Home.vue', 'pages/reading/library/index.ts'), /同层/)
  assert.equal(boundaryViolation('app/router/index.ts', 'pages/reader/index.ts'), null)
  assert.match(boundaryViolation('app/router/index.ts', 'pages/reader/ui/index.ts'), /根 index/)
  assert.match(boundaryViolation('pages/reader/index.ts', 'entities/media/ui/index.ts'), /根 index/)
})

test('实体 @x 出口仅对具名消费者开放', () => {
  assert.equal(boundaryViolation('entities/comic/model/types.ts', 'entities/media/@x/comic.ts'), null)
  assert.match(boundaryViolation('entities/task/model/types.ts', 'entities/media/@x/comic.ts'), /同层/)
  assert.match(boundaryViolation('features/upload/model/state.ts', 'entities/media/@x/comic.ts'), /根 index/)
  assert.match(boundaryViolation('features/upload/model/state.ts', 'features/import/@x/upload.ts'), /同层/)
})

test('禁止自身 barrel 循环与绕过通用 UI 入口', () => {
  assert.match(boundaryViolation('entities/media/ui/Card.vue', 'entities/media/index.ts'), /自身 public API/)
  assert.equal(boundaryViolation('pages/reader/index.ts', 'shared/ui/button/index.ts'), null)
  assert.match(boundaryViolation('pages/reader/index.ts', 'shared/ui/button/AppButton.vue'), /通用 UI/)
})

test('语法树识别类型、副作用、动态导入和重导出，忽略注释与字符串', () => {
  assert.deepEqual(
    importsOf(
      `
    // import '@/features/fake'
    const example = "import '@/features/fake'"
    import '@/shared/setup'
    import type { A } from '@/entities/a'
    export { B } from '../b'
    const lazy = import('../c')
    type C = import('../d').C
    const module = require('../e')
  `,
      'test.ts',
    ),
    ['@/shared/setup', '@/entities/a', '../b', '../c', '../d', '../e'],
  )
})

test('Vue 脚本及外置样式均检查依赖', () => {
  assert.deepEqual(
    importsOf(
      `<script setup lang="ts">import { A } from '@/features/a'</script>
    <template><div /></template><style scoped src="../other/style.css"></style>
    <style>@use '@/app/styles/tokens';</style>`,
      'Page.vue',
    ),
    ['@/features/a', '../other/style.css', '@/app/styles/tokens'],
  )
})

test('真实文件检查覆盖相对路径、测试文件、旧目录和无效引用', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'comic-atlas-fsd-'))
  const write = (name, text) => {
    const filename = path.join(root, name)
    fs.mkdirSync(path.dirname(filename), { recursive: true })
    fs.writeFileSync(filename, text)
  }
  try {
    write('features/a/index.ts', 'export {}')
    write(
      'features/a/model/check.test.ts',
      "import '../../b/index'; import '@/entities/item/model/index'; import './missing'",
    )
    write('features/b/index.ts', 'export {}')
    write('entities/item/index.ts', 'export {}')
    write('entities/item/model/index.ts', 'export {}')
    write('views/Legacy.vue', '<template><div /></template>')
    const violations = checkArchitecture(root)
    assert.ok(violations.some((message) => message.includes('check.test.ts') && message.includes('同层')))
    assert.ok(violations.some((message) => message.includes('根 index')))
    assert.ok(violations.some((message) => message.includes('无法解析')))
    assert.ok(violations.some((message) => message.includes('六层之外')))
  } finally {
    // 仅清理本测试使用 mkdtemp 创建的临时目录。
    assert.ok(root.startsWith(path.join(os.tmpdir(), 'comic-atlas-fsd-')))
    fs.rmSync(root, { recursive: true })
  }
})
