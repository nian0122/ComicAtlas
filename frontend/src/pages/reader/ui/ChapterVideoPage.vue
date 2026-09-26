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
      <section
        class="video-stage"
        aria-label="短视频阅读内容"
        @pointerdown="onVideoPointerDown"
        @pointermove="onVideoPointerMove"
        @pointerup="onVideoPointerUp"
        @pointercancel="onVideoPointerUp"
        @mousedown="onVideoPointerDown"
        @mouseup="onVideoPointerUp"
        @touchstart="onVideoTouchStart"
        @touchmove="onVideoTouchMove"
        @touchend="onVideoTouchEnd"
      >
        <Transition :name="slideTransitionName" @after-enter="finishSlide">
          <div :key="`${currentItem.id}-${imageReloadKey}`" class="video-media-frame">
            <video
              v-if="currentIsVideo"
              :ref="setVideoRef"
              class="video-media"
              :src="currentItem.hqUrl"
              :muted="muted"
              playsinline
              webkit-playsinline
              preload="auto"
              loop
              draggable="false"
              @click="togglePlayback"
              @loadedmetadata="onLoadedMetadata"
              @timeupdate="onTimeUpdate"
              @play="isPlaying = true"
              @pause="isPlaying = false"
              @error="onVideoError"
            />
            <img
              v-else
              class="video-media media-image"
              :src="imageUrl(currentItem)"
              :alt="`${chapterTitle} 第 ${currentItem.pageNumber} 页`"
              draggable="false"
              @error="onImageError"
            />
          </div>
        </Transition>
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
        <div v-if="isSpeedSheetOpen" class="video-speed-sheet" @click.self="closeSpeedSheet">
          <section class="video-speed-panel" role="dialog" aria-modal="true" aria-label="播放速度">
            <div class="video-speed-handle" aria-hidden="true" />
            <h2>播放速度</h2>
            <div class="video-speed-options" role="listbox" aria-label="选择播放速度">
              <AppButton
                v-for="speed in playbackRates"
                :key="speed"
                class="video-speed-option"
                type="button"
                role="option"
                :aria-selected="playbackRate === speed"
                @click.stop="selectPlaybackRate(speed)"
              >
                {{ formatPlaybackRate(speed) }}×
              </AppButton>
            </div>
            <AppButton class="video-speed-close" type="button" @click="closeSpeedSheet">完成</AppButton>
          </section>
        </div>
        <div v-if="currentIsVideo" class="video-controls" :class="{ 'is-seeking': isSeeking }">
          <div class="video-time" aria-live="polite">
            {{ formatTime(isSeeking && seekPreviewTime != null ? seekPreviewTime : currentTime) }} /
            {{ formatTime(duration) }}
          </div>
          <div
            ref="progressBarRef"
            class="video-progress"
            :class="{ 'is-long-press': isLongPressSeeking }"
            role="slider"
            tabindex="0"
            :aria-valuenow="Math.round(isSeeking && seekPreviewTime != null ? seekPreviewTime : currentTime)"
            aria-valuemin="0"
            :aria-valuemax="duration"
            aria-label="播放进度，可拖动调整"
            @pointerdown.stop.prevent="onProgressPointerDown"
            @pointermove.stop.prevent="onProgressPointerMove"
            @pointerup.stop.prevent="onProgressPointerUp"
            @pointercancel.stop.prevent="onProgressPointerUp"
            @touchstart.stop.prevent="onProgressTouchStart"
            @touchmove.stop.prevent="onProgressTouchMove"
            @touchend.stop.prevent="onProgressTouchEnd"
            @keydown="onProgressKeydown"
          >
            <span class="video-progress-fill" :style="{ width: `${displayedProgress * 100}%` }" />
            <i class="video-progress-thumb" :style="{ left: `${displayedProgress * 100}%` }" aria-hidden="true" />
          </div>
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
const progressBarRef = ref<HTMLElement | null>(null)
const loading = ref(true)
const loadError = ref('')
const mediaError = ref('')
const edgeError = ref('')
const isPlaying = ref(false)
const muted = ref(true)
const progress = ref(0)
const currentTime = ref(0)
const duration = ref(0)
const seekPreviewTime = ref<number | null>(null)
const isSeeking = ref(false)
const isLongPressSeeking = ref(false)
const playbackRate = ref(1)
const isSpeedSheetOpen = ref(false)
const slideDirection = ref<'up' | 'down'>('up')
const isSliding = ref(false)
const imageReloadKey = ref(0)
const chapterTitle = computed(() => chapter.value?.chapterTitle ?? '')
const items = computed(() => playableItems(chapter.value?.pages ?? []))
const currentItem = computed(() => items.value[currentIndex.value]!)
const currentIsVideo = computed(() => currentItem.value != null && isVideoMedia(currentItem.value))
const nextItem = computed(() => items.value[currentIndex.value + 1] ?? playableItems(nextChapter.value?.pages ?? [])[0])
const displayedProgress = computed(() =>
  isSeeking.value && seekPreviewTime.value != null && duration.value > 0
    ? seekPreviewTime.value / duration.value
    : progress.value,
)
const slideTransitionName = computed(() => `media-slide-${slideDirection.value}`)
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
let seekPressTimer: number | null = null
let resumeAfterSeek = false
let seekPointerId: number | null = null
let speedPressTimer: number | null = null
let speedPressStartX = 0
let speedPressStartY = 0
let suppressVideoClick = false
let slideUnlockTimer: number | null = null
const playbackRates = [0.5, 1, 1.25, 1.5, 2] as const

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

