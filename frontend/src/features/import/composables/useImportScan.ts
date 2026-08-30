import { computed, ref } from 'vue'
import { isBlockingScanWarning } from '@/features/import/types'
import type {
  ScanItemVO,
  ScanPreviewNodeVO,
  ScanResultVO,
  ScanWarningVO,
} from '@/features/import/types'

interface ScanItemRow {
  item: ScanItemVO
  preview?: ScanPreviewNodeVO
}

export function useImportScan(scan: (path: string) => Promise<ScanResultVO>) {
  const batchParentPath = ref('')
  const scanning = ref(false)
  const scanResult = ref<ScanResultVO | null>(null)
  const scanError = ref('')
  const selectedPaths = ref<string[]>([])
  const previewExpanded = ref<Set<string>>(new Set())

  const hasPreview = computed(() => (scanResult.value?.preview?.length ?? 0) > 0)
  const importableCount = computed(
    () => (scanResult.value?.items ?? []).filter(isImportable).length,
  )
  const totalImageCount = computed(
    () => (scanResult.value?.items ?? []).reduce((sum, item) => sum + item.imageCount, 0),
  )
  const totalMediaCount = computed(
    () => (scanResult.value?.preview ?? []).reduce((sum, node) => sum + node.fileCount, 0),
  )
  const totalVideoCount = computed(() => Math.max(totalMediaCount.value - totalImageCount.value, 0))
  const scanItemRows = computed<ScanItemRow[]>(() =>
    (scanResult.value?.items ?? []).map((item) => ({
      item,
      preview: scanResult.value?.preview?.find((previewNode) => previewNode.name === item.name),
    })),
  )

  function isImportable(item: ScanItemVO): boolean {
    return !(item.warnings ?? []).some((warning) => isBlockingScanWarning(warning.code))
  }

  function nonBlockingWarnings(item: ScanItemVO): ScanWarningVO[] {
    return (item.warnings ?? []).filter((warning) => !isBlockingScanWarning(warning.code))
  }

  function blockingReason(item: ScanItemVO): string {
    const blockingWarning = (item.warnings ?? []).find((warning) => isBlockingScanWarning(warning.code))
    return blockingWarning?.message ?? ''
  }

  function itemStats(item: ScanItemVO, preview?: ScanPreviewNodeVO): string {
    if (!preview) return `${item.imageCount} 张图片`
    const videoCount = Math.max(preview.fileCount - item.imageCount, 0)
    return videoCount > 0
      ? `图片 ${item.imageCount} · 视频 ${videoCount} · 媒体 ${preview.fileCount}`
      : `图片 ${item.imageCount} · 媒体 ${preview.fileCount}`
  }

  function togglePreview(path: string) {
    const nextExpanded = new Set(previewExpanded.value)
    if (nextExpanded.has(path)) nextExpanded.delete(path)
    else nextExpanded.add(path)
    previewExpanded.value = nextExpanded
  }

  function selectAll() {
    if (!scanResult.value) return
    selectedPaths.value = scanResult.value.items.filter(isImportable).map((item) => item.path)
  }

  function deselectAll() {
    selectedPaths.value = []
  }

  function togglePath(path: string) {
    const item = scanResult.value?.items.find((scanItem) => scanItem.path === path)
    if (item && !isImportable(item)) return
    const selectedIndex = selectedPaths.value.indexOf(path)
    if (selectedIndex >= 0) selectedPaths.value.splice(selectedIndex, 1)
    else selectedPaths.value.push(path)
  }

  async function doScan() {
    const path = batchParentPath.value.trim()
    if (!path) return
    scanning.value = true
    scanResult.value = null
    scanError.value = ''
    selectedPaths.value = []
    previewExpanded.value = new Set()
    try {
      scanResult.value = await scan(path)
    } catch (error: unknown) {
      scanError.value = errorMessage(error) || '扫描目录失败'
    } finally {
      scanning.value = false
    }
  }

  function resetScan() {
    batchParentPath.value = ''
    scanResult.value = null
    selectedPaths.value = []
    previewExpanded.value = new Set()
  }

  return {
    batchParentPath,
    scanning,
    scanResult,
    scanError,
    selectedPaths,
    previewExpanded,
    hasPreview,
    importableCount,
    totalImageCount,
    totalMediaCount,
    totalVideoCount,
    scanItemRows,
    isImportable,
    nonBlockingWarnings,
    blockingReason,
    itemStats,
    togglePreview,
    selectAll,
    deselectAll,
    togglePath,
    doScan,
    resetScan,
  }
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message ?? ''
}
