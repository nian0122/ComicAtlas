<template>
  <section class="catalog-tab" data-testid="catalog-tab">
    <div class="catalog-toolbar">
      <button
        class="action-btn action-btn--primary"
        type="button"
        data-testid="cat-add-catalog"
        :disabled="!canEdit"
        @click="openCreateCatalog(null)"
      >
        + 新建目录
      </button>
      <span v-if="!canEdit" class="toolbar-blocked" data-testid="cat-blocked-EDIT">
        {{ editBlockedReason }}
      </span>
    </div>

    <p v-if="store.catalogError" class="catalog-error" data-testid="catalog-error" role="alert">
      {{ store.catalogError }}
    </p>
    <p v-if="store.mutation.error" class="catalog-error" data-testid="catalog-error" role="alert">
      {{ store.mutation.error }}
    </p>

    <div v-if="store.catalogLoading && rows.length === 0" class="ws-state">
      <div class="action-btn-spinner" aria-hidden="true" />
      <span>加载目录中…</span>
    </div>

    <div v-else-if="rows.length === 0" class="catalog-empty" data-testid="catalog-empty">
      暂无目录与章节，点击「新建目录」开始组织内容。
    </div>

    <div v-else class="catalog-tree" role="tree" aria-label="章节目录" data-testid="catalog-tree">
      <template v-for="row in rows" :key="row.key">
        <!-- 目录行 -->
        <div v-if="row.kind === 'catalog'" class="catalog-node" role="treeitem" :data-testid="`catalog-node-${row.node.id}`">
          <div
            class="tree-row catalog-row"
            :style="{ paddingLeft: `${row.depth * 24}px` }"
            :aria-expanded="hasChildren(row.node) ? (isExpanded(row.node) ? 'true' : 'false') : undefined"
          >
            <span
              class="tree-arrow"
              :class="hasChildren(row.node) ? (isExpanded(row.node) ? 'tree-arrow--expanded' : 'tree-arrow--collapsed') : 'tree-arrow--leaf'"
              aria-hidden="true"
              @click="toggleExpand(row.node)"
            />
            <span class="tree-title" :class="{ 'tree-title--cjk': isLongTitle(row.node.title) }">{{ row.node.title ?? '未命名目录' }}</span>
            <span class="tree-badge">{{ row.node.chapters.length }}</span>

            <div class="row-actions">
              <button
                class="row-btn"
                type="button"
                data-testid="action-add-catalog"
                :disabled="!canEdit"
                title="新建子目录"
                @click="openCreateCatalog(row.node.id)"
              >
                子目录
              </button>
              <button
                class="row-btn"
                type="button"
                data-testid="action-add-chapter"
                :disabled="!canEdit"
                title="新建章节"
                @click="openCreateChapter(row.node.id)"
              >
                新章节
              </button>
              <button
                class="row-btn"
                type="button"
                data-testid="action-rename"
                :disabled="!canEdit"
                title="重命名"
                @click="openRename(row.node)"
              >
                重命名
              </button>
              <button
                class="row-btn"
                type="button"
                data-testid="action-move"
                :disabled="!canEdit"
                title="移动"
                @click="openMove(row.node)"
              >
                移动
              </button>
              <button
                class="row-btn"
                type="button"
                data-testid="action-reorder"
                :disabled="!canEdit"
                title="重排"
                @click="openReorder(row.node)"
              >
                重排
              </button>
              <button
                class="row-btn row-btn--danger"
                type="button"
                data-testid="action-delete"
                :disabled="!canDelete"
                title="删除目录"
                @click="openDeleteCatalog(row.node)"
              >
                删除
              </button>
            </div>
          </div>

          <div v-if="!canEdit || !canDelete" class="row-blocked" :style="{ paddingLeft: `${row.depth * 24 + 16}px` }">
            <span v-if="!canEdit" class="blocked-text" data-testid="blocked-reason-EDIT">{{ editBlockedReason }}</span>
            <span v-if="!canDelete" class="blocked-text" data-testid="blocked-reason-DELETE">{{ deleteBlockedReason }}</span>
          </div>
        </div>

        <!-- 章节行 -->
        <div v-else-if="row.chapter" class="chapter-node" role="treeitem" :data-testid="`chapter-node-${row.chapter.id}`">
          <div class="tree-row chapter-row" :style="{ paddingLeft: `${(row.depth + 1) * 24}px` }">
            <span class="tree-arrow tree-arrow--leaf" aria-hidden="true" />
            <span class="tree-title" :class="{ 'tree-title--cjk': isLongTitle(row.chapter.title) }">{{ row.chapter.title }}</span>
            <span class="tree-badge">{{ row.chapter.pageCount }}页</span>

            <div class="row-actions">
              <button
                class="row-btn"
                type="button"
                data-testid="action-rename"
                :disabled="!canEdit"
                title="重命名章节"
                @click="openRename(row.node, row.chapter)"
              >
                重命名
              </button>
              <button
                class="row-btn"
                type="button"
                data-testid="action-move"
                :disabled="!canEdit"
                title="移动章节"
                @click="openMove(row.node, row.chapter)"
              >
                移动
              </button>
              <button
                class="row-btn"
                type="button"
                data-testid="action-reorder"
                :disabled="!canEdit"
                title="重排章节"
                @click="openReorder(row.node, row.chapter)"
              >
                重排
              </button>
              <button
                class="row-btn row-btn--danger"
                type="button"
                data-testid="action-trash"
                :disabled="!canDelete"
                title="回收章节"
                @click="openTrashChapter(row.node, row.chapter)"
              >
                回收
              </button>
            </div>
          </div>

          <div v-if="!canEdit || !canDelete" class="row-blocked" :style="{ paddingLeft: `${(row.depth + 1) * 24 + 16}px` }">
            <span v-if="!canEdit" class="blocked-text" data-testid="blocked-reason-EDIT">{{ editBlockedReason }}</span>
            <span v-if="!canDelete" class="blocked-text" data-testid="blocked-reason-DELETE">{{ deleteBlockedReason }}</span>
          </div>
        </div>
      </template>
    </div>

    <!-- ============ 对话框 ============ -->
    <Teleport to="body">
      <div v-if="dialog" class="dialog-overlay" @click.self="closeDialog">
        <div class="ws-dialog" role="dialog" aria-modal="true" :aria-label="dialogTitle">
          <h3 class="dialog-title">{{ dialogTitle }}</h3>

          <template v-if="dialog.kind === 'catalog-name' || dialog.kind === 'chapter-title'">
            <label class="dialog-label" :for="dialog.kind === 'catalog-name' ? 'catalog-name-input' : 'chapter-title-input'">
              名称
            </label>
            <input
              :id="dialog.kind === 'catalog-name' ? 'catalog-name-input' : 'chapter-title-input'"
              :data-testid="dialog.kind === 'catalog-name' ? 'catalog-name-input' : 'chapter-title-input'"
              v-model="nameInput"
              class="dialog-input"
              maxlength="255"
              placeholder="输入名称"
              @keyup.enter="confirmDialog"
            />
          </template>

          <template v-else-if="dialog.kind === 'move'">
            <p class="dialog-hint">选择目标位置（根目录或目录）：</p>
            <div class="dialog-options" data-testid="move-dialog">
              <button
                class="dialog-option"
                type="button"
                data-testid="move-target-root"
                @click="moveTarget = null; confirmDialog()"
              >
                根目录
              </button>
              <button
                v-for="target in moveTargets"
                :key="target.id"
                class="dialog-option"
                type="button"
                :data-testid="`move-target-${target.id}`"
                @click="moveTarget = target.id; confirmDialog()"
              >
                {{ target.title }}
              </button>
            </div>
          </template>

          <template v-else-if="dialog.kind === 'reorder'">
            <label class="dialog-label" for="reorder-position-input">目标位置</label>
            <input
              id="reorder-position-input"
              v-model.number="reorderPosition"
              class="dialog-input"
              data-testid="reorder-position-input"
              type="number"
              min="1"
              @keyup.enter="confirmDialog"
            />
            <div class="dialog-actions-inline" data-testid="reorder-dialog">
              <button
                class="action-btn action-btn--primary"
                type="button"
                data-testid="reorder-confirm"
                :disabled="reorderPosition < 1"
                @click="confirmDialog"
              >
                确认重排
              </button>
            </div>
          </template>

          <template v-else-if="dialog.kind === 'delete'">
            <p class="dialog-hint">删除目录后，其章节需要重挂到其他目录（不可删除自身/子目录）：</p>
            <div class="dialog-options dialog-options--check">
              <label v-for="target in reparentTargets" :key="target.id" class="dialog-check">
                <input
                  type="radio"
                  name="reparent"
                  :value="target.id"
                  v-model="reparentTarget"
                  :data-testid="`delete-reparent-${target.id}`"
                />
                <span>{{ target.title }}</span>
              </label>
            </div>
            <p v-if="deleteWarning" class="dialog-warning">{{ deleteWarning }}</p>
          </template>

          <template v-else-if="dialog.kind === 'trash'">
            <p class="dialog-hint">确定回收章节「{{ dialog.chapter?.title ?? '' }}」？回收后可恢复。</p>
          </template>

          <div v-if="dialog.kind !== 'reorder'" class="dialog-actions">
            <button class="action-btn action-btn--ghost" type="button" @click="closeDialog">取消</button>
            <button
              class="action-btn action-btn--primary"
              type="button"
              data-testid="catalog-confirm"
              :disabled="!canConfirm"
              @click="confirmDialog"
            >
              {{ dialogConfirmLabel }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useComicWorkspaceStore } from '@/stores/management/workspace'