function setVideoRef(element: unknown): void {
  if (element instanceof HTMLVideoElement) {
    videoRef.value = element
    return
  }
  if (videoRef.value && !videoRef.value.isConnected) videoRef.value = null
}

function beginSlide(direction: number): void {
  slideDirection.value = direction > 0 ? 'up' : 'down'
  isSliding.value = true
  if (slideUnlockTimer != null) window.clearTimeout(slideUnlockTimer)
  slideUnlockTimer = window.setTimeout(finishSlide, 500)
}

function finishSlide(): void {
  isSliding.value = false
  if (slideUnlockTimer != null) window.clearTimeout(slideUnlockTimer)
  slideUnlockTimer = null
}

async function playCurrent(): Promise<void> {
  if (!currentIsVideo.value) return
  const video = videoRef.value
  if (!video) return
  video.playbackRate = playbackRate.value
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
  video.playbackRate = playbackRate.value
  video.removeAttribute('src')
  video.load()
}

function pauseCurrent(): void {
  videoRef.value?.pause()
}

function showEdgeError(message: string): void {
  edgeError.value = message
  if (errorTimer != null) window.clearTimeout(errorTimer)
  errorTimer = window.setTimeout(() => {
    edgeError.value = ''
  }, 2500)
}

async function move(direction: number): Promise<void> {
  if (switchingChapter || isSliding.value || !chapter.value || isSpeedSheetOpen.value) return
  const nextIndex = currentIndex.value + direction
  if (nextIndex >= 0 && nextIndex < items.value.length) {
    const sequence = ++playbackSequence
    beginSlide(direction)
    pauseCurrent()
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
    beginSlide(direction)
    pauseCurrent()
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
  currentTime.value = 0
  duration.value = 0
  seekPreviewTime.value = null
  isSeeking.value = false
  isLongPressSeeking.value = false
  isSpeedSheetOpen.value = false
  clearSpeedPressTimer()
  imageReloadKey.value = 0
}

function togglePlayback(): void {
  if (!currentIsVideo.value) return
  if (suppressVideoClick) {
    suppressVideoClick = false
    return
  }
  const video = videoRef.value
  if (!video) return
  if (video.paused) void playCurrent()
  else video.pause()
}

function toggleMute(): void {
  muted.value = !muted.value
  if (videoRef.value) videoRef.value.muted = muted.value
}

function formatPlaybackRate(rate: number): string {
  return Number.isInteger(rate) ? String(rate) : rate.toFixed(2).replace(/0$/u, '')
}

function selectPlaybackRate(rate: (typeof playbackRates)[number]): void {
  playbackRate.value = rate
  isSpeedSheetOpen.value = false
  if (videoRef.value) videoRef.value.playbackRate = rate
}

function closeSpeedSheet(): void {
  isSpeedSheetOpen.value = false
}

function clearSpeedPressTimer(): void {
  if (speedPressTimer != null) window.clearTimeout(speedPressTimer)
  speedPressTimer = null
}

function openSpeedSheet(): void {
  if (!currentIsVideo.value || !videoRef.value) return
  isSpeedSheetOpen.value = true
  suppressVideoClick = true
}

function onVideoPointerDown(event: PointerEvent | MouseEvent): void {
  if (!currentIsVideo.value || event.button > 0) return
  if (event.target instanceof Element && event.target.closest('button, [role="slider"], .video-speed-sheet')) return
  speedPressStartX = event.clientX
  speedPressStartY = event.clientY
  clearSpeedPressTimer()
  speedPressTimer = window.setTimeout(openSpeedSheet, 420)
}

function onVideoTouchStart(event: TouchEvent): void {
  if (!currentIsVideo.value) return
  if (event.target instanceof Element && event.target.closest('button, [role="slider"], .video-speed-sheet')) return
  const touch = event.changedTouches[0]
  if (!touch) return
  speedPressStartX = touch.clientX
  speedPressStartY = touch.clientY
  clearSpeedPressTimer()
  speedPressTimer = window.setTimeout(openSpeedSheet, 420)
}

function onVideoTouchMove(event: TouchEvent): void {
  const touch = event.changedTouches[0]
  if (touch && Math.hypot(touch.clientX - speedPressStartX, touch.clientY - speedPressStartY) > 12) {
    clearSpeedPressTimer()
  }
}

function onVideoTouchEnd(): void {
  clearSpeedPressTimer()
}

function onVideoPointerMove(event: PointerEvent): void {
  if (Math.hypot(event.clientX - speedPressStartX, event.clientY - speedPressStartY) > 12) clearSpeedPressTimer()
}

function onVideoPointerUp(): void {
  clearSpeedPressTimer()
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
  if (!video) return
  if (Number.isFinite(video.duration) && video.duration > 0) duration.value = video.duration
  currentTime.value = video.currentTime
  progress.value = duration.value > 0 ? video.currentTime / duration.value : 0
}

function onLoadedMetadata(): void {
  const video = videoRef.value
  if (!video || !Number.isFinite(video.duration)) return
  duration.value = video.duration
  currentTime.value = video.currentTime
}

function formatTime(value: number): string {
  if (!Number.isFinite(value) || value < 0) return '00:00'
  const totalSeconds = Math.floor(value)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`
}

function seekFromClientX(clientX: number, commit: boolean): void {
  const video = videoRef.value
  const progressBar = progressBarRef.value
  if (!video || !progressBar || duration.value <= 0) return
  const bounds = progressBar.getBoundingClientRect()
  const ratio = Math.min(1, Math.max(0, (clientX - bounds.left) / bounds.width))
  const targetTime = ratio * duration.value
  seekPreviewTime.value = targetTime
  if (commit) {
    video.currentTime = targetTime
    currentTime.value = targetTime
    progress.value = ratio
    seekPreviewTime.value = null
  }
}

function beginSeeking(clientX: number, pointerId: number | null = null): void {
  if (!currentIsVideo.value || duration.value <= 0) return
  seekPointerId = pointerId
  resumeAfterSeek = isPlaying.value
  if (resumeAfterSeek) videoRef.value?.pause()
  isSeeking.value = true
  seekFromClientX(clientX, false)
  clearSpeedPressTimer()
  if (seekPressTimer != null) window.clearTimeout(seekPressTimer)
  seekPressTimer = window.setTimeout(() => {
    isLongPressSeeking.value = true
  }, 180)
}

function finishSeeking(clientX?: number): void {
  if (!isSeeking.value) return
  if (clientX != null) seekFromClientX(clientX, false)
  // 使用预览值提交，避免最后一次触摸事件落在轨道外时跳回起点。
  const video = videoRef.value
  const targetTime = seekPreviewTime.value
  if (video && targetTime != null) {
    video.currentTime = targetTime
    currentTime.value = targetTime
    progress.value = duration.value > 0 ? targetTime / duration.value : 0
  }
  seekPreviewTime.value = null
  isSeeking.value = false
  isLongPressSeeking.value = false
  seekPointerId = null
  if (seekPressTimer != null) window.clearTimeout(seekPressTimer)
  seekPressTimer = null
  if (resumeAfterSeek) void playCurrent()
  resumeAfterSeek = false
}

function onProgressPointerDown(event: PointerEvent): void {
  progressBarRef.value?.setPointerCapture?.(event.pointerId)
  beginSeeking(event.clientX, event.pointerId)
}

function onProgressPointerMove(event: PointerEvent): void {
  if (seekPointerId === event.pointerId && isSeeking.value) seekFromClientX(event.clientX, false)
}

function onProgressPointerUp(event: PointerEvent): void {
  if (seekPointerId === event.pointerId) finishSeeking(event.clientX)
}

function onProgressTouchStart(event: TouchEvent): void {
  if (seekPointerId != null) return
  const touch = event.changedTouches[0]
  if (touch) beginSeeking(touch.clientX)
}

function onProgressTouchMove(event: TouchEvent): void {
  const touch = event.changedTouches[0]
  if (touch && seekPointerId == null && isSeeking.value) seekFromClientX(touch.clientX, false)
}

function onProgressTouchEnd(event: TouchEvent): void {
  if (seekPointerId == null) finishSeeking(event.changedTouches[0]?.clientX)
}

function onProgressKeydown(event: KeyboardEvent): void {
  if (duration.value <= 0) return
  const step = event.shiftKey ? 10 : 5
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault()
    const direction = event.key === 'ArrowLeft' ? -1 : 1
    const video = videoRef.value
    if (!video) return
    video.currentTime = Math.min(duration.value, Math.max(0, video.currentTime + direction * step))
    onTimeUpdate()
  }
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
  if (seekPressTimer != null) window.clearTimeout(seekPressTimer)
  clearSpeedPressTimer()
  if (slideUnlockTimer != null) window.clearTimeout(slideUnlockTimer)
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
  overflow: hidden;
  background: #000;
}

.video-media-frame {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  background: #000;
  backface-visibility: hidden;
  will-change: transform;
}

.video-media {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  cursor: pointer;
}

.media-slide-up-enter-active,
.media-slide-up-leave-active,
.media-slide-down-enter-active,
.media-slide-down-leave-active {
  position: absolute;
  inset: 0;
  transition: transform 340ms cubic-bezier(0.22, 0.72, 0.24, 1);
}

.media-slide-up-enter-from,
.media-slide-down-leave-to {
  transform: translate3d(0, 100%, 0);
}

.media-slide-up-leave-to,
.media-slide-down-enter-from {
  transform: translate3d(0, -100%, 0);
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
  bottom: calc(env(safe-area-inset-bottom) + 82px);
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
  bottom: calc(env(safe-area-inset-bottom) + 76px);
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

.video-speed-sheet {
  position: absolute;
  inset: 0;
  z-index: 4;
  display: flex;
  align-items: flex-end;
  background: rgb(0 0 0 / 35%);
}

.video-speed-panel {
  width: 100%;
  padding: 14px 18px calc(env(safe-area-inset-bottom) + 16px);
  border-radius: 20px 20px 0 0;
  background: rgb(22 22 24 / 98%);
  box-shadow: 0 10px 30px rgb(0 0 0 / 35%);
  backdrop-filter: blur(12px);
}

.video-speed-handle {
  width: 36px;
  height: 4px;
  margin: 0 auto 12px;
  border-radius: 999px;
  background: rgb(255 255 255 / 32%);
}

.video-speed-panel h2 {
  margin: 0 0 14px;
  color: #fff;
  font-size: 16px;
  text-align: center;
}

.video-speed-options {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}

.video-speed-option {
  min-height: 42px;
  padding: 0 6px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #fffc;
  font-size: 12px;
}

.video-speed-option[aria-selected='true'] {
  background: #fff;
  color: #111;
}

.video-speed-close {
  width: 100%;
  min-height: 42px;
  margin-top: 12px;
  border: 0;
  border-radius: 10px;
  background: rgb(255 255 255 / 10%);
  color: #fff;
}

.video-controls {
  position: absolute;
  bottom: 0;
  right: 0;
  left: 0;
  z-index: 2;
  display: grid;
  gap: 0;
  padding: 0 18px calc(env(safe-area-inset-bottom) + 3px);
  background: linear-gradient(transparent, rgb(0 0 0 / 42%));
  transition: transform 160ms ease;
}

.video-controls.is-seeking {
  transform: translateY(-4px);
}

.video-time {
  color: #fffd;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  text-align: right;
  text-shadow: 0 1px 8px #000;
  opacity: 0;
  transition: opacity 160ms ease;
}

.video-controls.is-seeking .video-time {
  opacity: 1;
}

.video-progress {
  position: relative;
  width: 100%;
  height: 18px;
  cursor: pointer;
  touch-action: none;
}

.video-progress::before {
  position: absolute;
  bottom: 3px;
  right: 0;
  left: 0;
  height: 2px;
  border-radius: 999px;
  background: #fff5;
  content: '';
}

.video-progress-fill {
  position: absolute;
  bottom: 3px;
  left: 0;
  display: block;
  height: 2px;
  border-radius: 999px;
  background: #fff;
  transition:
    width 0.15s linear,
    height 160ms ease;
}

.video-progress-thumb {
  position: absolute;
  bottom: -1px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  transform: translateX(-50%) scale(0.75);
  background: #fff;
  box-shadow: 0 1px 8px #0008;
  opacity: 0;
  transition:
    transform 160ms ease,
    opacity 160ms ease,
    width 160ms ease,
    height 160ms ease;
}

.video-progress:hover::before,
.video-progress:focus-visible::before,
.video-progress.is-long-press::before,
.video-controls.is-seeking .video-progress::before {
  bottom: 2px;
  height: 4px;
  background: rgb(255 255 255 / 72%);
}

.video-progress:hover .video-progress-fill,
.video-progress:focus-visible .video-progress-fill,
.video-progress.is-long-press .video-progress-fill,
.video-controls.is-seeking .video-progress-fill {
  bottom: 2px;
  height: 4px;
}

.video-progress:hover .video-progress-thumb,
.video-progress:focus-visible .video-progress-thumb,
.video-progress.is-long-press .video-progress-thumb,
.video-controls.is-seeking .video-progress-thumb {
  bottom: 0;
  width: 12px;
  height: 12px;
  transform: translateX(-50%) scale(1);
  opacity: 1;
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
  .media-slide-up-enter-active,
  .media-slide-up-leave-active,
  .media-slide-down-enter-active,
  .media-slide-down-leave-active,
  .video-progress span {
    transition: none;
  }
}
</style>
