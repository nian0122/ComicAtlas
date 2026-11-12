<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useReaderStore } from '@/features/reader-navigation'
import { useReadingNavigation } from '@/features/reading-navigation'
import { ReadingMarkDialog, type ReadingMarkTarget } from '@/features/reading-marks'
import { parsePageInput } from '@/features/page-jump'
import { comicApi, type ComicDetailVO } from '@/entities/comic'
import { getApiErrorMessage } from '@/shared/api/http'
import { useHistoryStore } from '@/entities/history'
import { CinemaViewport, readerPageSource, type ReaderImageQuality } from '@/widgets/cinema-viewport'
import { AppButton } from '@/shared/ui/button'
import { ReadingIcon, ReadingDialog, ReadingState } from '@/shared/ui/reading'
import { RecycleScroller } from 'vue-virtual-scroller'
const route = useRoute(),
  store = useReaderStore(),
  history = useHistoryStore(),
  navigation = useReadingNavigation()
const viewport = ref<InstanceType<typeof CinemaViewport>>(),
  readerRoot = ref<HTMLElement>(),
  catalogScroller = ref<{
    scrollToItem: (
      index: number,
      options?: { align?: 'start' | 'center' | 'end' | 'nearest'; smooth?: boolean },
    ) => void
  }>(),
  comic = ref<ComicDetailVO>(),
  toolbar = ref(false),
  sheet = ref<'jump' | 'catalog' | 'settings' | 'mark' | ''>(''),
  input = ref<string | number>(''),
  invalid = ref(false),
  ready = ref(false)
const mode = ref<'scroll' | 'paged'>('scroll'),
  fit = ref<'width' | 'screen'>('width'),
  gap = ref(false),
  quality = ref<ReaderImageQuality>('lq')
type PreparedImage = { chapterId: string; source: string; url: string }
const activeImage = ref<PreparedImage>()
const preparingNext = ref(false)
let nextImage: PreparedImage | undefined
let nextImageTimer: ReturnType<typeof setTimeout> | undefined
let preparation: AbortController | undefined
let attemptedChapter: string | undefined
function clearNextImage() {
  clearTimeout(nextImageTimer)
  if (nextImage) URL.revokeObjectURL(nextImage.url)
  nextImage = undefined
}
function stopPreparing() {
  preparation?.abort()
  preparation = undefined
  preparingNext.value = false
  attemptedChapter = undefined
  clearNextImage()
}
async function prepareNext() {
  const id = store.nextChapterId
  if (!ready.value || !id || attemptedChapter === id) return
  attemptedChapter = id
  const controller = new AbortController()
  preparation = controller
  preparingNext.value = true
  const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(5000)])
  try {
    const chapter = await store.prepareChapter(id)
    const first = chapter.items[0]
    if (signal.aborted || first?.mediaType !== 'image') return
    const source = readerPageSource(first, quality.value)
    if (!source) return
    const response = await fetch(source, { signal, priority: 'low' })
    const maxBytes = 16 * 1024 * 1024
    if (!response.ok || Number(response.headers.get('Content-Length')) > maxBytes) {
      await response.body?.cancel()
      return
    }
    const image = await response.blob()
    if (signal.aborted || image.size > maxBytes) return
    const url = URL.createObjectURL(image)
    const preview = new Image()
    preview.src = url
    try {
      await preview.decode()
    } catch {
      URL.revokeObjectURL(url)
      return
    }
    if (signal.aborted) {
      URL.revokeObjectURL(url)
      return
    }
    nextImage = { chapterId: id, source, url }
    nextImageTimer = setTimeout(clearNextImage, 60_000)
  } catch {
    // 后台准备失败时，用户打开章节后走正常加载流程。
  } finally {
    if (preparation === controller) {
      preparation = undefined
      preparingNext.value = false
    }
  }
}
watch(quality, () => {
  stopPreparing()
  if (activeImage.value) URL.revokeObjectURL(activeImage.value.url)
  activeImage.value = undefined
})
const progress = computed(() => (store.totalPages > 1 ? ((store.currentPage - 1) / (store.totalPages - 1)) * 100 : 0))
type ReadingAnchor = { comicId: string; chapterId: string; mediaId: string }
const completedAnchor = ref<ReadingAnchor | null>(null)
const completionSaved = ref(false)
const finalChapter = computed(() => ready.value && !store.nextChapterId && store.totalPages > 0)
const completionLabel = computed(() => {
  if (completionSaved.value) return '已读完'
  if (!completedAnchor.value) return '标为读完'
  return store.progressSaveError ? '读完状态待保存' : '正在保存'
})
const markTargets = computed<ReadingMarkTarget[]>(() => {
  const targets: ReadingMarkTarget[] = []
  if (store.comicId)
    targets.push({
      kind: 'comic',
      id: store.comicId,
      label: '作品',
      title: comic.value?.title || '当前作品',
    })
  if (store.chapterId)
    targets.push({
      kind: 'chapter',
      id: store.chapterId,
      label: '章节',
      title: store.chapterTitle || '当前章节',
    })
  const media = store.pages[store.currentPage - 1]
  if (media)
    targets.push({
      kind: 'media',
      id: media.id,
      label: '当前媒体',
      title: `第 ${store.currentPage} 页 · ${media.mediaType === 'video' ? '视频' : '图片'}`,
    })
  return targets
})
try {
  const saved = JSON.parse(localStorage.getItem('comicatlas.web.reader.v1') || '{}')
  mode.value = saved.mode === 'paged' ? 'paged' : 'scroll'
  fit.value = saved.fit === 'screen' ? 'screen' : 'width'
  gap.value = saved.gap === true
  quality.value = saved.quality === 'hq' ? 'hq' : 'lq'
} catch {
  /* 存储不可用时使用默认偏好。 */
}
watch([mode, fit, gap, quality], () => {
  try {
    localStorage.setItem(
      'comicatlas.web.reader.v1',
      JSON.stringify({ mode: mode.value, fit: fit.value, gap: gap.value, quality: quality.value }),
    )
  } catch {
    /* 不阻断阅读。 */
  }
})
let loadSequence = 0,
  saveTimer: ReturnType<typeof setTimeout> | undefined,
  hideTimer: ReturnType<typeof setTimeout> | undefined,
  saveChain = Promise.resolve(),
  lastWrite: { key: string } | undefined
