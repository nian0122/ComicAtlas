<template>
  <main class="short-video-page" @touchstart.passive="onTouchStart" @touchend="onTouchEnd" @wheel.prevent="onWheel">
    <header class="video-header">
      <AppButton class="video-back" type="button" aria-label="返回漫画详情" @click="goBack">←</AppButton>
      <span v-if="items.length" class="video-count"
        >{{ currentIndex + 1 }} <span>/ {{ items.length }}</span></span
      >
    </header>

    <div v-if="loading" class="video-state">正在加载阅读内容…</div>
    <div v-else-if="loadError" class="video-state">
      <p>{{ loadError }}</p>
      <AppButton type="button" @click="loadChapter">重试</AppButton>
    </div>
    <div v-else-if="items.length === 0" class="video-state">暂无可阅读内容</div>
    <template v-else>
      <section class="video-stage" aria-label="短视频阅读内容">
        <video
          v-if="currentIsVideo"
          :key="currentItem.id"
          ref="videoRef"
          class="video-media"
          :src="currentItem.hqUrl"
          :muted="muted"
          playsinline
          webkit-playsinline
          preload="auto"
          loop
          @click="togglePlayback"
          @timeupdate="onTimeUpdate"
          @play="isPlaying = true"
          @pause="isPlaying = false"
          @error="onVideoError"
        />
        <img
          v-else
          :key="`${currentItem.id}-${imageReloadKey}`"
          class="video-media media-image"
          :src="imageUrl(currentItem)"
          :alt="`${chapterTitle} 第 ${currentItem.pageNumber} 页`"
          draggable="false"
          @error="onImageError"
        />
        <div class="video-shade" aria-hidden="true" />
        <div v-if="mediaError" class="video-play-error">
          <span>{{ mediaError }}</span>
          <AppButton type="button" @click="retryPlayback">重试</AppButton>
        </div>
        <AppButton
          v-else-if="currentIsVideo && !isPlaying"
          class="video-play-button"
          type="button"
          aria-label="播放视频"
          @click="togglePlayback"
          >▶</AppButton
        >
        <div class="video-meta">
          <strong>{{ chapterTitle || '本章视频' }}</strong>
          <span>第 {{ currentItem.pageNumber }} 页</span>
        </div>
        <AppButton
          v-if="currentIsVideo"
          class="video-sound"
          type="button"
          :aria-label="muted ? '开启声音' : '静音'"
          @click="toggleMute"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="1.8"
            aria-hidden="true"
          >
            <path d="M11 5 6.5 9H3v6h3.5l4.5 4V5Z" />
            <path v-if="muted" d="m16 9 5 6m0-6-5 6" />
            <path v-else d="M15 9a4 4 0 0 1 0 6m3-9a8 8 0 0 1 0 12" />
          </svg>
        </AppButton>
        <div
          v-if="currentIsVideo"
          class="video-progress"
          role="progressbar"
          :aria-valuenow="Math.round(progress * 100)"
          aria-valuemin="0"
          aria-valuemax="100"
          aria-label="播放进度"
        >
          <span :style="{ width: `${progress * 100}%` }" />
        </div>
      </section>
      <div class="video-nav">
        <AppButton type="button" aria-label="上一项" :disabled="!hasPrevious" @click="move(-1)">↑</AppButton>
        <AppButton type="button" aria-label="下一项" :disabled="!hasNext" @click="move(1)">↓</AppButton>
      </div>
      <video
        v-if="nextItem && isVideoMedia(nextItem)"
        :key="nextItem.id"
        class="video-preload"
        :src="nextItem.hqUrl"
        muted
        playsinline
        preload="metadata"
        aria-hidden="true"
      />
      <img v-else-if="nextItem" class="video-preload" :src="imageUrl(nextItem)" alt="" aria-hidden="true" />
      <div v-if="edgeError" class="video-edge-error" role="alert">{{ edgeError }}</div>
    </template>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AppButton } from '@/shared/ui/button'
