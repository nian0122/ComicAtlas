import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const sourceRoot = path.resolve('src')
const layers = new Set(['app', 'pages', 'widgets', 'features', 'entities', 'shared'])
const sourceExtensions = new Set(['.ts', '.vue'])
const importPattern = /(?:from\s+|import\s*\()(['"])(@\/[^'"\n]+)\1/g
const violations = []

function collectSourceFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name)
    if (entry.isDirectory()) return collectSourceFiles(entryPath)
    return sourceExtensions.has(path.extname(entry.name)) && !entry.name.endsWith('.test.ts') ? [entryPath] : []
  })
}

function sliceKey(aliasPath) {
  const segments = aliasPath.split('/')
  if (segments[0] === 'pages') return segments.slice(0, 3).join('/')
  if (segments[0] === 'shared' || segments[0] === 'app') return segments[0]
  return segments.slice(0, 2).join('/')
}

function isPublicAlias(aliasPath) {
  const segments = aliasPath.split('/')
  if (segments.at(-1) === 'index') return true
  if (segments[0] === 'shared' || segments[0] === 'app') return true
  if (segments[0] === 'pages') return segments.length === 3
  if (segments.length === 2) return true
  return segments.length === 3 && ['api', 'ui'].includes(segments.at(-1))
}

for (const sourceFile of collectSourceFiles(sourceRoot)) {
  const relativeSource = path.relative(sourceRoot, sourceFile).replaceAll(path.sep, '/')
  const sourceSegments = relativeSource.split('/')
  const sourceSlice = sliceKey(sourceSegments.join('/'))
  const sourceText = fs.readFileSync(sourceFile, 'utf8')
  for (const match of sourceText.matchAll(importPattern)) {
    const aliasPath = match[2].slice(2)
    const targetLayer = aliasPath.split('/')[0]
    if (!layers.has(targetLayer) || targetLayer === 'shared' || !aliasPath.includes('/')) continue
    if (sliceKey(aliasPath) !== sourceSlice && !isPublicAlias(aliasPath)) {
      violations.push(`${relativeSource}: ${match[2]}`)
    }
  }
}

if (violations.length > 0) {
  console.error('发现跨切片深层引用：')
  for (const violation of violations) console.error(`- ${violation}`)
  process.exitCode = 1
} else {
  console.log('FSD import boundary passed')
}
