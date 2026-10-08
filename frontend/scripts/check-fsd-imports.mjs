import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
import { parse } from 'vue/compiler-sfc'

const layers = ['app', 'pages', 'widgets', 'features', 'entities', 'shared']
const extensions = ['.ts', '.tsx', '.js', '.jsx', '.mjs', '.vue', '.css', '.scss']
const normalize = (value) => value.replaceAll('\\', '/')

export function sliceOf(filename) {
  const parts = normalize(filename).split('/')
  if (['app', 'shared'].includes(parts[0])) return parts[0]
  // reading、management 只是页面分组，本身不能包含模块。
  const depth = parts[0] === 'pages' && ['reading', 'management'].includes(parts[1]) ? 3 : 2
  return parts.slice(0, depth).join('/')
}

export function importsOf(source, filename) {
  const imports = []
  const scan = (text) => {
    const ast = ts.createSourceFile(filename, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
    const visit = (node) => {
      let specifier
      if (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) specifier = node.moduleSpecifier
      if (ts.isImportTypeNode(node) && ts.isLiteralTypeNode(node.argument)) specifier = node.argument.literal
      if (
        ts.isCallExpression(node) &&
        (node.expression.kind === ts.SyntaxKind.ImportKeyword || node.expression.getText(ast) === 'require')
      )
        specifier = node.arguments[0]
      if (specifier && ts.isStringLiteralLike(specifier)) imports.push(specifier.text)
      ts.forEachChild(node, visit)
    }
    visit(ast)
  }
  const scanStyles = (text) => {
    for (const match of text.matchAll(/@(?:use|forward|import)\s+['"]([^'"]+)['"]/g)) imports.push(match[1])
  }
  if (filename.endsWith('.vue')) {
    const { descriptor, errors } = parse(source, { filename })
    if (errors.length) throw new Error(`${filename}: Vue 解析失败`)
    for (const block of [descriptor.script, descriptor.scriptSetup]) {
      if (block?.src) imports.push(block.src)
      if (block) scan(block.content)
    }
    for (const block of descriptor.styles) {
      if (block.src) imports.push(block.src)
      scanStyles(block.content)
    }
    if (descriptor.template?.src) imports.push(descriptor.template.src)
  } else if (/\.(css|scss)$/.test(filename)) scanStyles(source)
  else scan(source)
  return imports
}

export function boundaryViolation(source, target) {
  const sourceSlice = sliceOf(source)
  const targetSlice = sliceOf(target)
  const sourceLayer = source.split('/')[0]
  const targetLayer = target.split('/')[0]
  if (!layers.includes(targetLayer)) return '目标不属于 FSD 六层'
  if (sourceSlice === targetSlice) {
    if (!['app', 'shared'].includes(sourceLayer) && target === `${sourceSlice}/index.ts` && source !== target)
      return '切片内部不得引用自身 public API，避免循环依赖'
    return null
  }
  const crossEntity =
    sourceLayer === 'entities' &&
    targetLayer === 'entities' &&
    target === `${targetSlice}/@x/${sourceSlice.split('/')[1]}.ts`
  if (!crossEntity && layers.indexOf(sourceLayer) >= layers.indexOf(targetLayer)) return '禁止向上或同层跨切片依赖'
  if (!['app', 'shared'].includes(targetLayer) && !crossEntity && target !== `${targetSlice}/index.ts`)
    return '跨切片必须通过根 index.ts'
  if (target.startsWith('shared/ui/') && !/^shared\/ui\/(?:[^/]+\/)?index\.ts$/.test(target))
    return '通用 UI 必须通过公开 index.ts'
  return null
}

export function checkArchitecture(sourceRoot) {
  const files = []
  const violations = []
  function collect(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const filename = path.join(directory, entry.name)
      if (entry.isDirectory()) collect(filename)
      else if (extensions.includes(path.extname(entry.name))) files.push(filename)
    }
  }
  collect(sourceRoot)
  for (const entry of fs.readdirSync(sourceRoot, { withFileTypes: true })) {
    if (entry.isDirectory() && !layers.includes(entry.name)) violations.push(`${entry.name}: 禁止六层之外的顶层目录`)
  }
  const relative = (filename) => normalize(path.relative(sourceRoot, filename))
  const resolve = (base) =>
    [base, ...extensions.map((extension) => base + extension), path.join(base, 'index.ts')].find(
      (candidate) => fs.existsSync(candidate) && fs.statSync(candidate).isFile(),
    )
  for (const filename of files) {
    const source = relative(filename)
    if (!layers.includes(source.split('/')[0])) {
      if (!source.endsWith('.d.ts')) violations.push(`${source}: 源码必须位于 FSD 六层内`)
      continue
    }
    const slice = sliceOf(source)
    if (!['app', 'shared'].includes(slice) && !fs.existsSync(path.join(sourceRoot, slice, 'index.ts')))
      violations.push(`${source}: 切片缺少根 index.ts`)
    for (const specifier of importsOf(fs.readFileSync(filename, 'utf8'), source)) {
      const clean = specifier.split('?')[0]
      const base = clean.startsWith('@/')
        ? path.join(sourceRoot, clean.slice(2))
        : clean.startsWith('.')
          ? path.resolve(path.dirname(filename), clean)
          : clean.startsWith('/src/')
            ? path.join(sourceRoot, clean.slice(5))
            : null
      if (!base) continue
      const resolved = resolve(base)
      if (!resolved) {
        violations.push(`${source} → ${specifier}: 本地引用无法解析`)
        continue
      }
      const reason = boundaryViolation(source, relative(resolved))
      if (reason) violations.push(`${source} → ${specifier}: ${reason}`)
    }
  }
  return [...new Set(violations)]
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const violations = checkArchitecture(fileURLToPath(new URL('../src', import.meta.url)))
  if (violations.length) {
    console.error(violations.join('\n'))
    process.exitCode = 1
  } else console.log('FSD 架构检查通过')
}