import { OperationName } from '@/types/management/enums'
import type { CatalogTreeNode, CatalogTreeChapter } from '@/types/management/comic'

const store = useComicWorkspaceStore()

const canEdit = computed(() => store.can(OperationName.EDIT))
const canDelete = computed(() => store.can(OperationName.DELETE))
const editBlockedReason = computed(() => store.blockedReason(OperationName.EDIT) ?? '当前状态不允许编辑')
const deleteBlockedReason = computed(() => store.blockedReason(OperationName.DELETE) ?? '当前状态不允许删除')

type DialogKind = 'catalog-name' | 'chapter-title' | 'move' | 'reorder' | 'delete' | 'trash'

interface DialogState {
  readonly kind: DialogKind
  readonly node?: CatalogTreeNode
  readonly chapter?: CatalogTreeChapter
  readonly catalogId?: number
  readonly chapterId?: number
}

const dialog = ref<DialogState | null>(null)
const nameInput = ref('')
const moveTarget = ref<number | null | undefined>(undefined)
const reorderPosition = ref(1)
const reparentTarget = ref<number | null>(null)

/** 展开的目录 id 集合（null = 根级，根级始终展开） */
const expandedSet = ref<ReadonlySet<number>>(new Set())

interface CatalogRow {
  readonly key: string
  readonly kind: 'catalog' | 'chapter'
  readonly node: CatalogTreeNode
  readonly chapter?: CatalogTreeChapter
  readonly depth: number
}

