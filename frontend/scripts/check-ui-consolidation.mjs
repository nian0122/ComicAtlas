import { readdir, readFile } from 'node:fs/promises'
import { join, relative } from 'node:path'

const sourceRoot = join(process.cwd(), 'src')
const forbiddenPageClasses = /\.(primary-btn|ghost-btn|poster-btn|overlay-btn|hero-btn|status-badge|spinner)\b/
const deepSharedImport = /@\/shared\/ui\/[^'"\n]+\/[^'"\n]+\.vue/
const violations = []

async function collectFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const entryPath = join(directory, entry.name)
    if (entry.isDirectory()) files.push(...(await collectFiles(entryPath)))
    else if (/\.(vue|ts|js|mjs)$/.test(entry.name)) files.push(entryPath)
  }
  return files
}

for (const filePath of await collectFiles(join(sourceRoot, 'pages'))) {
  const source = await readFile(filePath, 'utf8')
  const relativePath = relative(process.cwd(), filePath)
  if (forbiddenPageClasses.test(source)) violations.push(`${relativePath}: 禁止重复定义公共 UI 类名`)
  if (deepSharedImport.test(source)) violations.push(`${relativePath}: 必须通过 shared/ui public API 引用组件`)
}

if (violations.length > 0) {
  console.error(violations.join('\n'))
  process.exitCode = 1
} else {
  console.log('UI consolidation check passed.')
}