import { getApiErrorMessage } from '@/shared/api/http'
import { readerApi, type ReaderDTO } from '@/entities/chapter'
import { historyApi } from '@/entities/history'
import { isVideoMedia, type MediaItemInfo } from '@/entities/media'
import { clientLogger } from '@/shared/lib/logger'

const route = useRoute()
const router = useRouter()
const chapter = shallowRef<ReaderDTO | null>(null)
const nextChapter = shallowRef<ReaderDTO | null>(null)
const previousChapter = shallowRef<ReaderDTO | null>(null)
const currentIndex = ref(0)
const videoRef = ref<HTMLVideoElement | null>(null)
const loading = ref(true)
const loadError = ref('')
const mediaError = ref('')
const edgeError = ref('')
const isPlaying = ref(false)
const muted = ref(true)
const progress = ref(0)
const imageReloadKey = ref(0)
const chapterTitle = computed(() => chapter.value?.chapterTitle ?? '')
const items = computed(() => playableItems(chapter.value?.pages ?? []))
const currentItem = computed(() => items.value[currentIndex.value]!)
const currentIsVideo = computed(() => currentItem.value != null && isVideoMedia(currentItem.value))
const nextItem = computed(() => items.value[currentIndex.value + 1] ?? playableItems(nextChapter.value?.pages ?? [])[0])
const hasPrevious = computed(() => currentIndex.value > 0 || chapter.value?.prevChapterId != null)
const hasNext = computed(() => currentIndex.value < items.value.length - 1 || chapter.value?.nextChapterId != null)
const MAX_CACHED_CHAPTERS = 8
const chapterRequests = new Map<number, Promise<ReaderDTO>>()
let loadSequence = 0
let playbackSequence = 0
let switchingChapter = false
let touchStartY = 0
let touchStartX = 0
let lastWheelTime = 0
let previousOverflow = ''
let progressTimer: number | null = null
let errorTimer: number | null = null
let pendingProgress: { comicId: number; chapterId: number; pageNumber: number } | null = null
let progressSavePromise: Promise<void> | null = null

function imageUrl(page: MediaItemInfo): string {
  if ((!page.hqStatus || page.hqStatus === 'READY') && page.hqUrl) return page.hqUrl
  return page.lqUrl || ''
}

function playableItems(pages: MediaItemInfo[]): MediaItemInfo[] {
  return pages.filter((page) =>
    isVideoMedia(page) ? !!page.hqUrl && (!page.hqStatus || page.hqStatus === 'READY') : !!imageUrl(page),
  )
}

function requestChapter(chapterId: number): Promise<ReaderDTO> {
  const existing = chapterRequests.get(chapterId)
  if (existing) {
    chapterRequests.delete(chapterId)
    chapterRequests.set(chapterId, existing)
    return existing
  }
  const request = readerApi
    .chapter(chapterId)
    .then((response) => response.data)
    .catch((error: unknown) => {
      if (chapterRequests.get(chapterId) === request) chapterRequests.delete(chapterId)
      throw error
    })
  chapterRequests.set(chapterId, request)
  while (chapterRequests.size > MAX_CACHED_CHAPTERS) {
    const oldestChapterId = chapterRequests.keys().next().value
    if (oldestChapterId == null) break
    chapterRequests.delete(oldestChapterId)
  }
  return request
}

/** 空章节继续沿阅读顺序查找，避免在章节边界停留于空画面。 */
async function findPlayableChapter(
  chapterId: number | null,
  direction: 'next' | 'previous',
  comicId: number,
): Promise<ReaderDTO | null> {
  const visited = new Set<number>()
  let candidateId = chapterId
  while (candidateId != null && !visited.has(candidateId)) {
    visited.add(candidateId)
    const candidate = await requestChapter(candidateId)
    if (candidate.comicId !== comicId) return null
    if (playableItems(candidate.pages).length > 0) return candidate
    candidateId = direction === 'next' ? candidate.nextChapterId : candidate.prevChapterId
  }
  return null
}

