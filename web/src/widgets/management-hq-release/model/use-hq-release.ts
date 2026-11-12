import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { hqReleaseApi, type HqReleasePreview } from '@/entities/hq-release'
import { releaseHq, sameHqSnapshot } from '@/features/release-hq'
import {
  batchPreviewExpired,
  confirmBatchPreview,
  createBatchPreview,
  readBatchPreviewPage,
  type BatchPreview,
  type BatchItem,
} from '@/features/manage-items'
import type { Task } from '@/entities/task'
import { ApiError, getApiErrorMessage } from '@/shared/api/http'
export interface HqTarget {
  kind: 'comics' | 'chapters'
  id: string
  title: string
  cover: string | null
}
export function useHqRelease(
  props: { open: boolean; target: HqTarget | null; ids: string[] },
  accepted: (task: Task, batch: BatchPreview | null) => void,
) {
  const target = ref<HqTarget | null>(null),
    ids = ref<string[]>([]),
    preview = ref<HqReleasePreview | null>(null),
    batch = ref<BatchPreview | null>(null),
    items = ref<BatchItem[]>([])
  const busy = ref(false),
    ready = ref(false),
    error = ref(''),
    acknowledged = ref(false),
    attempted = ref(false),
    resolved = ref(false),
    now = ref(Date.now()),
    total = ref(0),
    itemPage = ref(1)
  let generation = 0,
    disposed = false,
    scopeKey = ''
  const key = ref(crypto.randomUUID())
  const expired = computed(() => batch.value !== null && batchPreviewExpired(batch.value, now.value))
  const canSubmit = computed(
    () =>
      ready.value &&
      !busy.value &&
      acknowledged.value &&
      (!expired.value || attempted.value) &&
      (batch.value
        ? batch.value.eligibleCount > 0
        : preview.value?.allowed === true && !preview.value.truncated && preview.value.available > 0),
  )
  function validate(value: HqReleasePreview) {
    if (
      !target.value ||
      value.scopeId !== target.value.id ||
      value.scopeType !== (target.value.kind === 'comics' ? 'comic' : 'chapter')
    )
      throw new Error('原件释放预览范围与当前目标不一致')
  }
  async function load() {
    if (busy.value || (attempted.value && !resolved.value)) return
    const current = ++generation
    busy.value = true
    error.value = ''
    ready.value = false
    acknowledged.value = false
    key.value = crypto.randomUUID()
    try {
      if (target.value) {
        const value = await hqReleaseApi.preview(target.value.kind, target.value.id)
        if (disposed || current !== generation) return
        validate(value)
        preview.value = value
      } else {
        const content = await createBatchPreview(
          { operation: 'hq_release', selection: { mode: 'ids', ids: ids.value } },
          {
            preview: (value) => {
              if (
                value.operation !== 'hq_release' ||
                !value.confirmationToken ||
                value.hq?.effect !== 'delete_hq_keep_lq'
              )
                throw new Error('批量原件释放预览缺少正确的范围确认契约')
            },
          },
        )
        if (disposed || current !== generation) return
        batch.value = content.preview
        items.value = content.items
        total.value = content.total
        itemPage.value = 1
      }
      ready.value = true
    } catch (e) {
      if (!disposed && current === generation) error.value = getApiErrorMessage(e, '读取原件释放预览失败')
    } finally {
      if (!disposed && current === generation) busy.value = false
    }
  }
  watch(
    () => [props.open, props.target, props.ids],
    async () => {
      if (!props.open) return
      const incoming = props.target ? `${props.target.kind}:${props.target.id}` : props.ids.slice().sort().join(',')
      if (attempted.value && !resolved.value) {
        if (incoming !== scopeKey) error.value = '上次释放提交结果尚未确认，请先重试同次提交或在任务中心核对'
        return
      }
      scopeKey = incoming
      target.value = props.target ? { ...props.target } : null
      ids.value = [...props.ids]
      preview.value = null
      batch.value = null
      items.value = []
      attempted.value = false
      resolved.value = false
      await load()
    },
  )
  async function more() {
    if (!batch.value || items.value.length >= total.value || busy.value) return
    busy.value = true
    try {
      const result = await readBatchPreviewPage(batch.value, itemPage.value + 1)
      items.value.push(...result.items)
      itemPage.value++
      total.value = result.total
    } catch (e) {
      error.value = getApiErrorMessage(e, '读取完整范围失败')
    } finally {
      busy.value = false
    }
  }
  async function submit() {
    if (!canSubmit.value) return
    busy.value = true
    error.value = ''
    try {
      let task: Task
      if (target.value) {
        if (!attempted.value) {
          const current = await hqReleaseApi.preview(target.value.kind, target.value.id)
          validate(current)
          if (!preview.value || !sameHqSnapshot(preview.value, current)) {
            preview.value = current
            acknowledged.value = false
            throw new Error('释放范围或保护条件已变化，请重新核对并确认')
          }
        }
        attempted.value = true
        task = await releaseHq(target.value.kind, target.value.id, key.value)
      } else {
        if (!batch.value?.confirmationToken) throw new Error('范围令牌丢失，请重新预览')
        attempted.value = true
        task = await confirmBatchPreview(batch.value, key.value)
      }
      resolved.value = true
      accepted(task, batch.value)
    } catch (e) {
      if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
        attempted.value = false
        acknowledged.value = false
      }
      error.value = getApiErrorMessage(e, '释放提交失败，请核对同次提交结果后重试')
    } finally {
      busy.value = false
    }
  }
  const timer = window.setInterval(() => {
    if (props.open) now.value = Date.now()
  }, 1000)
  onBeforeUnmount(() => {
    disposed = true
    generation++
    window.clearInterval(timer)
  })
  return {
    target,
    preview,
    batch,
    items,
    busy,
    ready,
    error,
    acknowledged,
    attempted,
    expired,
    total,
    canSubmit,
    load,
    more,
    submit,
  }
}
