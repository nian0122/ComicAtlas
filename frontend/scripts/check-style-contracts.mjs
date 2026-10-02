import { readdir, readFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { pathToFileURL } from 'node:url'

/** 检查自定义 token 和页面控件主题；移动阅读的独立交互外观保留。 */
export function validateStyleContracts(files) {
  const definitions = new Set()
  const references = []
  const violations = []
  for (const { path, source } of files) {
    for (const match of source.matchAll(/['"]?(--[\w-]+)['"]?\s*:/g)) definitions.add(match[1])
    for (const match of source.matchAll(/setProperty\(\s*['"](--[\w-]+)['"]/g)) definitions.add(match[1])
    for (const match of source.matchAll(/var\(\s*(--[\w-]+)/g)) references.push({ path, name: match[1] })
    if (!path.startsWith('src/pages/') || path === 'src/pages/reading/library/ui/library.css') continue
    for (const match of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      if (!/\.el-(?:input__wrapper|select__wrapper|textarea__inner)\b/.test(match[1])) continue
      if (/(?:^|;)\s*(?:background(?:-color)?|box-shadow|border-radius|font-size|color)\s*:/.test(match[2])) {
        violations.push(`${path}: 公共表单外观应在 app/styles 中声明，页面只保留布局`)
      }
    }
  }
  for (const { path, name } of references) {
    if (!definitions.has(name) && !name.startsWith('--el-')) violations.push(`${path}: 未定义的样式 token ${name}`)
  }
  return [...new Set(violations)]
}

async function readSources(directory, root) {
  const files = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) files.push(...(await readSources(path, root)))
    else if (/\.(vue|ts|css|scss)$/.test(entry.name))
      files.push({ path: relative(root, path).replaceAll('\\', '/'), source: await readFile(path, 'utf8') })
  }
  return files
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const files = await readSources(join(process.cwd(), 'src'), process.cwd())
  const violations = validateStyleContracts(files)
  if (violations.length) {
    console.error(violations.join('\n'))
    process.exitCode = 1
  } else console.log(`公共样式检查通过，覆盖 ${files.length} 个源文件`)
}
