import { computed, ref } from 'vue'
import type { ImportTaskVO } from '@/features/import/types'

export function useImportPageForm() {
  const activeTab = ref<'single' | 'batch'>('single')
  const sourceType = ref<'ZIP' | 'CBZ' | 'DIRECTORY' | 'EHENTAI'>('ZIP')
  const sourcePath = ref('')
  const pathPlaceholder = computed(() => {
    if (sourceType.value === 'EHENTAI') return 'https://e-hentai.org/g/1234567/abcdef1234/'
    if (sourceType.value === 'ZIP') return 'D:/comics/my_comic.zip'
    if (sourceType.value === 'CBZ') return 'D:/comics/my_comic.cbz'
    return 'D:/comics/my_comic_dir'
  })
  const pathHint = computed(() => {
    if (sourceType.value === 'EHENTAI') return '输入完整的 E-Hentai 或 ExHentai 画廊 URL；服务端会异步下载、解压并导入'
    if (sourceType.value === 'ZIP')
      return '完整 ZIP 文件路径；大导出分卷时填最后一个 .zip，分卷须同目录同 basename，.z01 不可作为入口'
    if (sourceType.value === 'CBZ')
      return '完整 CBZ 文件路径；压缩包内可放置 ComicInfo.xml，导入时自动读取标题、作者、标签和章节信息'
    return '漫画根目录绝对路径，包含章节子目录'
  })
  const canSubmit = computed(() => {
    const sourceValue = sourcePath.value.trim()
    if (!sourceValue) return false
    if (sourceType.value !== 'EHENTAI') return true
    try {
      const sourceUrl = new URL(sourceValue)
      return ['e-hentai.org', 'exhentai.org'].includes(sourceUrl.hostname.toLowerCase())
    } catch {
      return false
    }
  })
  function taskName(task: ImportTaskVO): string {
    const sourcePathValue = task.sourcePath || task.sourceRef || ''
    if (!sourcePathValue) return `任务 #${task.id}`
    const pathParts = sourcePathValue.replace(/\\/g, '/').split('/')
    return pathParts[pathParts.length - 1] || sourcePathValue
  }
  return { activeTab, sourceType, sourcePath, pathPlaceholder, pathHint, canSubmit, taskName }
}