function hasChildren(node: CatalogTreeNode): boolean {
  return node.children.length > 0
}

function isExpanded(node: CatalogTreeNode): boolean {
  if (node.id == null) return true
  return expandedSet.value.has(node.id)
}

function toggleExpand(node: CatalogTreeNode): void {
  if (!hasChildren(node)) return
  const next = new Set(expandedSet.value)
  if (next.has(node.id as number)) {
    next.delete(node.id as number)
  } else if (node.id != null) {
    next.add(node.id)
  }
  expandedSet.value = next
}

function isLongTitle(title: string | null | undefined): boolean {
  return (title?.length ?? 0) > 24
}

/** 展平目录树为可渲染行（目录行 + 章节行） */
const rows = computed<readonly CatalogRow[]>(() => {
  const result: CatalogRow[] = []
  const walk = (nodes: readonly CatalogTreeNode[], depth: number): void => {
    for (const node of nodes) {
      result.push({ key: `c-${node.id}`, kind: 'catalog', node, depth })
      if (isExpanded(node)) {
        for (const chapter of node.chapters) {
          result.push({ key: `ch-${chapter.id}`, kind: 'chapter', node, chapter, depth })
        }
        walk(node.children, depth + 1)
      }
    }
  }
  walk(store.catalog, 0)
  return result
})

/** 目录树中所有目录节点（作移动目标 / 重挂目标；排除自身与自身子树） */
const allCatalogs = computed<readonly { id: number; title: string }[]>(() => {
  const result: { id: number; title: string }[] = []
  const walk = (nodes: readonly CatalogTreeNode[]): void => {
    for (const node of nodes) {
      if (node.id != null && node.title != null) {
        result.push({ id: node.id, title: node.title })
      }
      walk(node.children)
    }
  }
  walk(store.catalog)
  return result
})