function prefetchAdjacent(): void {
  const activeChapter = chapter.value
  if (!activeChapter) return
  nextChapter.value = null
  previousChapter.value = null
  const chapterId = activeChapter.chapterId
  if (activeChapter.nextChapterId != null) {
    void findPlayableChapter(activeChapter.nextChapterId, 'next', activeChapter.comicId)
      .then((candidate) => {
        if (chapter.value?.chapterId === chapterId) nextChapter.value = candidate
      })
      .catch(() => {
        // 正式滑动到章节边界时会重试，并在失败时显示提示。
      })
  }
  if (activeChapter.prevChapterId != null) {
    void findPlayableChapter(activeChapter.prevChapterId, 'previous', activeChapter.comicId)
      .then((candidate) => {
        if (chapter.value?.chapterId === chapterId) previousChapter.value = candidate
      })
      .catch(() => {
        // 上一章预取失败不影响当前阅读。
      })
  }
}

async function loadChapter(): Promise<void> {
  const chapterId = Number(route.params.chapterId)
  if (!Number.isSafeInteger(chapterId) || chapterId <= 0) {
    loading.value = false
    loadError.value = '章节地址无效'
    return
  }
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    let response = await requestChapter(chapterId)
    if (playableItems(response.pages).length === 0) {
      response = (await findPlayableChapter(response.nextChapterId, 'next', response.comicId)) ?? response
    }
    if (sequence !== loadSequence) return
    chapter.value = response
    const requestedPage = response.chapterId === chapterId ? Number(route.query.page) : 1
    const matchingIndex = playableItems(response.pages).findIndex((page) => page.pageNumber >= requestedPage)
    currentIndex.value =
      !Number.isFinite(requestedPage) || requestedPage <= 0
        ? 0
        : matchingIndex >= 0
          ? matchingIndex
          : Math.max(0, playableItems(response.pages).length - 1)
    loading.value = false
    prefetchAdjacent()
    await nextTick()
    if (sequence === loadSequence) {
      void playCurrent()
      scheduleProgressSave()
      if (response.chapterId !== chapterId) updateRoute()
    }
  } catch (error: unknown) {
    if (sequence === loadSequence) loadError.value = getApiErrorMessage(error, '加载阅读内容失败')
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function updateRoute(): void {
  if (!chapter.value || !currentItem.value) return
  void router.replace({
    name: 'chapter-videos',
    params: { chapterId: chapter.value.chapterId },
    query: { page: currentItem.value.pageNumber },
  })
}

async function playCurrent(): Promise<void> {
  if (!currentIsVideo.value) return
  const video = videoRef.value
  if (!video) return
  mediaError.value = ''
  try {
    await video.play()
  } catch (error: unknown) {
    if (video !== videoRef.value) return
    // 浏览器禁止自动播放时保留手动入口。
    if (error instanceof DOMException && error.name === 'NotAllowedError') return
    mediaError.value = '视频无法播放，请重试'
  }
}

function stopCurrent(): void {
  const video = videoRef.value
  if (!video) return
  video.pause()
  video.removeAttribute('src')
  video.load()
}

function showEdgeError(message: string): void {
  edgeError.value = message
  if (errorTimer != null) window.clearTimeout(errorTimer)
  errorTimer = window.setTimeout(() => {
    edgeError.value = ''
  }, 2500)
}

async function move(direction: number): Promise<void> {
  if (switchingChapter || !chapter.value) return
  const nextIndex = currentIndex.value + direction
  if (nextIndex >= 0 && nextIndex < items.value.length) {
    const sequence = ++playbackSequence
    stopCurrent()
    currentIndex.value = nextIndex
    resetMediaState()
    updateRoute()
    scheduleProgressSave()
    await nextTick()
    if (sequence === playbackSequence) void playCurrent()
    return
  }

  const activeChapter = chapter.value
  const adjacentId = direction > 0 ? activeChapter.nextChapterId : activeChapter.prevChapterId
  if (adjacentId == null) return
  switchingChapter = true
  try {
    const prefetched = direction > 0 ? nextChapter.value : previousChapter.value
    const target =
      prefetched ?? (await findPlayableChapter(adjacentId, direction > 0 ? 'next' : 'previous', activeChapter.comicId))
    if (!target || chapter.value?.chapterId !== activeChapter.chapterId) return
    ++playbackSequence
    stopCurrent()
    chapter.value = target
    currentIndex.value = direction > 0 ? 0 : playableItems(target.pages).length - 1
    resetMediaState()
    prefetchAdjacent()
    updateRoute()
    scheduleProgressSave()
    await nextTick()
    void playCurrent()
  } catch (error: unknown) {
    clientLogger.error('相邻章节加载失败', {
      operation: 'immersive.chapter',
      chapterId: adjacentId,
      reason: error instanceof Error ? error.name : 'unknown',
    })
    showEdgeError('章节加载失败，请再滑动重试')
  } finally {
    switchingChapter = false
  }
}

function resetMediaState(): void {
  isPlaying.value = false
  mediaError.value = ''
  progress.value = 0
  imageReloadKey.value = 0
}

function togglePlayback(): void {
  if (!currentIsVideo.value) return
  const video = videoRef.value
  if (!video) return
  if (video.paused) void playCurrent()
  else video.pause()
}

function toggleMute(): void {
  muted.value = !muted.value
  if (videoRef.value) videoRef.value.muted = muted.value
}

function retryPlayback(): void {
  mediaError.value = ''
  if (!currentIsVideo.value) {
    imageReloadKey.value++
    return
  }
  const video = videoRef.value
  if (!video) return
  video.load()
  void playCurrent()
}

function onTimeUpdate(): void {
  const video = videoRef.value
  progress.value =
    video && Number.isFinite(video.duration) && video.duration > 0 ? video.currentTime / video.duration : 0
}

function onVideoError(event: Event): void {
  if (event.currentTarget === videoRef.value) mediaError.value = '视频无法播放，请重试'
}

function onImageError(): void {
  mediaError.value = '图片无法显示，请重试'
}

function onTouchStart(event: TouchEvent): void {
  touchStartY = event.changedTouches[0]?.clientY ?? 0
  touchStartX = event.changedTouches[0]?.clientX ?? 0
}

function onTouchEnd(event: TouchEvent): void {
  const endY = event.changedTouches[0]?.clientY ?? touchStartY
  const endX = event.changedTouches[0]?.clientX ?? touchStartX
  const distance = touchStartY - endY
  if (Math.abs(distance) >= 60 && Math.abs(distance) > Math.abs(touchStartX - endX)) {
    void move(distance > 0 ? 1 : -1)
  }
}

function onWheel(event: WheelEvent): void {
  if (Math.abs(event.deltaY) < 12 || Date.now() - lastWheelTime < 450) return
  lastWheelTime = Date.now()
  void move(event.deltaY > 0 ? 1 : -1)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    void move(1)
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    void move(-1)
  }
  if (event.code === 'Space' && currentIsVideo.value) {
    event.preventDefault()
    togglePlayback()
  }
}

