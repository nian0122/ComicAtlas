import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { comicCoverUrl } from '@/entities/comic'
import { storageApi, operationBlockers, type StorageObject } from '@/entities/storage'
import {
  batchPreviewExpired,
  confirmBatchPreview,
  createBatchPreview,
  managementCommands as commands,
  readBatchPreviewPage,
  type BatchItem,
  type BatchPreview,
} from '@/features/manage-items'
import type { Task } from '@/entities/task'
import { ApiError, getApiErrorMessage } from '@/shared/api/http'

export interface LqTarget {
  kind: 'comics' | 'chapters'
  id: string
  title: string
  cover: string | null
}
export type LqMode = 'missing' | 'all'
interface Props {
  open: boolean
  target: LqTarget | null
  objects: StorageObject[]
  workspaceBusy: boolean
}
interface Totals {
  images: number
  ready: number
  missing: number
  released: number
  releasing: number
}
const emptyTotals: Totals = { images: 0, ready: 0, missing: 0, released: 0, releasing: 0 }
const batchReasons: Record<string, string> = {
  NO_IMAGES: '没有图片',
  TARGET_NOT_FOUND: '作品已不存在或不可用',
  TARGET_BUSY: '作品正在处理中',
  BATCH_SCOPE_CHANGED: '操作范围已变化',
  BATCH_TARGET_LIMIT: '图片数量超出单批上限',
  BATCH_DEPENDENCY_FAILED: '前置项目未完成',
}
const itemStatus: Record<string, string> = {
  pending: '可执行',
  eligible: '可执行',
  blocked: '暂时无法执行',
  covered: '与其他项目一并处理',
  succeeded: '已完成',
  failed: '处理失败',
  cancelled: '已取消',
}

/**
 * 漫画库与存储统计共用的轻量副本对话框。
 * 单范围读取存储对象，批量创建服务端冻结预览；生成方式变化时重新读取预览，提交始终使用当前预览与幂等键。
 */