function snapshot() {
  if (completedAnchor.value?.comicId === store.comicId) return completedAnchor.value
  const media = store.pages[store.currentPage - 1]
  return media
    ? {
        comicId: store.comicId,
        chapterId: store.chapterId,
        mediaId: media.id,
      }
    : null
}
function persist() {
  clearTimeout(saveTimer)
  if (!ready.value || store.progressLoadError) return
  const value = snapshot()
  if (!value) return
  const key = JSON.stringify([value.comicId, value.chapterId, value.mediaId])
  if (lastWrite?.key === key) return
  const write = { key }
  lastWrite = write
  // 同一客户端写入串行化，防止快速翻页的旧响应覆盖最新进度。
  saveChain = saveChain
    .catch(() => {})
    .then(async () => {
      try {
        await history.recordProgress(value.comicId, value.chapterId, value.mediaId)
        if (completedAnchor.value === value) completionSaved.value = true
        if (store.comicId === value.comicId) store.progressSaveError = null
      } catch (reason) {
        if (lastWrite === write) lastWrite = undefined
        if (store.comicId === value.comicId) store.progressSaveError = getApiErrorMessage(reason, '进度保存失败')
      }
    })
}
function complete() {
  const last = store.pages.at(-1)
  if (!finalChapter.value || !last || store.progressLoadError || completedAnchor.value) return
  // 服务端用末章末页判断读完，回翻只移动当前视口。
  completedAnchor.value = { comicId: store.comicId, chapterId: store.chapterId, mediaId: last.id }
  persist()
}
async function load() {
  persist()
  ready.value = false
  const sequence = ++loadSequence
  clearTimeout(hideTimer)
  toolbar.value = false
  sheet.value = ''
  comic.value = undefined
  const id = String(route.params.chapterId)
  if (activeImage.value && activeImage.value.chapterId !== id) {
    URL.revokeObjectURL(activeImage.value.url)
    activeImage.value = undefined
  }
  if (nextImage?.chapterId === id) {
    activeImage.value = nextImage
    nextImage = undefined
  }
  stopPreparing()
  if (!id || id.length > 128) {
    store.discardPreparedChapter()
    store.error = '无效章节'
    store.loading = false
    return
  }
  await store.loadChapter(id)
  if (sequence !== loadSequence || store.error) return
  if (completedAnchor.value && completedAnchor.value.comicId !== store.comicId) {
    completedAnchor.value = null
    completionSaved.value = false
  }
  if (typeof route.query.media === 'string') {
    if (!store.goToMedia(route.query.media)) {
      store.error = '请求的媒体不在当前章节'
      return
    }
  } else await store.restoreProgress()
  if (sequence !== loadSequence) return
  await nextTick()
  await viewport.value?.jump(store.currentPage)
  if (sequence !== loadSequence) return
  ready.value = true
  try {
    const data = await comicApi.detail(store.comicId)
    if (sequence === loadSequence) comic.value = data
  } catch {
    /* 作品元数据异常不阻断已加载的章节。 */
  }
}
watch(
  () => route.params.chapterId,
  () => void load(),
  { immediate: true },
)
watch([ready, () => store.currentPage, () => store.progressLoadError], ([isReady, page, loadError], [wasReady]) => {
  if (!isReady || loadError) return
  if (finalChapter.value && page === store.totalPages) {
    complete()
  } else if (wasReady && !completedAnchor.value) {
    clearTimeout(saveTimer)
    saveTimer = setTimeout(persist, 700)
  }
})
function position(page: number) {
  if (ready.value) store.goToPage(page)
}
function hide() {
  if (!toolbar.value) return
  const active = document.activeElement
  if (sheet.value || active?.closest('.cinema-hud')) return
  collapse()
}
function collapse() {
  toolbar.value = false
  void nextTick(() => readerRoot.value?.focus({ preventScroll: true }))
}
function showToolbar() {
  if (toolbar.value) {
    collapse()
    return
  }
  toolbar.value = true
  scheduleHide()
}
function scheduleHide() {
  clearTimeout(hideTimer)
  hideTimer = setTimeout(hide, 4500)
}
function revealToolbar() {
  if (sheet.value) return
  toolbar.value = true
  scheduleHide()
}
function hideByScroll() {
  if (!toolbar.value || sheet.value) return
  collapse()
}
function ignoreGestureTarget(target: EventTarget | null): boolean {
  return target instanceof Element && !!target.closest('.cinema-hud, dialog, input, select, textarea')
}
function onWheel(event: WheelEvent) {
  if (mode.value !== 'scroll' || sheet.value || event.ctrlKey || Math.abs(event.deltaY) < 8) return
  if (ignoreGestureTarget(event.target)) return
  if (event.deltaY > 0) hideByScroll()
  else revealToolbar()
}
let touchStartY = 0,
  touchStartX = 0,
  touchHandled = false