function currentProgress(): { comicId: number; chapterId: number; pageNumber: number } | null {
  if (!chapter.value || !currentItem.value) return null
  return {
    comicId: chapter.value.comicId,
    chapterId: chapter.value.chapterId,
    pageNumber: currentItem.value.pageNumber,
  }
}

async function flushProgress(): Promise<void> {
  while (pendingProgress) {
    const progressToSave = pendingProgress
    pendingProgress = null
    try {
      await historyApi.update(progressToSave.comicId, {
        chapterId: progressToSave.chapterId,
        pageNumber: progressToSave.pageNumber,
      })
    } catch (error: unknown) {
      clientLogger.error('沉浸阅读进度保存失败', {
        operation: 'immersive.history',
        chapterId: progressToSave.chapterId,
        reason: error instanceof Error ? error.name : 'unknown',
      })
    }
  }
  progressSavePromise = null
}

function queueProgressSave(): void {
  pendingProgress = currentProgress()
  if (pendingProgress && !progressSavePromise) progressSavePromise = flushProgress()
}

function scheduleProgressSave(): void {
  if (progressTimer != null) window.clearTimeout(progressTimer)
  progressTimer = window.setTimeout(queueProgressSave, 350)
}

function goBack(): void {
  if (chapter.value) {
    void router.push({ name: 'comic-detail', params: { id: chapter.value.comicId } })
  } else {
    router.back()
  }
}