export function useLqDialog(props: Props, accepted: (task: Task, batch: BatchPreview | null) => void) {
  const target = ref<LqTarget | null>(null)
  const objects = ref<StorageObject[]>([])
  const scope = ref<StorageObject | null>(null)
  const preview = ref<BatchPreview | null>(null)
  const items = ref<BatchItem[]>([])
  const total = ref(0)
  const previewPage = ref(1)
  const mode = ref<LqMode>('missing')
  const loading = ref(false)
  const loadingMore = ref(false)
  const submitting = ref(false)
  const error = ref('')
  const attempted = ref(false)
  const resolved = ref(false)
  const key = ref<string>(crypto.randomUUID())
  const cache = new Map<
    LqMode,
    { preview: BatchPreview; items: BatchItem[]; total: number; page: number; key: string }
  >()
  let generation = 0,
    disposed = false,
    scopeKey = ''

  const single = computed(() => target.value !== null)
  const locked = computed(() => attempted.value && !resolved.value)
  const eligibleIds = computed(
    () => new Set(items.value.filter((item) => item.status !== 'blocked').map((item) => item.targetId)),
  )
  const statObjects = computed(() => {
    if (single.value) return scope.value ? [scope.value] : []
    return objects.value.filter((item) => eligibleIds.value.has(item.id))
  })
  const known = computed(
    () =>
      statObjects.value.length > 0 &&
      statObjects.value.every(
        (item) =>
          typeof item.inventory.currentLqImages === 'number' &&
          typeof item.inventory.missingLq === 'number' &&
          typeof item.inventory.releasedImages === 'number' &&
          typeof item.inventory.releasingImages === 'number',
      ),
  )
  const totals = computed<Totals>(() => {
    const result = { ...emptyTotals }
    for (const item of statObjects.value) {
      const inventory = item.inventory
      result.images += inventory.images ?? 0
      result.ready += inventory.currentLqImages ?? 0
      result.missing += inventory.missingLq ?? 0
      result.released += inventory.releasedImages ?? 0
      result.releasing += inventory.releasingImages ?? 0
    }
    return result
  })
  const itemImages = computed(() => items.value.reduce((sum, item) => sum + (item.imageCount ?? 0), 0))
  const selectedImages = computed(() => {
    if (single.value) return scope.value?.inventory.images ?? 0
    const fromObjects = objects.value.reduce((sum, item) => sum + (item.inventory.images ?? 0), 0)
    return fromObjects || itemImages.value
  })
  const prediction = computed(() => {
    if (!known.value || !statObjects.value.length) return null
    const value = totals.value
    if (mode.value === 'missing')
      return {
        generate: value.missing,
        skip: value.ready,
        unavailable: Math.max(0, value.images - value.missing - value.ready),
      }
    return {
      generate: Math.max(0, value.images - value.released - value.releasing),
      skip: 0,
      unavailable: value.released + value.releasing,
    }
  })
  const selectedCount = computed(() => preview.value?.selectedCount ?? objects.value.length)
  const eligibleCount = computed(() => preview.value?.eligibleCount ?? 0)
  const blockedCount = computed(() => preview.value?.blockedCount ?? 0)
  const singleBlockers = computed(() => {
    if (!single.value || !scope.value) return ''
    if (!scope.value.operations?.generateLq?.canSubmit)
      return operationBlockers(scope.value.operations.generateLq.blockers) || '当前范围不能生成轻量副本'
    if ((scope.value.inventory.images ?? 0) === 0) return '当前范围没有图片，无法生成轻量副本'
    return ''
  })
  const canSubmit = computed(() => {
    if (!props.open || loading.value || submitting.value || props.workspaceBusy) return false
    if (single.value) return !!scope.value?.operations?.generateLq?.canSubmit && (scope.value.inventory.images ?? 0) > 0
    return !!preview.value && (preview.value.eligibleCount ?? 0) > 0
  })
  const submitLabel = computed(() => {
    if (submitting.value) return '正在提交…'
    if (locked.value) return '重试确认同次提交'
    return mode.value === 'all' ? '重新生成全部副本' : '生成轻量副本'
  })

  function scopeKeyOf() {
    return props.target
      ? `${props.target.kind}:${props.target.id}`
      : props.objects
          .map((item) => item.id)
          .sort()
          .join(',')
  }
  function reset() {
    target.value = props.target ? { ...props.target } : null
    objects.value = [...props.objects]
    scope.value = null
    preview.value = null
    items.value = []
    total.value = 0
    previewPage.value = 1
    mode.value = 'missing'
    error.value = ''
    attempted.value = false
    resolved.value = false
    key.value = crypto.randomUUID()
    cache.clear()
  }
  async function load() {
    if (!props.open) return
    const current = ++generation
    loading.value = true
    error.value = ''
    preview.value = null
    items.value = []
    total.value = 0
    previewPage.value = 1
    try {
      if (target.value) {
        const value =
          target.value.kind === 'comics'
            ? await storageApi.comic(target.value.id)
            : await storageApi.chapter(target.value.id)
        if (disposed || current !== generation) return
        if (!value || value.id !== target.value.id) throw new Error('读取到的范围与当前对象不一致，请重新打开')
        scope.value = value
      } else {
        const ids = objects.value.map((item) => item.id)
        if (!ids.length) throw new Error('没有可生成轻量副本的作品')
        const content = await createBatchPreview(
          {
            operation: 'image_lq',
            selection: { mode: 'ids', ids },
            lq: { force: mode.value === 'all' },
          },
          {
            preview: (value) => {
              if (value.operation !== 'image_lq' || !value.id) throw new Error('轻量副本预览缺少有效范围，请重新读取')
            },
          },
        )
        if (disposed || current !== generation) return
        preview.value = content.preview
        items.value = content.items
        total.value = content.total
        cache.set(mode.value, {
          preview: content.preview,
          items: content.items,
          total: content.total,
          page: 1,
          key: key.value,
        })
      }
    } catch (e) {
      if (!disposed && current === generation) error.value = getApiErrorMessage(e, '无法读取轻量副本范围')
    } finally {
      if (!disposed && current === generation) loading.value = false
    }
  }
  async function reload() {
    if (loading.value || submitting.value) return
    cache.delete(mode.value)
    key.value = crypto.randomUUID()
    await load()
  }
  function setMode(next: LqMode) {
    if (mode.value === next || locked.value || loading.value || submitting.value) return
    mode.value = next
    error.value = ''
    if (single.value) {
      key.value = crypto.randomUUID()
      return
    }
    const cached = cache.get(next)
    if (cached && !batchPreviewExpired(cached.preview)) {
      key.value = cached.key
      preview.value = cached.preview
      items.value = [...cached.items]
      total.value = cached.total
      previewPage.value = cached.page
      return
    }
    key.value = crypto.randomUUID()
    void load()
  }
  async function more() {
    if (!preview.value || loadingMore.value || items.value.length >= total.value) return
    loadingMore.value = true
    error.value = ''
    try {
      const page = await readBatchPreviewPage(preview.value, previewPage.value + 1)
      items.value.push(...page.items)
      total.value = page.total
      previewPage.value++
      cache.set(mode.value, {
        preview: preview.value,
        items: items.value,
        total: total.value,
        page: previewPage.value,
        key: key.value,
      })
    } catch (e) {
      error.value = getApiErrorMessage(e, '读取完整清单失败')
    } finally {
      loadingMore.value = false
    }
  }
  async function submit() {
    if (!canSubmit.value || !props.open) return
    if (!target.value && preview.value && batchPreviewExpired(preview.value)) {
      error.value = '预览已过期，请重新读取范围后再提交'
      return
    }
    submitting.value = true
    error.value = ''
    try {
      if (target.value) {
        const task = await commands.generateLq(target.value.kind, target.value.id, key.value, mode.value === 'all')
        resolved.value = true
        accepted(task, null)
        return
      }
      if (!preview.value) throw new Error('预览已失效，请重新读取范围')
      attempted.value = true
      const task = await confirmBatchPreview(preview.value, key.value)
      resolved.value = true
      accepted(task, preview.value)
    } catch (e) {
      if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
        attempted.value = false
        cache.delete(mode.value)
        preview.value = null
        items.value = []
        total.value = 0
        error.value = getApiErrorMessage(e, '提交被拒绝，请重新读取范围后再试')
      } else if (!target.value && attempted.value) {
        error.value = '提交结果尚未确认。请重试同次提交，或在任务中心核对；重复提交不会创建第二个任务。'
      } else {
        error.value = getApiErrorMessage(e, '提交失败，请重试')
      }
    } finally {
      submitting.value = false
    }
  }
  function itemReason(item: BatchItem) {
    if (!item.errorCode) return '暂时无法执行'
    return batchReasons[item.errorCode] ?? operationBlockers([item.errorCode])
  }
  function itemLabel(item: BatchItem) {
    return itemStatus[item.status] ?? '状态待核对'
  }
  function itemStats(item: BatchItem) {
    const object = objects.value.find((value) => value.id === item.targetId)
    if (!object) return ''
    const inventory = object.inventory
    const images = inventory.images ?? item.imageCount ?? 0
    if (mode.value === 'all') {
      const failed = (inventory.releasedImages ?? 0) + (inventory.releasingImages ?? 0)
      return `${images} 张 · 将重新生成 ${Math.max(0, images - failed)} 张${failed ? ` · 预计失败 ${failed} 张` : ''}`
    }
    if (inventory.missingLq == null) return `${images} 张`
    return `${images} 张 · 待补齐 ${inventory.missingLq} 张${
      inventory.currentLqImages != null ? ` · 已就绪 ${inventory.currentLqImages} 张` : ''
    }`
  }
  function itemCover(item: BatchItem) {
    return item.targetType === 'comic' ? comicCoverUrl(item.targetId) : null
  }

  watch(
    () => [props.open, props.target?.kind, props.target?.id, props.objects.map((item) => item.id).join(',')],
    () => {
      if (!props.open) {
        generation++
        return
      }
      const incoming = scopeKeyOf()
      if (!incoming) return
      if (incoming === scopeKey && attempted.value && !resolved.value) return
      scopeKey = incoming
      reset()
      void load()
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    disposed = true
    generation++
  })

  return {
    target,
    objects,
    scope,
    preview,
    items,
    total,
    mode,
    loading,
    loadingMore,
    submitting,
    error,
    attempted,
    single,
    locked,
    known,
    totals,
    prediction,
    selectedImages,
    selectedCount,
    eligibleCount,
    blockedCount,
    singleBlockers,
    canSubmit,
    submitLabel,
    reload,
    setMode,
    more,
    submit,
    itemReason,
    itemLabel,
    itemStats,
    itemCover,
  }
}
