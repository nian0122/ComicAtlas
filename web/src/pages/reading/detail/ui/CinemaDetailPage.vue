<script setup lang="ts">
import { AppSearch } from '@/shared/ui/search'
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { comicApi, type ComicDetailVO } from '@/entities/comic'
import { reactionApi } from '@/entities/reaction'
import { useReadingNavigation } from '@/features/reading-navigation'
import { AppButton } from '@/shared/ui/button'
import { ReadingIcon, ReadingState, RetryImage } from '@/shared/ui/reading'
import { historyApi, type Progress } from '@/entities/history'
import { getApiErrorMessage } from '@/shared/api/http'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import { filterChapters, catalogLabels } from '../model/chapter-filter'
const route = useRoute(),
  navigation = useReadingNavigation(),
  comic = ref<ComicDetailVO>(),
  progress = ref<Progress | null>(null),
  reaction = ref<'none' | 'like' | 'dislike' | null>(null),
  loading = ref(false),
  error = ref(''),
  progressLoading = ref(false),
  progressError = ref(''),
  reactionError = ref(''),
  expanded = ref(false),
  unread = ref(false),
  reverse = ref(false),
  chapterQuery = ref(''),
  selectedCatalog = ref(''),
  markBusy = ref(false),
  cta = ref<HTMLElement>(),
  chapterScroller = ref<{
    scrollToItem: (
      index: number,
      options?: { align?: 'start' | 'center' | 'end' | 'nearest'; smooth?: boolean },
    ) => void
  }>(),
  showSticky = ref(false)
