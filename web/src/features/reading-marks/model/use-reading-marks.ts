import { ref } from 'vue'
import { reactionApi, type ReactionValue } from '@/entities/reaction'
import { getApiErrorMessage } from '@/shared/api/http'
import { markKey, type ReadingMarkTarget } from './marks'

export function useReadingMarks() {
  const values = ref<Record<string, ReactionValue>>({})
  const loading = ref(false)
  const ready = ref(false)
  const pending = ref('')
  const error = ref('')
  let generation = 0

  function value(target: ReadingMarkTarget): ReactionValue {
    return values.value[markKey(target.kind, target.id)] ?? 'none'
  }

  async function load(targets: ReadingMarkTarget[]) {
    const current = ++generation
    loading.value = true
    ready.value = false
    error.value = ''
    pending.value = ''
    try {
      const loaded = await Promise.all(
        targets.map(async (target) => {
          const saved = await reactionApi.get(target.kind, target.id)
          return [markKey(target.kind, target.id), saved.value] as const
        }),
      )
      if (current !== generation) return
      values.value = Object.fromEntries(loaded)
      ready.value = true
    } catch (reason) {
      if (current === generation) error.value = getApiErrorMessage(reason, '标记状态加载失败')
    } finally {
      if (current === generation) loading.value = false
    }
  }

  async function set(target: ReadingMarkTarget, next: ReactionValue) {
    if (!ready.value || pending.value) return
    const key = markKey(target.kind, target.id)
    const previous = value(target)
    if (previous === next) return
    const current = ++generation
    values.value = { ...values.value, [key]: next }
    pending.value = key
    error.value = ''
    try {
      const saved = await reactionApi.set(target.kind, target.id, next)
      if (current !== generation) return
      values.value = { ...values.value, [key]: saved.value }
    } catch (reason) {
      if (current !== generation) return
      values.value = { ...values.value, [key]: previous }
      error.value = getApiErrorMessage(reason, '标记保存失败')
    } finally {
      if (current === generation) pending.value = ''
    }
  }

  function reset() {
    generation++
    values.value = {}
    loading.value = false
    ready.value = false
    pending.value = ''
    error.value = ''
  }

  return { values, loading, ready, pending, error, value, load, set, reset }
}