function onTouchStart(event: TouchEvent) {
  touchHandled = true
  if (mode.value !== 'scroll' || sheet.value || event.touches.length !== 1) return
  if (ignoreGestureTarget(event.target)) return
  const touch = event.touches[0]!
  touchStartY = touch.clientY
  touchStartX = touch.clientX
  touchHandled = false
}
function onTouchMove(event: TouchEvent) {
  if (mode.value !== 'scroll' || sheet.value || touchHandled || event.touches.length !== 1) return
  const touch = event.touches[0]!
  const deltaY = touch.clientY - touchStartY,
    deltaX = touch.clientX - touchStartX
  if (Math.abs(deltaY) < 12 || Math.abs(deltaX) > Math.abs(deltaY)) return
  touchHandled = true
  if (deltaY < 0) hideByScroll()
  else revealToolbar()
}
function open(kind: 'jump' | 'catalog' | 'settings' | 'mark') {
  sheet.value = kind
  clearTimeout(hideTimer)
  if (kind === 'jump') {
    input.value = String(store.currentPage)
    invalid.value = false
  }
}
async function scrollCatalogToCurrent() {
  await nextTick()
  requestAnimationFrame(() =>
    requestAnimationFrame(() => {
      const index = (comic.value?.chapters ?? []).findIndex((item) => item.id === store.chapterId)
      if (index > 0) catalogScroller.value?.scrollToItem(index, { align: 'center' })
    }),
  )
}
watch(
  () => sheet.value,
  (value) => {
    if (value === 'catalog') void scrollCatalogToCurrent()
  },
)
watch(
  () => comic.value?.chapters.length,
  () => {
    if (sheet.value === 'catalog') void scrollCatalogToCurrent()
  },
)
async function jump(page: number) {
  store.goToPage(page)
  sheet.value = ''
  await nextTick()
  await viewport.value?.jump(page)
}
function submitPage() {
  const page = parsePageInput(input.value, store.totalPages)
  if (page === null) {
    invalid.value = true
    return
  }
  void jump(page)
}
function chapter(id: string | null) {
  if (!id) return
  persist()
  navigation.goToReader(id)
}
function openVideo() {
  const media = store.pages[store.currentPage - 1]
  if (!store.chapterId) return
  navigation.goToVideo(store.chapterId, { ...route.query, ...(media ? { media: media.id } : {}) })
}
function leave() {
  persist()
  navigation.goToDetail(store.comicId)
}
function keydown(event: KeyboardEvent) {
  if (sheet.value) return
  const target = event.target as HTMLElement
  if (event.key === 'Escape' && toolbar.value) {
    collapse()
    return
  }
  if (target.closest('input,select,textarea,button,a,video')) return
  if (event.code === 'Space') {
    event.preventDefault()
    showToolbar()
  }
  if (mode.value === 'paged' && ['ArrowLeft', 'ArrowRight'].includes(event.key)) {
    event.preventDefault()
    void jump(Math.min(store.totalPages, Math.max(1, store.currentPage + (event.key === 'ArrowRight' ? 1 : -1))))
  }
}
function pagehide() {
  const value = snapshot()
  if (ready.value && !store.progressLoadError && value && !completionSaved.value) history.recordProgressKeepalive(value)
}
async function retryRestore() {
  await store.restoreProgress()
  if (!store.progressLoadError) {
    await viewport.value?.jump(store.currentPage)
  }
}
onMounted(() => {
  window.addEventListener('wheel', onWheel, { passive: true })
  window.addEventListener('touchstart', onTouchStart, { passive: true })
  window.addEventListener('touchmove', onTouchMove, { passive: true })
  window.addEventListener('keydown', keydown)
  window.addEventListener('pagehide', pagehide)
})
onBeforeUnmount(() => {
  persist()
  stopPreparing()
  store.discardPreparedChapter()
  if (activeImage.value) URL.revokeObjectURL(activeImage.value.url)
  loadSequence++
  clearTimeout(saveTimer)
  clearTimeout(hideTimer)
  window.removeEventListener('wheel', onWheel)
  window.removeEventListener('touchstart', onTouchStart)
  window.removeEventListener('touchmove', onTouchMove)
  window.removeEventListener('keydown', keydown)
  window.removeEventListener('pagehide', pagehide)
})
</script>
<template>
  <div ref="readerRoot" class="ca-scope cinema-reader" tabindex="-1">
    <div v-if="store.progressLoadError" class="cinema-save-error" role="alert">
      {{ store.progressLoadError }}<AppButton variant="text" @click="retryRestore">重试恢复位置</AppButton>
    </div>
    <div v-if="store.progressSaveError" class="cinema-save-error" role="alert">
      {{ store.progressSaveError }}<AppButton variant="text" @click="persist">重试保存</AppButton>
    </div>
    <ReadingState v-if="store.loading" loading /><ReadingState
      v-else-if="store.error"
      error
      :title="store.error"
      action="重试"
      @action="load"
      ><AppButton variant="text" @click="leave">返回</AppButton></ReadingState
    ><ReadingState v-else-if="!store.pages.length" title="本章暂无可阅读内容"
      ><AppButton variant="text" @click="leave">返回章节目录</AppButton></ReadingState
    >
    <template v-else>
      <header v-if="toolbar" class="cinema-hud cinema-hud-top" aria-label="阅读信息">
        <AppButton variant="text" icon-only aria-label="返回作品详情" @click="leave"
          ><ReadingIcon name="back"
        /></AppButton>
        <div class="cinema-reader-title">
          <strong :title="comic?.title">{{ comic?.title || '漫画阅读' }}</strong
          ><span :title="store.chapterTitle">{{ store.chapterTitle }}</span>
        </div>
        <AppButton class="cinema-top-tool" variant="text" :disabled="!store.chapterId" @click="openVideo"
          ><ReadingIcon name="video" :size="18" /><span>短视频</span></AppButton
        ><AppButton
          class="cinema-top-tool"
          variant="text"
          :aria-expanded="sheet === 'settings'"
          aria-controls="reader-settings"
          @click="open('settings')"
          ><ReadingIcon name="settings" :size="18" /><span>设置</span></AppButton
        >
      </header>
      <CinemaViewport
        ref="viewport"
        :pages="store.pages"
        :current="store.currentPage"
        :mode="mode"
        :fit="fit"
        :gap="gap"
        :quality="quality"
        :enabled="ready"
        :preparing-next="preparingNext"
        :prepared-image="activeImage"
        @position="position"
        @prepare-next="prepareNext"
        @toggle="showToolbar"
      />
      <div v-if="mode === 'paged'" class="cinema-paged-actions">
        <AppButton :disabled="store.currentPage <= 1" @click="jump(store.currentPage - 1)">上一页</AppButton
        ><AppButton :disabled="store.currentPage >= store.totalPages" @click="jump(store.currentPage + 1)"
          >下一页</AppButton
        >
      </div>
      <section v-if="mode === 'scroll' || store.currentPage === store.totalPages" class="cinema-chapter-end">
        <ReadingIcon :name="!store.nextChapterId && completionSaved ? 'check' : 'library'" :size="30" />
        <h2>{{ store.nextChapterId ? '本章末尾' : completionSaved ? '全书读完' : '全书末尾' }}</h2>
        <AppButton v-if="store.nextChapterId" variant="primary" @click="chapter(store.nextChapterId)"
          >下一章<ReadingIcon name="next" :size="18" /></AppButton
        ><AppButton variant="text" @click="leave">返回章节目录</AppButton>
      </section></template
    >
    <footer
      v-if="toolbar && !store.loading && !store.error"
      class="cinema-hud cinema-hud-bottom"
      aria-label="阅读工具栏"
    >
      <div class="cinema-bar">
        <AppButton class="cinema-page-number" variant="text" aria-label="点击页码跳转" @click="open('jump')"
          ><b>{{ store.currentPage }}</b> / {{ store.totalPages }}</AppButton
        ><input
          class="cinema-progress"
          type="range"
          min="1"
          :max="store.totalPages || 1"
          :value="store.currentPage"
          :disabled="store.totalPages <= 1"
          :style="{ '--reader-progress': `${progress}%` }"
          :aria-valuetext="`${store.currentPage} / ${store.totalPages}`"
          aria-label="阅读进度"
          @change="jump(Number(($event.target as HTMLInputElement).value))"
        />
        <AppButton
          v-if="finalChapter"
          class="cinema-completion"
          variant="text"
          :aria-label="completionLabel"
          :aria-disabled="!!completedAnchor || !!store.progressLoadError"
          @click="complete"
        >
          <ReadingIcon v-if="completionSaved" name="check" :size="16" />
          <span role="status">{{ completionLabel }}</span>
        </AppButton>
        <nav class="cinema-tools" aria-label="阅读工具">
          <AppButton
            class="cinema-tool"
            variant="text"
            :aria-expanded="sheet === 'mark'"
            aria-controls="reader-marks"
            @click="open('mark')"
            ><ReadingIcon name="heart" :size="19" /><span>标记</span></AppButton
          ><AppButton
            class="cinema-tool"
            variant="text"
            :disabled="!store.prevChapterId"
            @click="chapter(store.prevChapterId)"
            ><ReadingIcon name="previous" :size="19" /><span>上一章</span></AppButton
          ><AppButton
            class="cinema-tool"
            variant="text"
            :aria-expanded="sheet === 'catalog'"
            aria-controls="reader-catalog"
            @click="open('catalog')"
            ><ReadingIcon name="list" :size="19" /><span>目录</span></AppButton
          ><AppButton
            class="cinema-tool"
            variant="text"
            :disabled="!store.nextChapterId"
            @click="chapter(store.nextChapterId)"
            ><span>下一章</span><ReadingIcon name="following" :size="19"
          /></AppButton>
        </nav>
      </div>
    </footer>
    <ReadingDialog class="cinema-tool-sheet" :open="sheet === 'jump'" title="跳转页码" @close="sheet = ''"
      ><form novalidate @submit.prevent="submitPage">
        <label class="ca-field"
          >页码（1–{{ store.totalPages }}）<input
            v-model="input"
            type="number"
            inputmode="numeric"
            min="1"
            :max="store.totalPages"
            :aria-invalid="invalid || undefined"
            autofocus
        /></label>
        <p v-if="invalid" class="ca-inline-error" role="alert">请输入 1–{{ store.totalPages }} 之间的整数页码</p>
        <AppButton class="cinema-jump-submit" variant="primary" type="submit">跳转</AppButton>
      </form></ReadingDialog
    >
    <ReadingDialog
      id="reader-settings"
      class="cinema-tool-sheet"
      :open="sheet === 'settings'"
      title="阅读设置"
      @close="sheet = ''"
      ><label class="ca-field"
        >阅读方向<select v-model="mode">
          <option value="scroll">纵向滚动</option>
          <option value="paged">横向翻页</option>
        </select></label
      ><label class="ca-field"
        >适配模式<select v-model="fit">
          <option value="width">适应宽度</option>
          <option value="screen">适应屏幕</option>
        </select></label
      ><label class="ca-field"
        >图片画质<select v-model="quality" aria-describedby="reader-quality-help">
          <option value="lq">省流优先</option>
          <option value="hq">高清优先</option>
        </select></label
      >
      <p id="reader-quality-help" class="cinema-quality-help">
        {{
          quality === 'lq'
            ? '先加载轻量图；双击单页可看高清。没有轻量图时使用高清原件。'
            : '直接加载高清原件，流量明显增加；没有高清原件时回落到轻量图。'
        }}
      </p>
      <label class="ca-check"><span>页面间留白</span><input v-model="gap" type="checkbox" /></label
    ></ReadingDialog>
    <ReadingMarkDialog id="reader-marks" :open="sheet === 'mark'" :targets="markTargets" @close="sheet = ''" />
    <ReadingDialog
      id="reader-catalog"
      class="cinema-tool-sheet"
      :open="sheet === 'catalog'"
      title="章节目录"
      @close="sheet = ''"
      ><RecycleScroller
        ref="catalogScroller"
        class="cinema-catalog"
        :items="comic?.chapters ?? []"
        :data-total="comic?.chapters.length ?? 0"
        key-field="id"
        :item-size="64"
        :buffer="160"
        ><template #default="{ item }">
          <AppButton
            class="cinema-catalog-row"
            variant="text"
            :aria-current="item.id === store.chapterId ? 'page' : undefined"
            @click="chapter(item.id)"
            ><span class="cinema-catalog-order">第 {{ item.globalOrder }} 话</span><strong>{{ item.title }}</strong
            ><ReadingIcon v-if="item.id === store.chapterId" name="check" :size="17"
          /></AppButton> </template
      ></RecycleScroller>
      <p v-if="!comic">章节目录暂不可用，请返回作品详情重试。</p></ReadingDialog
    >
  </div>