let seq = 0
const chapters = computed(() =>
  filterChapters(
    comic.value?.chapters ?? [],
    comic.value?.catalogs ?? [],
    chapterQuery.value,
    selectedCatalog.value,
    progress.value?.chapterId ?? null,
    unread.value,
    reverse.value,
  ),
)
const catalogs = computed(() => catalogLabels(comic.value?.catalogs ?? []))
function clearChapterFilters() {
  chapterQuery.value = ''
  selectedCatalog.value = ''
  unread.value = false
}
const resume = computed(() => {
  const c = comic.value
  const first = c?.chapters[0]
  return progress.value?.chapterId
    ? `/reader/${encodeURIComponent(progress.value.chapterId)}?media=${encodeURIComponent(progress.value.mediaId)}`
    : first
      ? `/reader/${first.id}`
      : ''
})
const readLabel = computed(() =>
  progress.value?.chapterId ? '继续阅读' : progressError.value ? '从第一章读起' : '开始阅读',
)
async function loadProgress(id: string, current: number) {
  progressLoading.value = true
  progressError.value = ''
  try {
    const saved = await historyApi.get(id)
    if (current === seq) progress.value = saved
  } catch {
    if (current === seq) progressError.value = '上次阅读位置暂时无法加载'
  } finally {
    if (current === seq) progressLoading.value = false
  }
}
async function loadReaction(id: string, current: number) {
  reactionError.value = ''
  try {
    const opinion = await reactionApi.get('comic', id)
    if (current === seq) reaction.value = opinion.value
  } catch {
    if (current === seq) reactionError.value = '标记状态暂时无法加载'
  }
}
async function load() {
  const current = ++seq
  const id = String(route.params.id)
  loading.value = true
  error.value = ''
  comic.value = undefined
  progress.value = null
  reaction.value = null
  markBusy.value = false
  showSticky.value = false
  void loadProgress(id, current)
  void loadReaction(id, current)
  try {
    const data = await comicApi.detail(id)
    if (current === seq) comic.value = data
  } catch (reason) {
    if (current === seq) error.value = getApiErrorMessage(reason, '作品加载失败或已不存在')
  } finally {
    if (current === seq) loading.value = false
  }
}
watch(cta, (element, _, onCleanup) => {
  if (!element) return
  const observer = new IntersectionObserver(([entry]) => {
    if (entry) showSticky.value = !entry.isIntersecting && entry.boundingClientRect.bottom < 0
  })
  observer.observe(element)
  onCleanup(() => observer.disconnect())
})
async function mark(value: 'like' | 'dislike') {
  if (!comic.value || reaction.value === null || markBusy.value) return
  const current = seq
  markBusy.value = true
  reactionError.value = ''
  const next = reaction.value === value ? 'none' : value
  try {
    const saved = await reactionApi.set('comic', comic.value.id, next)
    if (current === seq) reaction.value = saved.value
  } catch (reason) {
    if (current === seq) reactionError.value = getApiErrorMessage(reason, '标记未保存，请重试')
  } finally {
    if (current === seq) markBusy.value = false
  }
}
async function locate() {
  clearChapterFilters()
  await nextTick()
  const index = chapters.value.findIndex((item) => item.id === progress.value?.chapterId)
  if (index >= 0) chapterScroller.value?.scrollToItem(index, { align: 'center' })
}
watch(
  () => route.params.id,
  () => {
    expanded.value = false
    clearChapterFilters()
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  seq++
})
</script>
<template>
  <div class="cinema-detail">
    <AppButton variant="text" class="cinema-back" @click="navigation.goToSource"
      ><ReadingIcon name="back" />返回</AppButton
    ><ReadingState v-if="loading" loading /><ReadingState
      v-else-if="error"
      error
      :title="error"
      action="重试"
      @action="load"
    />
    <div v-else-if="comic" class="cinema-detail-layout">
      <aside class="cinema-book-info">
        <div class="cinema-info-head">
          <RetryImage
            class="cinema-detail-cover"
            :src="comic.coverUrl"
            :alt="comic.title"
            :width="2"
            :height="3"
            eager
          />
          <div>
            <h1>{{ comic.title }}</h1>
            <p v-if="comic.author">{{ comic.author }}</p>
            <span>{{ comic.chapters.length }} 章</span>
          </div>
        </div>
        <div ref="cta" class="cinema-detail-actions">
          <RouterLink v-if="resume && !progressLoading" :to="resume" class="app-button app-button--primary"
            ><ReadingIcon name="play" />{{ readLabel }}</RouterLink
          ><AppButton
            variant="secondary"
            icon-only
            :aria-label="reaction === 'like' ? '取消喜欢' : '喜欢作品'"
            :aria-pressed="reaction === 'like'"
            :disabled="reaction === null || markBusy"
            title="喜欢"
            @click="mark('like')"
            ><ReadingIcon name="heart"
          /></AppButton>
          <AppButton
            variant="secondary"
            icon-only
            :aria-label="reaction === 'dislike' ? '取消不喜欢' : '不喜欢作品'"
            :aria-pressed="reaction === 'dislike'"
            :disabled="reaction === null || markBusy"
            title="不喜欢"
            @click="mark('dislike')"
            ><ReadingIcon name="dislike"
          /></AppButton>
        </div>
        <p v-if="progressLoading" class="ca-muted" role="status">正在读取上次阅读位置</p>
        <p v-if="progress?.chapterId" class="ca-muted">上次读到第 {{ progress?.pageNumber || 1 }} 页</p>
        <div v-if="progressError" class="ca-inline-error" role="alert">
          {{ progressError }}
          <AppButton variant="text" @click="loadProgress(comic.id, seq)">重试阅读位置</AppButton>
        </div>
        <div v-if="reactionError" class="ca-inline-error" role="alert">
          {{ reactionError }}
          <AppButton v-if="reaction === null" variant="text" @click="loadReaction(comic.id, seq)"
            >重试标记状态</AppButton
          >
        </div>
        <div v-if="comic.description" class="cinema-description">
          <p :class="{ collapsed: !expanded }">{{ comic.description }}</p>
          <AppButton variant="text" :aria-expanded="expanded" @click="expanded = !expanded"
            >{{ expanded ? '收起简介' : '展开简介' }}<ReadingIcon name="down" :size="14"
          /></AppButton>
        </div>
        <div class="cinema-tags">
          <span v-if="comic.categoryName">{{ comic.categoryName }}</span
          ><span v-for="tag in comic.tags" :key="tag.name">{{ tag.name }}</span>
        </div>
      </aside>
      <section class="cinema-chapters">
        <header>
          <h2>
            章节目录 <small>{{ comic.chapters.length }}</small>
          </h2>
          <AppButton v-if="progress?.chapterId" variant="text" @click="locate">定位续读</AppButton>
        </header>
        <div class="cinema-chapter-search">
          <AppSearch v-model="chapterQuery" label="搜索章节" placeholder="搜索章节名称或序号" /><select
            v-if="catalogs.length"
            v-model="selectedCatalog"
            aria-label="章节目录分组"
          >
            <option value="">全部目录</option>
            <option v-for="catalog in catalogs" :key="catalog.id" :value="catalog.id">{{ catalog.label }}</option>
          </select>
        </div>
        <div class="cinema-chapter-filter">
          <label
            ><input v-model="unread" type="checkbox" :disabled="progressLoading || !!progressError" />只看未读</label
          ><AppButton variant="text" @click="reverse = !reverse"
            >{{ reverse ? '倒序' : '正序' }}<ReadingIcon name="down" :size="14"
          /></AppButton>
        </div>
        <DynamicScroller
          v-if="chapters.length"
          ref="chapterScroller"
          class="cinema-chapter-list"
          :items="chapters"
          :data-total="chapters.length"
          key-field="id"
          :min-item-size="60"
          :buffer="300"
        >
          <template #default="{ item, active }">
            <DynamicScrollerItem
              :item="item"
              :active="active"
              :size-dependencies="[item.title, item.globalOrder]"
              class="cinema-chapter-item"
            >
              <RouterLink
                :to="`/reader/${item.id}${item.id === progress?.chapterId ? `?media=${encodeURIComponent(progress.mediaId)}` : ''}`"
                class="cinema-chapter"
                :class="{ current: item.id === progress?.chapterId }"
                ><span class="cinema-chapter-number">{{ item.globalOrder }}</span
                ><span class="cinema-chapter-title"
                  >{{ item.title || `第 ${item.globalOrder} 话`
                  }}<small v-if="item.id === progress?.chapterId">上次阅读</small></span
                ><ReadingIcon name="following" :size="16"
              /></RouterLink>
            </DynamicScrollerItem>
          </template>
        </DynamicScroller>
        <ReadingState
          v-if="!chapters.length"
          :title="comic.chapters.length ? '没有匹配的章节' : '暂无章节'"
          :message="comic.chapters.length ? '试试其他章节名称或目录。' : '章节入库后即可开始阅读。'"
          :action="comic.chapters.length ? '清除章节筛选' : ''"
          @action="clearChapterFilters"
        />
      </section>
    </div>
    <div v-if="comic && showSticky && resume && !progressLoading" class="cinema-sticky-resume">
      <span>{{ comic.title }}</span
      ><RouterLink :to="resume" class="app-button app-button--primary">{{ readLabel }}</RouterLink>
    </div>
  </div>
