<script setup lang="ts">
import { computed, reactive, ref, nextTick, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { reactionKey, reactionTypes, type ManagedReaction, type ReactionType } from '@/entities/managed-reaction'
import { ManagementIcon, ManagementImage, ManagementDialog, formatDate } from '@/shared/ui/management'
import { ManagementPagedFeed } from '@/shared/ui/pagination'
import ManagementReactionConfirm from './ManagementReactionConfirm.vue'
import { useManagedReactions } from '../model/use-managed-reactions'
import { canRecycleDislike, type RecyclePreview, type RecycleRequest } from '../model/recycle'
const props = defineProps<{ revision: number; writable: boolean; recycled: number }>()
const emit = defineEmits<{ recycle: [payload: RecycleRequest] }>()
const w = reactive(useManagedReactions(props))
const isDislikeView = computed(() => w.value === 'dislike')
const hasFilters = computed(() => Boolean(w.targetType || w.value || w.mediaType || w.includeTrashed))
const selectedRecycleType = computed<ReactionType | ''>(() => {
  const types = new Set(w.selectedDislikes.map((item) => item.targetType))
  return types.size === 1 ? ([...types][0] ?? '') : ''
})
function titleId(item: ManagedReaction) {
  return `reaction-${item.targetType}-${item.targetId}`
}
interface RecycleItem {
  targetId: string
  previewUrl?: string | null
}
function previews(items: RecycleItem[]): RecyclePreview[] {
  return items.map((item) => ({ id: item.targetId, url: item.previewUrl ?? null }))
}
function recycle(items: RecycleItem[], scope: RecycleRequest['scope'], targetType: RecycleRequest['targetType']) {
  if (!props.writable || w.loading || w.listFailed) return
  emit('recycle', {
    scope,
    targetType,
    mediaType: w.mediaType,
    ids: scope === 'ids' ? items.map((item) => item.targetId) : [],
    previews: previews(items),
  })
}
function recycleOne(item: ManagedReaction) {
  if (canRecycleDislike(item)) recycle([item], 'ids', item.targetType)
}
function recycleSelected() {
  const targetType = selectedRecycleType.value
  if (!targetType || !w.selectedDislikes.length) return
  recycle(
    w.selectedDislikes.filter((item) => item.targetType === targetType),
    'ids',
    targetType,
  )
}
const canBulkRecycle = computed(
  () =>
    isDislikeView.value &&
    (w.targetType === 'chapter' || w.targetType === 'media') &&
    !w.includeTrashed &&
    w.hasLoaded &&
    w.total > 0,
)
const bulkHint = computed(() => {
  if (!isDislikeView.value || !w.rows.length) return ''
  if (w.includeTrashed) return '包含回收内容时不支持批量回收；取消“包含回收内容”后可继续。'
  if (w.targetType === 'comic') return '漫画不支持按筛选批量回收；勾选漫画后使用“回收所选”。'
  if (w.targetType === '') return '批量回收需先选择章节或媒体类型；漫画请勾选后回收。'
  return ''
})
const bulkLabel = computed(() => (w.targetType === 'chapter' ? '章节' : w.targetType === 'media' ? '媒体' : ''))
function recycleCurrent() {
  const targetType = w.targetType
  if (targetType !== 'chapter' && targetType !== 'media') return
  if (!canBulkRecycle.value) return
  recycle(
    w.rows.filter((item) => item.targetType === targetType && item.value === 'dislike' && !item.hidden),
    'filter',
    targetType,
  )
}
watch(
  () => props.recycled,
  () => {
    w.selected = []
  },
)
const preview = ref<ManagedReaction | null>(null),
  failed = ref(false),
  previewVersion = ref(0)
function toggle(item: ManagedReaction, event: Event) {
  w.toggle(item)
  ;(event.target as HTMLInputElement).checked = w.selectedKeys.has(reactionKey(item))
}
function selectPage(event: Event) {
  w.selectPage()
  ;(event.target as HTMLInputElement).checked = w.allPage
}
function openPreview(item: ManagedReaction) {
  if (item.hidden || !item.contentUrl) return
  failed.value = false
  previewVersion.value++
  preview.value = item
}
async function closePreview() {
  const key = preview.value ? reactionKey(preview.value) : ''
  preview.value = null
  await nextTick()
  document
    .querySelector<HTMLButtonElement>(`[data-reaction-preview="${CSS.escape(key)}"]`)
    ?.focus({ preventScroll: true })
}
function retryPreview() {
  failed.value = false
  previewVersion.value++
}
/** 管理端预览图片优先读取轻量副本；视频没有质量变体，仍使用内容地址。 */
function previewSource(item: ManagedReaction): string | undefined {
  return item.mediaType === 'video' ? item.contentUrl : (item.previewUrl ?? item.contentUrl)
}
function location(item: ManagedReaction) {
  return {
    path: '/manage',
    query: {
      comic: item.comicId,
      chapter:
        item.targetType === 'comic'
          ? undefined
          : item.targetType === 'chapter'
            ? item.targetId
            : (item.chapterId ?? undefined),
    },
  }
}
</script>
<template>
  <div class="rating-filters">
    <label
      >对象类型<select v-model="w.targetType" aria-label="标记对象类型" :disabled="w.loading" @change="w.changeType">
        <option value="">全部类型</option>
        <option v-for="(label, type) in reactionTypes" :key="type" :value="type">{{ label }}</option>
      </select></label
    >
    <label
      >标记<select v-model="w.value" aria-label="标记筛选" :disabled="w.loading" @change="w.filters">
        <option value="">全部标记</option>
        <option value="like">喜欢</option>
        <option value="dislike">不喜欢</option>
      </select></label
    >
    <label v-if="w.targetType === 'media'"
      >媒体类型<select v-model="w.mediaType" aria-label="标记媒体类型" :disabled="w.loading" @change="w.filters">
        <option value="">全部媒体</option>
        <option value="image">图片</option>
        <option value="video">视频</option>
      </select></label
    >
    <label
      >排序<select v-model="w.oldest" aria-label="标记时间排序" :disabled="w.loading" @change="w.filters">
        <option :value="false">最近标记优先</option>
        <option :value="true">最早标记优先</option>
      </select></label
    >
    <label class="rating-filter-check"
      ><input v-model="w.includeTrashed" type="checkbox" :disabled="w.loading" @change="w.filters" />包含回收内容</label
    >
    <button class="mw-button" :disabled="w.loading" @click="w.load()"><ManagementIcon name="refresh" />刷新标记</button>
  </div>
  <p v-if="w.selected.length" class="rating-filter-note" role="status">
    改变筛选会清除已选 {{ w.selected.length }} 项；请先完成当前整理。
  </p>
  <p v-if="w.error && !(w.listFailed && !w.rows.length)" class="mw-error" role="alert">{{ w.error }}</p>
  <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
  <p v-if="w.listFailed && w.rows.length" class="mw-muted">以下为上次成功结果，重新读取后才能修改。</p>
  <div v-if="w.rows.length || w.selected.length" class="rating-selection">
    <label class="mw-select-page"
      ><input
        type="checkbox"
        aria-label="选择已加载可修改标记"
        :checked="w.allPage"
        :disabled="w.loading || w.listFailed || !writable"
        @change="selectPage"
      />已加载可修改项</label
    ><span>已选 {{ w.selected.length }} / 100 项 · 共 {{ w.hasLoaded ? w.total : '—' }} 项</span>
    <template v-if="w.selected.length">
      <button class="mw-button" @click="w.selected = []">清除选择</button>
      <button
        v-if="selectedRecycleType"
        class="mw-button rating-selection-recycle"
        :disabled="w.loading || w.listFailed"
        @click="recycleSelected"
      >
        <ManagementIcon name="trash" :size="14" />回收所选 {{ w.selectedDislikes.length }} 项
      </button>
      <span v-else-if="w.selectedDislikes.length" class="rating-selection-note">
        回收所选需同一对象类型；请按类型筛选后分别回收。
      </span>
      <button class="mw-button mw-button-primary" :disabled="!writable || !w.canEdit || w.loading" @click="w.edit">
        修改所选标记
      </button>
    </template>
    <template v-else>
      <button
        v-if="canBulkRecycle"
        class="mw-button mw-button-danger rating-bulk"
        :disabled="w.loading || w.listFailed"
        @click="recycleCurrent"
      >
        <ManagementIcon name="trash" :size="14" />回收筛选中的{{ bulkLabel }} {{ w.hasLoaded ? w.total : '—' }} 项
      </button>
      <span v-else-if="bulkHint" class="rating-selection-note rating-selection-hint">{{ bulkHint }}</span>
    </template>
  </div>
  <p v-if="w.loading" class="mw-loading">正在读取标记…</p>
  <div v-else-if="w.listFailed && !w.rows.length" class="mw-empty" role="alert">
    <ManagementIcon name="warning" :size="32" />
    <h2>暂时无法读取标记</h2>
    <p>{{ w.error || '请检查本机管理服务后重试。' }}</p>
    <button class="mw-button" @click="w.load()">重试读取标记</button>
  </div>
  <div v-else-if="!w.rows.length" class="mw-empty">
    <ManagementIcon name="heart" :size="32" />
    <h2>{{ hasFilters ? '没有符合当前筛选的标记' : '还没有任何标记' }}</h2>
    <p>
      {{
        hasFilters ? '清除筛选可查看全部标记，也可以去漫画库添加新的标记。' : '从漫画库选择漫画、章节或媒体添加标记。'
      }}
    </p>
    <button v-if="hasFilters" class="mw-button" @click="w.clearFilters">清除筛选</button>
    <RouterLink v-else class="mw-button" to="/manage">去漫画库</RouterLink>
  </div>
  <div v-else>
    <ManagementPagedFeed
      :items="w.rows"
      :page="w.page"
      :total="w.total"
      :loading="w.loading"
      :loading-more="w.loadingMore"
      :append-error="w.appendError"
      :disabled="w.listFailed || !w.hasLoaded"
      label="标记分页"
      @page-change="w.changePage"
      @load-more="w.loadMore"
      @retry="w.retryLoadMore"
    >
      <template #default="{ items }">
        <div class="rating-grid">
          <article
            v-for="item in items"
            :key="reactionKey(item)"
            class="rating-card"
            :class="{ 'rating-selected': w.selectedKeys.has(reactionKey(item)) }"
            :aria-labelledby="titleId(item)"
          >
            <div class="rating-visual">
              <div v-if="item.hidden" class="rating-hidden" role="img" aria-label="回收内容不可预览">
                <ManagementIcon name="trash" :size="30" /><span>回收内容不可预览</span>
              </div>
              <button
                v-else-if="item.targetType === 'media'"
                class="rating-preview"
                :data-reaction-preview="reactionKey(item)"
                :aria-label="'预览标记媒体' + item.title"
                :disabled="!item.contentUrl"
                @click="openPreview(item)"
              >
                <ManagementImage :src="item.previewUrl ?? null" :alt="item.title" /><ManagementIcon
                  v-if="item.mediaType === 'video'"
                  class="rating-video"
                  name="video"
                  :size="24"
                />
              </button>
              <RouterLink
                v-else
                class="rating-preview"
                :to="location(item)"
                :aria-label="'打开标记' + reactionTypes[item.targetType] + item.title"
                ><ManagementImage :src="item.previewUrl ?? null" :alt="item.title"
              /></RouterLink>
              <label class="rating-select">
                <input
                  type="checkbox"
                  :aria-label="'选择标记' + reactionTypes[item.targetType] + '：' + item.title"
                  :checked="w.selectedKeys.has(reactionKey(item))"
                  :disabled="!writable || w.listFailed || !item.canChange"
                  @change="toggle(item, $event)"
                />
              </label>
              <span class="rating-badge">{{ item.value === 'like' ? '喜欢' : '不喜欢' }}</span>
            </div>
            <strong :id="titleId(item)" class="rating-title" :title="item.title">{{ item.title }}</strong
            ><small
              >{{ reactionTypes[item.targetType]
              }}{{ item.mediaType === 'video' ? ' · 视频' : item.mediaType === 'image' ? ' · 图片' : ''
              }}{{ item.pageNumber !== null ? ' · 第 ' + item.pageNumber + ' 项' : '' }}</small
            ><small v-if="item.targetType !== 'comic'" :title="item.comicTitle">{{ item.comicTitle }}</small
            ><small>{{ formatDate(item.updatedAt) }}</small>
            <p v-if="!item.canChange" class="mw-muted">状态变更中，暂不可修改</p>
            <p v-else-if="item.hidden" class="mw-muted">内容或所属层级已回收</p>
            <div v-else-if="item.targetType === 'media' || item.value === 'dislike'" class="rating-actions">
              <RouterLink v-if="item.targetType === 'media'" class="rating-locate" :to="location(item)"
                >打开所在章节</RouterLink
              >
              <button
                v-if="writable && item.value === 'dislike'"
                class="rating-recycle-one"
                :disabled="w.loading || w.listFailed"
                :aria-label="'移入回收站：' + item.title"
                @click="recycleOne(item)"
              >
                <ManagementIcon name="trash" :size="13" />移入回收站
              </button>
            </div>
          </article>
        </div>
      </template>
    </ManagementPagedFeed>
  </div>
  <ManagementReactionConfirm
    :open="w.confirmOpen"
    :items="w.confirmItems"
    scope="management"
    @close="w.confirmOpen = false"
    @saved="w.saved($event.length)"
  />
  <ManagementDialog
    :open="!!preview"
    :title="preview?.title || '媒体预览'"
    class="rating-media-dialog"
    @close="closePreview"
    ><template v-if="preview && !preview.hidden"
      ><div v-if="failed" class="mw-empty">
        <p>当前媒体无法预览，可从所属章节检查格式和文件。</p>
        <button class="mw-button" @click="retryPreview">重试预览</button>
      </div>
      <video
        v-else-if="preview.mediaType === 'video'"
        :key="previewVersion"
        :src="previewSource(preview)"
        controls
        playsinline
        preload="metadata"
        @error="failed = true" /><img
        v-else
        :key="previewVersion"
        :src="previewSource(preview)"
        :alt="preview.title"
        @error="failed = true" /></template
  ></ManagementDialog>
</template>
<style scoped>
.rating-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: end;
  margin: 20px 0;
}
.rating-filters label {
  display: grid;
  gap: 6px;
  color: var(--atlas-muted);
  font-size: 12px;
}
.rating-filters select {
  min-width: 120px;
}
.rating-filters .rating-filter-check {
  display: flex;
  align-items: center;
  align-self: center;
  gap: 6px;
}
.rating-filter-note {
  margin: -4px 0 18px;
  color: var(--atlas-muted);
  font-size: 12px;
}
.rating-selection {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  padding: 12px;
  background: var(--atlas-raised);
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  margin-bottom: 18px;
  position: sticky;
  top: 0;
  z-index: 2;
  font-size: 12px;
}
.rating-selection-note {
  color: var(--atlas-muted);
  font-size: 12px;
}
.rating-selection-hint {
  margin-left: auto;
  text-align: right;
}
.rating-bulk {
  margin-left: auto;
}
.rating-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.rating-recycle-one {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 36px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: var(--atlas-radius-control);
  background: transparent;
  color: var(--atlas-muted);
  font-size: 12px;
  cursor: pointer;
  transition:
    background-color var(--atlas-control-transition),
    border-color var(--atlas-control-transition),
    color var(--atlas-control-transition);
}
.rating-recycle-one:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--atlas-error) 35%, var(--atlas-line));
  background: var(--atlas-error-surface);
  color: var(--atlas-error);
}
.rating-recycle-one:disabled {
  opacity: var(--atlas-control-disabled-opacity);
  cursor: not-allowed;
}
.rating-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 20px 16px;
}
.rating-card {
  min-width: 0;
}
.rating-visual {
  position: relative;
  aspect-ratio: 2/3;
  background: var(--atlas-surface);
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-cover);
  overflow: hidden;
}
.rating-selected .rating-visual {
  border-color: var(--atlas-accent);
}
.rating-preview {
  display: block;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.rating-preview :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.rating-hidden {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  justify-content: center;
  color: var(--atlas-muted);
  font-size: 11px;
}
.rating-select {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  cursor: pointer;
}
.rating-select input {
  accent-color: var(--atlas-accent);
  width: 17px;
  height: 17px;
  margin: 0;
}
.rating-badge {
  position: absolute;
  bottom: 8px;
  left: 8px;
  background: #141414d9;
  color: #f5f5f5;
  border: 1px solid #ffffff26;
  padding: 4px 7px;
  border-radius: 6px;
  font-size: 11px;
  pointer-events: none;
}
.rating-video {
  position: absolute;
  right: 10px;
  top: 10px;
}
.rating-title {
  display: block;
  margin-top: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.rating-card small {
  display: block;
  font-size: 12px;
  color: var(--atlas-muted);
  margin-top: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rating-locate {
  font-size: 12px;
  color: var(--atlas-muted);
  display: inline-block;
}
.rating-media-dialog {
  width: min(1040px, calc(100vw - 32px));
  max-width: calc(100vw - 32px);
}
.rating-media-dialog video,
.rating-media-dialog img {
  display: block;
  width: 100%;
  max-height: 78vh;
  object-fit: contain;
}
@media (max-width: 600px) {
  .rating-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px 9px;
  }
  .rating-filters label {
    flex: 1;
    min-width: 120px;
  }
  .rating-filters select {
    min-width: 0;
    width: 100%;
  }
  .rating-hidden span {
    font-size: 10px;
    text-align: center;
  }
  .rating-locate {
    font-size: 11px;
  }
}
</style>