</template>
<style scoped>
.cinema-reader {
  min-height: 100dvh;
  background: var(--atlas-bg);
  padding-bottom: 50px;
}
.cinema-reader:focus {
  outline: none;
}
.cinema-hud {
  position: fixed;
  right: 0;
  left: 0;
  z-index: 25;
  color: #f2f0ec;
  background: #0b0b0ae6;
  backdrop-filter: blur(12px);
}
.cinema-hud-top {
  top: 0;
  display: flex;
  align-items: center;
  gap: 14px;
  height: 52px;
  padding: 0 20px;
  padding-top: env(safe-area-inset-top);
  border-bottom: 1px solid #ffffff14;
}
.cinema-reader-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex: 1;
  min-width: 0;
}
.cinema-reader-title strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 0.1em;
}
.cinema-reader-title > span {
  min-width: 0;
  max-width: 40%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11.5px;
  letter-spacing: 0.1em;
  color: #8e8b86;
}
.cinema-hud-bottom {
  bottom: 0;
  padding: 0 20px;
  padding-bottom: env(safe-area-inset-bottom);
  border-top: 1px solid #ffffff14;
}
.cinema-top-tool {
  height: 44px;
  padding: 0 12px;
  color: #cfcbc4;
  background: none;
  border: 0;
  border-radius: 0;
  font-size: 12px;
  letter-spacing: 0.1em;
}
.cinema-top-tool:hover:not(:disabled) {
  color: #f2f0ec;
  background: #ffffff0f;
}
.cinema-top-tool :deep(.app-button-content) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.cinema-bar {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 18px;
  height: 64px;
}