</template>
<style scoped>
.cinema-chapter-search {
  display: flex;
  gap: 10px;
  margin: 12px 0;
  flex-wrap: wrap;
}
.cinema-chapter-search .atlas-search {
  min-width: 160px;
}
.cinema-chapter-search > select {
  max-width: 100%;
}
.cinema-info-head h1 {
  overflow-wrap: anywhere;
}

.cinema-detail {
  max-width: 1180px;
  margin-inline: auto;
  padding-top: 16px;
}
.cinema-back {
  margin: 0 0 20px -12px;
}
.cinema-detail-layout {
  display: grid;
  grid-template-columns: clamp(230px, 23vw, 300px) minmax(0, 1fr);
  gap: clamp(36px, 6vw, 88px);
}
.cinema-book-info {
  position: sticky;
  top: 100px;
  align-self: start;
}
.cinema-detail-cover {
  width: min(100%, 238px);
  flex: none;
  border-radius: var(--atlas-radius-cover);
  background: var(--atlas-surface);
  margin-bottom: 24px;
}
.cinema-detail-cover :deep(img) {
  object-fit: cover;
}
.cinema-detail-cover :deep(.ca-image-failure) {
  gap: 6px;
  padding: 4px;
}
.cinema-info-head h1 {
  margin: 0 0 12px;
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: clamp(28px, 2.6vw, 36px);
  font-weight: 500;
  line-height: 1.3;
  letter-spacing: 0.04em;
}
.cinema-info-head p,
.cinema-info-head span {
  font-size: 13px;
  color: var(--atlas-muted);
  margin: 0 0 8px;
}
.cinema-detail-actions {
  display: flex;
  gap: 10px;
  margin: 22px 0 12px;
}
.cinema-detail-actions > a {
  flex: 1;
}
.cinema-detail-actions [aria-pressed='true'] {
  color: var(--atlas-text);
  background: var(--atlas-selected);
}
.cinema-detail-actions [aria-pressed='true'] :deep(svg) {
  fill: currentColor;
}
.cinema-book-info .ca-inline-error {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0 8px;
  padding: 0;
}
.cinema-book-info .ca-inline-error .app-button {
  margin-left: 0;
}
.cinema-description {
  margin-top: 24px;
}
.cinema-description p {
  font-size: 13px;
  line-height: 1.9;
  color: var(--atlas-muted);
  white-space: pre-line;
}
.cinema-description .collapsed {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.cinema-description .app-button {
  padding: 0;
  font-size: 11px;
}
.cinema-tags {
  display: flex;
  gap: 7px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.cinema-tags span {
  padding: 4px 10px;
  background: transparent;
  color: var(--atlas-muted);
  font-size: 10.5px;
  letter-spacing: 0.1em;
  border: 1px solid var(--atlas-line);
  border-radius: 0;
}
.cinema-chapters > header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cinema-chapters h2 {
  margin: 0;
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 20px;
  font-weight: 400;
  letter-spacing: 0.06em;
}
.cinema-chapters h2 small {
  font-size: 12px;
  color: var(--atlas-faint);
  margin-left: 6px;
}
.cinema-chapter-filter {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--atlas-muted);
  border-bottom: 1px solid var(--atlas-line);
  margin-bottom: 4px;
}
.cinema-chapter-filter label {
  display: flex;
  align-items: center;
  gap: 7px;
}
.cinema-chapter-filter input {
  accent-color: var(--atlas-accent);
}
.cinema-chapter-list {
  max-height: min(70dvh, 880px);
}
.cinema-chapter-list :deep(.vue-recycle-scroller__item-wrapper) {
  overflow: visible;
}
.cinema-chapter-item {
  width: 100%;
}
.cinema-chapter {
  display: grid;
  grid-template-columns: minmax(30px, auto) minmax(0, 1fr) 16px;
  gap: 14px;
  align-items: center;
  min-height: 60px;
  padding: 14px 12px;
  border-bottom: 1px solid var(--atlas-line);
  text-decoration: none;
  color: var(--atlas-text);
  font-size: 15px;
}
.cinema-chapter:hover {
  background: var(--atlas-surface);
}
.cinema-chapter.current {
  background: var(--atlas-selected);
}
.cinema-chapter-number {
  font-size: 11px;
  color: var(--atlas-faint);
  font-variant-numeric: tabular-nums;
}
.cinema-chapter-title {
  min-width: 0;
  overflow-wrap: anywhere;
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  letter-spacing: 0.08em;
}
.cinema-chapter small {
  display: block;
  font-size: 12px;
  color: var(--atlas-error);
  margin-top: 4px;
}
.cinema-chapter.current .cinema-chapter-number {
  color: var(--atlas-error);
}
.cinema-sticky-resume {
  display: none;
}
@media (max-width: 899px) {
  .cinema-detail {
    padding-top: 6px;
  }
  .cinema-back {
    margin-bottom: 12px;
  }
  .cinema-detail-layout {
    display: block;
    padding: 0;
  }
  .cinema-book-info {
    position: static;
    margin-bottom: 28px;
  }
  .cinema-info-head {
    display: flex;
    gap: 16px;
    align-items: center;
  }
  .cinema-detail-cover {
    width: clamp(92px, 27vw, 132px);
    margin: 0;
  }
  .cinema-info-head h1 {
    font-size: clamp(25px, 7vw, 34px);
  }
  .cinema-description {
    margin-top: 14px;
  }
  .cinema-detail-actions {
    margin-top: 16px;
  }
  .cinema-sticky-resume {
    display: flex;
    position: fixed;
    bottom: calc(68px + env(safe-area-inset-bottom));
    left: 0;
    right: 0;
    padding: 12px var(--ca-gutter);
    gap: 14px;
    align-items: center;
    justify-content: space-between;
    background: var(--atlas-raised);
    border-top: 1px solid var(--atlas-line);
    z-index: 15;
  }
  .cinema-sticky-resume > span {
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .cinema-sticky-resume > a {
    white-space: nowrap;
  }
}
</style>