const moveTargets = computed(() => {
  const exclude = dialog.value?.node?.id ?? dialog.value?.catalogId ?? null
  return allCatalogs.value.filter((c) => c.id !== exclude)
})

const reparentTargets = computed(() => {
  const exclude = dialog.value?.node?.id ?? null
  return allCatalogs.value.filter((c) => c.id !== exclude)
})

const deleteWarning = computed(() => {
  const node = dialog.value?.node
  if (!node) return null
  if (node.chapters.length === 0 && node.children.length === 0) return null
  if (reparentTarget.value == null) return '该目录下有章节或子目录，必须选择重挂目标。'
  return null
})

const dialogTitle = computed(() => {
  switch (dialog.value?.kind) {
    case 'catalog-name': return dialog.value?.node ? '重命名目录' : '新建目录'
    case 'chapter-title': return dialog.value?.chapter ? '重命名章节' : '新建章节'
    case 'move': return '移动位置'
    case 'reorder': return '重排位置'
    case 'delete': return '删除目录'
    case 'trash': return '回收章节'
    default: return ''
  }
})

const dialogConfirmLabel = computed(() => {
  switch (dialog.value?.kind) {
    case 'delete': return '删除'
    case 'trash': return '回收'
    case 'move': return '移动'
    case 'reorder': return '重排'
    default: return '确定'
  }
})

const canConfirm = computed(() => {
  const d = dialog.value
  if (!d) return false
  switch (d.kind) {
    case 'catalog-name':
    case 'chapter-title':
      return nameInput.value.trim().length > 0
    case 'move':
      return true
    case 'reorder':
      return reorderPosition.value >= 1
    case 'delete':
      return deleteWarning.value == null && reparentTarget.value != null
    case 'trash':
      return true
    default:
      return false
  }
})

function openCreateCatalog(catalogId: number | null): void {
  dialog.value = { kind: 'catalog-name', catalogId: catalogId ?? undefined }
  nameInput.value = ''
}

function openCreateChapter(catalogId: number | null): void {
  dialog.value = { kind: 'chapter-title', catalogId: catalogId ?? undefined }
  nameInput.value = ''
}

function openRename(node: CatalogTreeNode, chapter?: CatalogTreeChapter): void {
  if (chapter) {
    dialog.value = { kind: 'chapter-title', node, chapter, chapterId: chapter.id }
    nameInput.value = chapter.title
  } else {
    dialog.value = { kind: 'catalog-name', node, catalogId: node.id ?? undefined }
    nameInput.value = node.title ?? ''
  }
}

function openMove(node: CatalogTreeNode, chapter?: CatalogTreeChapter): void {
  dialog.value = chapter
    ? { kind: 'move', node, chapter, chapterId: chapter.id }
    : { kind: 'move', node, catalogId: node.id ?? undefined }
  moveTarget.value = undefined
}

function openReorder(node: CatalogTreeNode, chapter?: CatalogTreeChapter): void {
  dialog.value = chapter
    ? { kind: 'reorder', node, chapter, chapterId: chapter.id }
    : { kind: 'reorder', node, catalogId: node.id ?? undefined }
  reorderPosition.value = 1
}

function openDeleteCatalog(node: CatalogTreeNode): void {
  dialog.value = { kind: 'delete', node, catalogId: node.id ?? undefined }
  reparentTarget.value = null
}

function openTrashChapter(node: CatalogTreeNode, chapter: CatalogTreeChapter): void {
  dialog.value = { kind: 'trash', node, chapter, chapterId: chapter.id }
}

function closeDialog(): void {
  dialog.value = null
}

