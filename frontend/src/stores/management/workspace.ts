import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { workspaceApi } from '@/services/management/workspace'
import { toErrorMessage } from '@/services/management/http'
import { OperationName } from '@/types/management/enums'
import type { OperationName as OperationNameType } from '@/types/management/enums'
import type {
  CatalogTreeNode,
  UpdateComicRequest,
  WorkspaceComicDetail,
} from '@/types/management/comic'

/**
 * 统一漫画工作区 Store（T18）
 *
 * 持有工作区详情 + 目录树，提供：
 * - 详情/目录加载（loading/error 三态）
 * - 目录 CRUD / 章节 CRUD（成功后本地更新树或整体重拉）
 * - allowedOperations 判定：can(操作) + blockedReason(操作)
 *
 * allowedOperations 全部来自 API（detail.allowedOperations），不做本地推导。
 */

export interface CatalogMutationState {
  readonly pending: boolean
  readonly error: string | null
}

function freshMutation(): CatalogMutationState {
  return { pending: false, error: null }
}

export const useComicWorkspaceStore = defineStore('comic-workspace', () => {
  const detail = ref<WorkspaceComicDetail | null>(null)
  const catalog = ref<readonly CatalogTreeNode[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const catalogLoading = ref(false)
  const catalogError = ref<string | null>(null)
  /** 最近一次目录/章节写操作的错误（供 CatalogTab 顶部提示） */
  const mutation = ref<CatalogMutationState>(freshMutation())

  const lifecycle = computed(() => detail.value?.lifecycle ?? null)
  const activeTask = computed(() => detail.value?.activeTask ?? null)
  const allowedOperations = computed(() => detail.value?.allowedOperations ?? null)
  const version = computed(() => detail.value?.version ?? 0)

  function can(operation: OperationNameType): boolean {
    const ops = allowedOperations.value
    return ops ? ops.allowed.includes(operation) : false
  }

  function blockedReason(operation: OperationNameType): string | null {
    const ops = allowedOperations.value
    if (!ops) return null
    const direct = ops.blockedReasons[operation]
    if (direct) return direct
    return ops.blockedReasons['*'] ?? null
  }

  function blockedOrNull(operation: OperationNameType): string | null {
    if (can(operation)) return null
    return blockedReason(operation) ?? '当前状态不允许此操作'
  }

  async function load(id: number): Promise<WorkspaceComicDetail | null> {
    loading.value = true
    error.value = null
    try {
      const entry = await workspaceApi.detail(id)
      detail.value = entry
      return entry
    } catch (err: unknown) {
      error.value = toErrorMessage(err, '加载漫画失败')
      detail.value = null
      return null
    } finally {
      loading.value = false
    }
  }

  async function loadCatalog(): Promise<readonly CatalogTreeNode[]> {
    const id = detail.value?.id
    if (!id) return []
    catalogLoading.value = true
    catalogError.value = null
    try {
      const tree = await workspaceApi.catalogTree(id)
      catalog.value = tree
      return tree
    } catch (err: unknown) {
      catalogError.value = toErrorMessage(err, '加载目录失败')
      return catalog.value
    } finally {
      catalogLoading.value = false
    }
  }

  function clearMutation(): void {
    mutation.value = freshMutation()
  }

  /** 执行写操作，成功返回 true；失败记录 mutation.error 并返回 false */
  async function runMutation(fn: () => Promise<void>): Promise<boolean> {
    mutation.value = { pending: true, error: null }
    try {
      await fn()
      mutation.value = { pending: false, error: null }
      return true
    } catch (err: unknown) {
      mutation.value = { pending: false, error: toErrorMessage(err, '操作失败') }
      return false
    }
  }

  // ========== 目录 CRUD ==========

  async function createCatalog(payload: { readonly title: string; readonly parentId?: number | null }): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.createCatalog(id, payload)
      await loadCatalog()
    })
  }

  async function renameCatalog(catalogId: number, title: string): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.renameCatalog(id, catalogId, { title })
      await loadCatalog()
    })
  }

  async function moveCatalog(catalogId: number, parentId?: number | null): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.moveCatalog(id, catalogId, { parentId })
      await loadCatalog()
    })
  }

  async function reorderCatalog(catalogId: number, sortOrder: number): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.reorderCatalog(id, catalogId, { sortOrder })
      await loadCatalog()
    })
  }

  async function deleteCatalog(catalogId: number, reparentTo?: number | null): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.deleteCatalog(id, catalogId, reparentTo)
      await loadCatalog()
    })
  }

  // ========== 章节 CRUD ==========

  async function createChapter(payload: { readonly title: string; readonly chapterNo?: string; readonly catalogId?: number | null }): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.createChapter(id, payload)
      await loadCatalog()
    })
  }

  async function renameChapter(chapterId: number, title: string): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.renameChapter(id, chapterId, { title })
      await loadCatalog()
    })
  }

  async function moveChapter(chapterId: number, catalogId?: number | null): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.moveChapter(id, chapterId, { catalogId })
      await loadCatalog()
    })
  }

  async function reorderChapter(chapterId: number, targetGlobalOrder: number): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.reorderChapter(id, chapterId, { targetGlobalOrder })
      await loadCatalog()
    })
  }

  async function trashChapter(chapterId: number): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      await workspaceApi.trashChapter(id, chapterId)
      await loadCatalog()
    })
  }

  /** 更新工作区元数据（含版本冲突传播） */
  async function updateComic(payload: UpdateComicRequest): Promise<boolean> {
    const id = detail.value?.id
    if (!id) return false
    return runMutation(async () => {
      const updated = await workspaceApi.update(id, payload)
      detail.value = updated
    })
  }

  return {
    detail,
    catalog,
    loading,
    error,
    catalogLoading,
    catalogError,
    mutation,
    lifecycle,
    activeTask,
    allowedOperations,
    version,
    can,
    blockedReason,
    blockedOrNull,
    load,
    loadCatalog,
    clearMutation,
    runMutation,
    createCatalog,
    renameCatalog,
    moveCatalog,
    reorderCatalog,
    deleteCatalog,
    createChapter,
    renameChapter,
    moveChapter,
    reorderChapter,
    trashChapter,
    updateComic,
  }
})

/** 供语义化导入（Eslint 无未用导出告警） */
export const WORKSPACE_GATE_OPS: readonly OperationNameType[] = [
  OperationName.EDIT,
  OperationName.DELETE,
  OperationName.RECOVER,
  OperationName.PURGE,
  OperationName.LQ_GENERATE,
  OperationName.HQ_DELETE,
  OperationName.TRANSCODE,
]