watch(
  () => route.params.chapterId,
  (chapterId) => {
    if (Number(chapterId) !== chapter.value?.chapterId) {
      stopCurrent()
      void loadChapter()
    }
  },
)

onMounted(() => {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  document.addEventListener('keydown', onKeydown)
  void loadChapter()
})

onBeforeUnmount(() => {
  ++loadSequence
  ++playbackSequence
  if (progressTimer != null) window.clearTimeout(progressTimer)
  if (errorTimer != null) window.clearTimeout(errorTimer)
  queueProgressSave()
  stopCurrent()
  document.body.style.overflow = previousOverflow
  document.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.short-video-page {
  position: fixed;
  inset: 0;
  z-index: 100;
  overflow: hidden;
  background: #000;
  color: #fff;
  touch-action: pan-x;
}

.video-header {
  position: absolute;
  z-index: 2;
  top: 0;
  right: 0;
  left: 0;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 48px;
  align-items: center;
  min-height: 56px;
  padding: calc(env(safe-area-inset-top) + 12px) 18px 22px;
  background: linear-gradient(#0009, transparent);
}

.video-back {
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #fff;
  font-size: 25px;
}

.video-count {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.video-count span {
  color: #fffa;
}

.video-stage {
  position: relative;
  width: min(100%, 520px);
  height: 100%;
  margin: 0 auto;
  background: #000;
}

.video-media {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  cursor: pointer;
}

.media-image {
  cursor: default;
  user-select: none;
}

.video-shade {
  position: absolute;
  inset: 62% 0 0;
  background: linear-gradient(transparent, #000b);
  pointer-events: none;
}

.video-meta {
  position: absolute;
  right: 86px;
  bottom: calc(env(safe-area-inset-bottom) + 30px);
  left: 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  text-shadow: 0 2px 12px #000b;
}

.video-meta strong {
  overflow: hidden;
  font-size: 18px;
  font-weight: 700;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.video-meta span {
  color: #fffd;
  font-size: 12px;
}

.video-sound {
  position: absolute;
  right: 18px;
  bottom: calc(env(safe-area-inset-bottom) + 27px);
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: #0007;
  color: #fff;
}

.video-sound svg {
  width: 23px;
  height: 23px;
}

.video-progress {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: #fff5;
}

.video-progress span {
  display: block;
  height: 100%;
  background: #fff;
  transition: width 0.15s linear;
}

.video-play-button {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 64px;
  height: 64px;
  border: 0;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  background: #0008;
  color: #fff;
  font-size: 27px;
}

.video-play-error {
  position: absolute;
  top: 50%;
  left: 50%;
  display: grid;
  justify-items: center;
  gap: 16px;
  width: 90%;
  transform: translate(-50%, -50%);
  text-align: center;
}

.video-nav {
  position: absolute;
  top: 50%;
  right: max(20px, calc((100vw - 610px) / 2));
  display: grid;
  gap: 8px;
  transform: translateY(-50%);
}

.video-nav :deep(button) {
  width: 40px;
  height: 40px;
  border: 1px solid #fff4;
  border-radius: 50%;
  background: #0006;
  color: #fff;
  font-size: 19px;
}

.video-nav :deep(button:disabled) {
  opacity: 0.35;
}

.video-state {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 24px;
  text-align: center;
}

.video-edge-error {
  position: absolute;
  z-index: 3;
  right: 24px;
  bottom: calc(env(safe-area-inset-bottom) + 115px);
  left: 24px;
  padding: 10px 14px;
  border-radius: 8px;
  background: #262626e6;
  color: #fff;
  font-size: 13px;
  text-align: center;
}

.video-preload {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

@media (max-width: 680px) {
  .video-stage {
    width: 100%;
  }
  .video-nav {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .video-progress span {
    transition: none;
  }
}
</style>