async function confirmDialog(): Promise<void> {
  const d = dialog.value
  if (!d) return
  switch (d.kind) {
    case 'catalog-name': {
      const title = nameInput.value.trim()
      if (!title) return
      if (d.node) {
        await store.renameCatalog(d.node.id as number, title)
      } else {
        await store.createCatalog({ title, parentId: d.catalogId ?? null })
      }
      break
    }
    case 'chapter-title': {
      const title = nameInput.value.trim()
      if (!title) return
      if (d.chapter) {
        await store.renameChapter(d.chapter.id, title)
      } else {
        await store.createChapter({ title, catalogId: d.catalogId ?? null })
      }
      break
    }
    case 'move': {
      if (d.chapter) {
        await store.moveChapter(d.chapter.id, moveTarget.value ?? null)
      } else {
        await store.moveCatalog(d.node?.id as number, moveTarget.value ?? null)
      }
      break
    }
    case 'reorder': {
      if (d.chapter) {
        await store.reorderChapter(d.chapter.id, reorderPosition.value)
      } else {
        await store.reorderCatalog(d.node?.id as number, reorderPosition.value)
      }
      break
    }
    case 'delete': {
      if (deleteWarning.value) return
      await store.deleteCatalog(d.node?.id as number, reparentTarget.value)
      break
    }
    case 'trash': {
      await store.trashChapter(d.chapter?.id as number)
      break
    }
    default:
      return
  }
  closeDialog()
}

onMounted(async () => {
  await store.loadCatalog()
  // 默认展开第一层目录（显示章节）
  const roots = store.catalog
  const initial = new Set<number>()
  for (const node of roots) {
    if (node.id != null) initial.add(node.id)
  }
  expandedSet.value = initial
})
</script>

<style scoped>
.catalog-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 760px;
  min-width: 0;
}

.catalog-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.toolbar-blocked {
  font-size: var(--text-xs);
  color: var(--warning);
}

.catalog-error {
  margin: 0;
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px solid var(--danger);
  color: var(--danger);
  font-size: var(--text-sm);
}

.catalog-empty {
  padding: var(--space-8);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px dashed var(--border-strong);
  color: var(--text-muted);
  font-size: var(--text-sm);
  text-align: center;
}

.catalog-tree {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  overflow-x: auto;
  min-block-size: 0;
}

.catalog-node,
.chapter-node {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.catalog-row,
.chapter-row {
  min-width: 0;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-shrink: 0;
}

.row-btn {
  min-height: 28px;
  padding: 2px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-xs);
  background: none;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 600;
  cursor: pointer;
  transition:
    color var(--transition-fast),
    background-color var(--transition-fast);
}

.row-btn:hover:not(:disabled) {
  color: var(--text-primary);
  background: var(--bg-primary);
}

.row-btn:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: -2px;
}

.row-btn:disabled {
  cursor: not-allowed;
  opacity: var(--disabled-opacity);
}

.row-btn--danger {
  color: var(--danger);
}

.row-blocked {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.blocked-text {
  font-size: 10px;
  color: var(--warning);
}

.ws-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  padding: var(--space-16) 0;
  color: var(--text-secondary);
  font-size: var(--text-sm);
}

/* ============ 对话框 ============ */
.dialog-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-dialog);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-8);
  background: var(--color-overlay-scrim);
}

.ws-dialog {
  width: 100%;
  max-width: 440px;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-8);
  border-radius: var(--radius-lg);
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
  max-height: 80vh;
  overflow-y: auto;
  min-block-size: 0;
}

.dialog-title {
  margin: 0;
  font-size: var(--text-section);
  font-weight: 700;
  color: var(--text-primary);
}

.dialog-label {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-muted);
}

.dialog-input {
  display: block;
  width: 100%;
  box-sizing: border-box;
  min-height: var(--control-min-size);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  font-family: var(--font-ui);
  font-size: var(--text-sm);
  outline: none;
  transition: border-color var(--transition-fast);
}

.dialog-input:focus {
  border-color: var(--accent);
}

.dialog-hint {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-secondary);
  line-height: 1.6;
}

.dialog-options {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-height: 240px;
  overflow-y: auto;
  min-block-size: 0;
}

.dialog-options--check {
  gap: var(--space-1);
}

.dialog-option {
  display: flex;
  align-items: center;
  min-height: var(--control-min-size);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  text-align: left;
  transition:
    background-color var(--transition-fast),
    border-color var(--transition-fast);
}

.dialog-option:hover {
  background: var(--surface-highlight);
  border-color: var(--border-strong);
}

.dialog-option:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: -2px;
}

.dialog-check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: var(--control-min-size);
  padding: var(--space-1) var(--space-2);
  font-size: var(--text-sm);
  color: var(--text-secondary);
  cursor: pointer;
}

.dialog-warning {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--warning);
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-2);
}
</style>
