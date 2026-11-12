import { readdir, readFile } from 'node:fs/promises'
import { dirname, resolve, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const layers = ['app', 'pages', 'widgets', 'features', 'entities', 'shared']
function location(path) {
  const parts = path
    .replaceAll('\\', '/')
    .replace(/^src\//, '')
    .split('/')
  const layer = parts[0],
    group = layer === 'pages' && parts[1] === 'reading' ? 3 : 2
  return { layer, index: layers.indexOf(layer), slice: parts.slice(0, group).join('/') }
}
/** 静态与动态导入均检查；相对路径不能绕过切片边界。 */
export function inspectImports(files) {
  const failures = []
  for (const { path, source } of files) {
    const from = location(path)
    if (from.index < 0) continue
    for (const match of source.matchAll(/(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s*)(['"])([^'"]+)\1/g)) {
      const specifier = match[2]
      if (/^(?:element-plus|@element-plus\/)/.test(specifier)) {
        failures.push(`${path}: 禁止引入 Element Plus`)
        continue
      }
      if (!specifier.startsWith('@/') && !specifier.startsWith('.')) continue
      const target = specifier.startsWith('@/')
        ? specifier.slice(2)
        : relative(resolve('src'), resolve(dirname(path), specifier))
            .split(sep)
            .join('/')
      const to = location(target)
      if (to.index < 0) {
        failures.push(`${path}: 导入越出 FSD 源码边界 ${specifier}`)
        continue
      }
      if (to.index < from.index) failures.push(`${path}: 禁止向上依赖 ${specifier}`)
      if (to.index === from.index && !['app', 'shared'].includes(from.layer) && to.slice !== from.slice)
        failures.push(`${path}: 禁止同层切片互引 ${specifier}`)
      if (
        to.slice !== from.slice &&
        !['app', 'shared'].includes(to.layer) &&
        target !== to.slice &&
        target !== `${to.slice}/index` &&
        target !== `${to.slice}/index.ts`
      )
        failures.push(`${path}: 必须通过切片公开入口 ${specifier}`)
    }
  }
  return failures
}
async function collect(dir) {
  const all = []
  for (const item of await readdir(dir, { withFileTypes: true })) {
    const path = `${dir}/${item.name}`
    if (item.isDirectory()) all.push(...(await collect(path)))
    else if (/\.(vue|ts|css)$/.test(path)) all.push({ path, source: await readFile(path, 'utf8') })
  }
  return all
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const files = await collect('src'),
    failures = inspectImports(files)
  const manifest = JSON.parse(await readFile('package.json', 'utf8'))
  for (const name of Object.keys({ ...manifest.dependencies, ...manifest.devDependencies }))
    if (/element-plus/.test(name)) failures.push(`package.json: 禁止依赖 ${name}`)
  if (failures.length) {
    console.error(failures.join('\n'))
    process.exitCode = 1
  } else console.log(`FSD 检查通过：${files.length} 个源文件，无 Element Plus 依赖`)
}
