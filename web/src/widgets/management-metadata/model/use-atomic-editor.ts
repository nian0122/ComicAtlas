import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { comicApi, type Term } from '@/entities/comic'
import type { MetadataFields } from '@/entities/metadata-edit'
import { atomicBody, metadataFieldIssues, metadataCommands } from '@/features/edit-metadata'
import { getApiErrorMessage } from '@/shared/api/http'
import type { MetadataTarget } from './types'
export function useAtomicEditor(props: { targets: MetadataTarget[]; writable: boolean }, saved: () => void) {
  const rows = ref<MetadataFields[]>([]),
    baseline = ref(''),
    categories = ref<Term[]>([]),
    tags = ref<Term[]>([])
  const loading = ref(false),
    busy = ref(false),
    fresh = ref(false),
    acknowledged = ref(false),
    discardAction = ref<'reload' | 'draft' | ''>(''),
    error = ref(''),
    notice = ref('')
  const versions = ref<{ id: string; version: number }[] | null>(null)
  const validationActive = ref(false)
  const invalidRows = computed(() =>
    rows.value
      .map((row, index) => ({ index, id: row.id, issues: metadataFieldIssues(row) }))
      .filter((row) => Object.keys(row.issues).length),
  )
  let disposed = false
  const signature = computed(() => JSON.stringify(rows.value)),
    dirty = computed(() => !!baseline.value && signature.value !== baseline.value && !versions.value)
  const changes = computed(() => {
    if (!baseline.value) return []
    const original = JSON.parse(baseline.value) as MetadataFields[]
    return rows.value.flatMap((row) => {
      const before = original.find((item) => item.id === row.id)
      if (!before) return []
      const fields = (['title', 'author', 'description', 'categoryId', 'tagIds'] as const)
        .filter((key) => JSON.stringify(before[key]) !== JSON.stringify(row[key]))
        .map((key) => ({ key, before: before[key], after: row[key] }))
      return fields.length ? [{ id: row.id, title: row.title, fields }] : []
    })
  })
  watch(signature, () => {
    acknowledged.value = false
    if (validationActive.value && fresh.value) error.value = ''
  })
  async function load(force = false) {
    if (loading.value || busy.value) return
    if (dirty.value && !force) {
      discardAction.value = 'reload'
      return
    }
    loading.value = true
    fresh.value = false
    error.value = ''
    try {
      if (!props.targets.length || props.targets.length > 100) throw new Error('逐本编辑需要选择 1—100 部作品')
      const [c, t] = await Promise.all([comicApi.categories(), comicApi.tags()])
      const result: MetadataFields[] = []
      for (let start = 0; start < props.targets.length; start += 10) {
        const books = await Promise.all(
          props.targets.slice(start, start + 10).map((target) => comicApi.detail(target.id)),
        )
        if (disposed) return
        if (books.some((book, index) => book.id !== props.targets[start + index]?.id))
          throw new Error('读取到的资料与所选作品不一致')
        result.push(
          ...books.map((book) => ({
            id: book.id,
            version: book.version,
            originalTitle: book.originalTitle,
            title: book.title,
            author: book.author,
            description: book.description,
            categoryId: book.category?.id ?? null,
            tagIds: book.tags.map((tag) => tag.id),
          })),
        )
      }
      if (disposed) return
      rows.value = result
      baseline.value = JSON.stringify(result)
      categories.value = c.items
      tags.value = t.items
      versions.value = null
      acknowledged.value = false
      discardAction.value = ''
      fresh.value = true
      validationActive.value = false
      notice.value = ''
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '读取逐本资料失败，尚未完成的范围不可保存')
    } finally {
      if (!disposed) loading.value = false
    }
  }
  async function save() {
    if (busy.value || loading.value || !props.writable || !fresh.value || !dirty.value || !acknowledged.value) return
    error.value = ''
    try {
      atomicBody(rows.value)
    } catch (e) {
      error.value = getApiErrorMessage(e, '资料无效')
      return
    }
    busy.value = true
    try {
      const value = await metadataCommands.atomic(rows.value)
      if (disposed) return
      versions.value = value
      fresh.value = false
      acknowledged.value = false
      notice.value = `整批保存已确认，已返回 ${value.length} 部作品的版本。`
      saved()
    } catch (e) {
      if (!disposed) {
        fresh.value = false
        error.value = getApiErrorMessage(e, '保存结果未确认，请重新读取核对；不会自动重发或提升版本')
      }
    } finally {
      if (!disposed) busy.value = false
    }
  }
  function review() {
    if (!fresh.value || !dirty.value || busy.value || loading.value || !props.writable) return false
    error.value = ''
    validationActive.value = true
    try {
      atomicBody(rows.value)
      return true
    } catch (e) {
      error.value = getApiErrorMessage(e, '请核对修改内容')
      return false
    }
  }
  function discardDraft() {
    if (busy.value || loading.value || !baseline.value || discardAction.value !== 'draft') return false
    rows.value = JSON.parse(baseline.value) as MetadataFields[]
    acknowledged.value = false
    if (validationActive.value && fresh.value) error.value = ''
    validationActive.value = false
    discardAction.value = ''
    return true
  }
  onBeforeUnmount(() => {
    disposed = true
  })
  void load()
  return {
    rows,
    categories,
    tags,
    loading,
    busy,
    fresh,
    dirty,
    acknowledged,
    discardAction,
    error,
    notice,
    versions,
    validationActive,
    invalidRows,
    changes,
    review,
    load,
    save,
    discardDraft,
  }
}