.cinema-hud-top .app-button {
  min-width: 32px;
  min-height: 32px;
  padding: 0;
  color: #d8d5cf;
  background: none;
  border: 0;
  border-radius: 0;
}

.cinema-hud-top .app-button:hover:not(:disabled) {
  background: #ffffff0f;
  color: #f2f0ec;
}
.cinema-hud-bottom .cinema-page-number {
  min-height: 36px;
  padding: 0 4px;
  font-size: 12px;
  letter-spacing: 0.06em;
  color: #8e8b86;
  background: none;
  border: 0;
  border-radius: 0;
  font-variant-numeric: tabular-nums;
}
.cinema-hud-bottom .cinema-page-number b {
  font-size: 15px;
  font-weight: 500;
  color: #f2f0ec;
}
.cinema-hud-bottom .cinema-page-number:hover {
  color: #f2f0ec;
  background: none;
}
.cinema-progress {
  appearance: none;
  flex: 1 1 0;
  display: block;
  width: auto;
  height: 40px;
  margin: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
.cinema-progress::-webkit-slider-runnable-track {
  height: 2px;
  background: linear-gradient(to right, #f2f0ec var(--reader-progress), #ffffff1f var(--reader-progress));
}
.cinema-progress::-webkit-slider-thumb {
  appearance: none;
  width: 12px;
  height: 12px;
  margin-top: -5px;
  border: 0;
  border-radius: 50%;
  background: #f2f0ec;
}
.cinema-progress::-moz-range-track {
  height: 2px;
  background: #ffffff1f;
}
.cinema-progress::-moz-range-progress {
  height: 2px;
  background: #f2f0ec;
}
.cinema-progress::-moz-range-thumb {
  width: 12px;
  height: 12px;
  border: 0;
  border-radius: 50%;
  background: #f2f0ec;
}
.cinema-completion {
  min-height: 44px;
  flex: none;
  padding: 0 var(--atlas-space-2);
  color: inherit;
  font-size: 12px;
  white-space: nowrap;
}
.cinema-completion[aria-disabled='true'] {
  cursor: default;
}
.cinema-tools {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: none;
}
.cinema-tools .cinema-tool {
  height: 40px;
  padding: 0 12px;
  font-size: 12px;
  letter-spacing: 0.1em;
  color: #cfcbc4;
  background: none;
  border: 0;
  border-radius: 0;
}
.cinema-tools .cinema-tool:hover:not(:disabled) {
  background: #ffffff0f;
}
.cinema-tools .cinema-tool:disabled {
  color: #56534e;
}
.cinema-tool :deep(.app-button-content) {
  gap: 8px;
}
.cinema-tool[aria-expanded='true'] {
  background: #ffffff0f;
}
.cinema-tool-sheet {
  padding: 0;
  color: #f2f0ec;
  background: #111110f7;
  border: 1px solid #ffffff1f;
  border-radius: 0;
}
.cinema-tool-sheet :deep(header) {
  height: 54px;
  margin: 0;
  padding: 0 20px;
  border-bottom: 1px solid #ffffff12;
}
.cinema-tool-sheet :deep(h2) {
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 15px;
  font-weight: 400;
  letter-spacing: 0.12em;
}
.cinema-tool-sheet :deep(.ca-field) {
  margin: 0;
  padding: 16px 20px;
}
.cinema-tool-sheet :deep(.ca-check) {
  margin: 0;
  padding: 15px 20px;
  background: none;
  border: 0;
  border-bottom: 1px solid #ffffff0f;
  border-radius: 0;
}
.cinema-quality-help {
  margin: -12px 20px 18px;
  color: var(--atlas-muted);
  font-size: 12px;
  line-height: 1.65;
}
.cinema-tool-sheet :deep(.cinema-jump-submit) {
  margin: 18px 20px 20px;
}
.cinema-catalog {
  display: block;
  max-height: 60dvh;
}
.cinema-catalog .app-button {
  justify-content: flex-start;
  padding: 12px;
  text-align: left;
  border-radius: 0;
}
.cinema-catalog [aria-current='page'] {
  color: var(--atlas-text);
  background: var(--atlas-selected);
  border-color: var(--atlas-control-border);
}
.cinema-catalog-row {
  width: 100%;
  height: 56px;
  margin-bottom: 8px;
}
.cinema-catalog-row :deep(.app-button-content) {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  width: 100%;
  justify-content: flex-start;
}
.cinema-catalog-order {
  flex-shrink: 0;
  margin-right: 6px;
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--atlas-faint);
}
.cinema-catalog-row strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.08em;
}
@media (max-width: 899px) {
  .cinema-hud-top {
    padding: 0 16px;
    padding-top: env(safe-area-inset-top);
  }
  .cinema-reader-title {
    flex-direction: column;
    align-items: flex-start;
    gap: 1px;
  }
  .cinema-reader-title > span {
    max-width: 100%;
    font-size: 10.5px;
  }
  .cinema-hud-top .cinema-top-tool {
    gap: 6px;
    padding: 0 8px;
    font-size: 11px;
  }
  .cinema-hud-top .cinema-top-tool :deep(.app-button-content) {
    gap: 6px;
  }
  .cinema-hud-bottom {
    padding: 0 16px;
  }
  .cinema-bar {
    flex-wrap: wrap;
    gap: 0 12px;
    height: auto;
    padding: 6px 0 0;
  }
  .cinema-hud-bottom .cinema-page-number {
    min-height: 40px;
  }
  .cinema-progress {
    flex: 1 1 0;
    height: 40px;
  }
  .cinema-tools {
    width: calc(100% + 32px);
    margin: 0 -16px;
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    border-top: 1px solid #ffffff0f;
  }
  .cinema-tools .cinema-tool {
    height: 58px;
    padding: 0;
    border-left: 1px solid #ffffff0f;
    font-size: 11px;
  }
  .cinema-tools .cinema-tool:first-child {
    border-left: 0;
  }
  .cinema-tool :deep(.app-button-content) {
    flex-direction: column;
    gap: 4px;
  }

  .cinema-tool :deep(svg) {
    order: -1;
  }
  .cinema-tool-sheet {
    position: fixed;
    inset: auto 0 0;
    width: 100%;
    max-width: 100%;
    max-height: 80dvh;
    margin: 0;
    border-right: 0;
    border-bottom: 0;
    border-left: 0;
  }
}
.cinema-chapter-end {
  max-width: 450px;
  margin: auto;
  padding: 65px 24px;
  text-align: center;
}
.cinema-chapter-end > svg {
  display: block;
  margin: 0 auto 22px;
  color: var(--atlas-faint);
}
.cinema-chapter-end h2 {
  font-size: 21px;
  font-weight: 500;
  margin: 0 0 26px;
}
.cinema-chapter-end .app-button {
  display: flex;
  min-width: 240px;
  margin: 10px auto;
}
.cinema-save-error {
  position: fixed;
  top: calc(92px + env(safe-area-inset-top));
  left: 50%;
  transform: translateX(-50%);
  z-index: 40;
  background: var(--atlas-raised);
  padding: 10px 15px;
  font-size: 12px;
  width: max-content;
  max-width: 95%;
}
.cinema-paged-actions {
  display: flex;
  justify-content: center;
  gap: 15px;
  padding: 18px;
}
</style>
