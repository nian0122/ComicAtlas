<template>
  <main
    class="short-video-page"
    :class="{ 'controls-hidden': !controlsVisible, 'is-fullscreen': isFullscreen }"
    @touchstart="onTouchStart"
    @touchmove.prevent="onTouchMove"
    @touchend="onTouchEnd"
    @touchcancel="cancelTouchGesture"
    @wheel.prevent="onWheel"
  >
    <header class="video-header">
      <AppButton class="video-back" type="button" aria-label="返回漫画阅读" @click="goBack">←</AppButton>
      <span v-if="items.length" class="video-count"
        >{{ currentIndex + 1 }} <span>/ {{ items.length }}</span></span
      >
      <FullscreenButton
        class="video-fullscreen"
        :active="isFullscreen"
        :pending="fullscreenPending"
        @toggle="toggleFullscreen"
      />
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
        <Transition :name="mediaTransitionName" :css="!skipMediaTransition" @after-enter="finishSlide">
          <div
            :key="`${currentItem.id}-${imageReloadKey}`"
            class="video-media-frame"
            :class="{ 'is-touch-dragging': isTouchDragging, 'is-touch-settling': isTouchSettling }"
            :style="currentFrameStyle"
          >
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
              @loadstart="onVideoLoadStart"
              @waiting="onVideoWaiting"
              @loadeddata="clearBufferingIndicator"
              @canplay="onVideoCanPlay"
              @canplaythrough="clearBufferingIndicator"
              @seeked="clearBufferingIndicator"
              @playing="onVideoPlaying"
              @play="onVideoPlay"
              @pause="onVideoPause"
              @ended="onVideoEnded"
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
        <div
          v-if="gesturePreviewItem"
          class="video-media-frame video-media-preview"
          :class="{ 'is-touch-dragging': isTouchDragging, 'is-touch-settling': isTouchSettling }"
          :style="previewFrameStyle"
          aria-hidden="true"
        >
          <video
            v-if="isVideoMedia(gesturePreviewItem)"
            class="video-media"
            :src="gesturePreviewItem.hqUrl"
            muted
            playsinline
            webkit-playsinline
            preload="none"
            draggable="false"
          />
          <img v-else class="video-media media-image" :src="imageUrl(gesturePreviewItem)" alt="" draggable="false" />
        </div>
        <div class="video-shade" aria-hidden="true" />
        <div
          v-if="currentIsVideo && isBuffering && !mediaError"
          class="video-buffering"
          role="status"
          aria-label="视频缓冲中"
        >
          <span aria-hidden="true" />
        </div>
        <div v-if="mediaError" class="video-play-error">
          <span>{{ mediaError }}</span>
          <AppButton type="button" @click="retryPlayback">重试</AppButton>
        </div>
        <AppButton
          v-else-if="currentIsVideo && !isPlaying"
          class="video-play-button"
          type="button"
          aria-label="播放视频"
          title="播放 / 暂停（空格或 K）"
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
          title="静音 / 开启声音（M）"
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
        <div class="video-reactions" aria-label="媒体偏好">
          <AppButton
            class="video-reaction"
            :class="{ 'is-active': currentReaction === 'LIKE' }"
            type="button"
            aria-label="喜欢"
            :disabled="reactionPending"
            @click="toggleReaction('LIKE')"
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
              <path
                class="reaction-heart"
                d="M12 20.2 4.7 13a4.8 4.8 0 0 1 6.8-6.8L12 6.7l.5-.5A4.8 4.8 0 0 1 19.3 13L12 20.2Z"
              />
            </svg>
          </AppButton>
          <AppButton
            class="video-reaction"
            :class="{ 'is-active is-dislike': currentReaction === 'DISLIKE' }"
            type="button"
            aria-label="不喜欢"
            :disabled="reactionPending"
            @click="toggleReaction('DISLIKE')"
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
              <path
                d="M7 14V4H4a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h3Zm0-10h8.7a2.4 2.4 0 0 1 2.3 1.8l1.6 6.1a1.7 1.7 0 0 1-1.6 2.1H14l.8 3.8a2.3 2.3 0 0 1-2.2 2.8L7 14V4Z"
              />
            </svg>
          </AppButton>
        </div>
        <VideoSpeedSheet
          v-if="isSpeedSheetOpen"
          :current-rate="playbackRate"
          :rates="playbackRates"
          @close="closeSpeedSheet"
          @select="selectPlaybackRate"
        />
        <VideoProgressControl
          v-if="currentIsVideo"
          ref="progressControlRef"
          :display-time="isSeeking && seekPreviewTime != null ? seekPreviewTime : currentTime"
          :duration="duration"
          :progress="displayedProgress"
          :is-seeking="isSeeking"
          :is-long-press="isLongPressSeeking"
          @pointer-down="onProgressPointerDown"
          @pointer-move="onProgressPointerMove"
          @pointer-up="onProgressPointerUp"
          @touch-start="onProgressTouchStart"
          @touch-move="onProgressTouchMove"
          @touch-end="onProgressTouchEnd"
          @keydown="onProgressKeydown"
        />
      </section>
      <div class="video-nav">
        <AppButton type="button" aria-label="上一项" title="上一项（↑）" :disabled="!hasPrevious" @click="move(-1)"
          >↑</AppButton
        >
        <AppButton type="button" aria-label="下一项" title="下一项（↓）" :disabled="!hasNext" @click="move(1)"
          >↓</AppButton
        >
      </div>
      <video
        v-if="nextItem && isVideoMedia(nextItem)"
        :key="nextItem.id"
        :ref="setPreloadVideoRef"
        class="video-preload"
        :src="nextItem.hqUrl"
        muted
        playsinline
        preload="metadata"
        fetchpriority="low"
        aria-hidden="true"
      />
      <img v-else-if="nextItem" class="video-preload" :src="imageUrl(nextItem)" alt="" aria-hidden="true" />
      <div v-if="edgeError" class="video-edge-error" role="alert">{{ edgeError }}</div>
      <div v-if="chapterCue" class="chapter-cue" aria-live="polite">{{ chapterCue }}</div>
    </template>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useReadingNavigation } from '@/features/reading-navigation'
import { AppButton, FullscreenButton } from '@/shared/ui/button'
import { getApiErrorMessage } from '@/shared/api/http'
import { readerApi, type ReaderDTO } from '@/entities/chapter'
import { catalogApi, type CatalogNode } from '@/entities/comic'
import { isVideoMedia, type MediaItemInfo, type MediaReaction } from '@/entities/media'
import { searchCatalogChapters } from '@/features/chapter-search'
import { clientLogger } from '@/shared/lib/logger'
import { useScreenWakeLock } from '@/shared/lib/device/useScreenWakeLock'
import { VideoProgressControl, VideoSpeedSheet } from './components'
import { useAutoHideControls } from './composables/useAutoHideControls'
import { useImmersiveSwipe } from './composables/useImmersiveSwipe'
import { useReaderFullscreen } from './composables/useReaderFullscreen'
import { useShortVideoKeyboard } from './composables/useShortVideoKeyboard'
import { useReadingProgressPersistence, type ReadingProgressPayload } from './composables/useReadingProgressPersistence'

const route = useRoute()
const router = useRouter()
const { isFullscreen, isPending: fullscreenPending, toggleFullscreen } = useReaderFullscreen()
const readingNavigation = useReadingNavigation()
const chapter = shallowRef<ReaderDTO | null>(null)
const catalogTreeCache = new Map<number, CatalogNode[]>()
const nextChapter = shallowRef<ReaderDTO | null>(null)
const previousChapter = shallowRef<ReaderDTO | null>(null)
const currentIndex = ref(0)
const videoRef = ref<HTMLVideoElement | null>(null)
const nextPreloadVideoRef = ref<HTMLVideoElement | null>(null)
const progressControlRef = ref<InstanceType<typeof VideoProgressControl> | null>(null)
const { setPlaybackActive } = useScreenWakeLock()
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
const isBuffering = ref(false)
const chapterCue = ref('')
const imageReloadKey = ref(0)
const reactionOverrides = ref<Record<number, MediaReaction>>({})
const reactionPending = ref(false)
const chapterTitle = computed(() => chapter.value?.chapterTitle ?? '')
const items = computed(() => playableItems(chapter.value?.pages ?? []))
const currentItem = computed(() => items.value[currentIndex.value]!)
const currentIsVideo = computed(() => currentItem.value != null && isVideoMedia(currentItem.value))
const currentReaction = computed<MediaReaction>(() => {
  const item = currentItem.value
  return item ? (reactionOverrides.value[item.id] ?? item.reaction ?? 'NONE') : 'NONE'
})
const nextItem = computed(() => items.value[currentIndex.value + 1] ?? playableItems(nextChapter.value?.pages ?? [])[0])
const previousItem = computed(
  () => items.value[currentIndex.value - 1] ?? playableItems(previousChapter.value?.pages ?? []).at(-1),
)
const displayedProgress = computed(() =>
  isSeeking.value && seekPreviewTime.value != null && duration.value > 0
    ? seekPreviewTime.value / duration.value
    : progress.value,
)
const mediaTransitionName = computed(() => (skipMediaTransition.value ? '' : `media-slide-${slideDirection.value}`))
const hasPrevious = computed(() => currentIndex.value > 0 || chapter.value?.prevChapterId != null)
const hasNext = computed(() => currentIndex.value < items.value.length - 1 || chapter.value?.nextChapterId != null)
const { controlsVisible, scheduleControlsHide, showControls, disposeControls } = useAutoHideControls(
  () => !isSeeking.value && !isSpeedSheetOpen.value && (!currentIsVideo.value || isPlaying.value),
)
const {
  isTouchDragging,
  isTouchSettling,
  skipMediaTransition,
  gesturePreviewItem,
  currentFrameStyle,
  previewFrameStyle,
  onTouchStart,
  onTouchMove,
  onTouchEnd,
  cancelTouchGesture,
  disposeSwipe,
} = useImmersiveSwipe<MediaItemInfo>({
  hasPrevious,
  hasNext,
  previousItem,
  nextItem,
  canStart: () => !isSliding.value && !switchingChapter,
  move: (direction) => move(direction, false),
  onTap: () => showControls(),
  cancelLongPress: clearSpeedPressTimer,
})
const MAX_CACHED_CHAPTERS = 8
const chapterRequests = new Map<number, Promise<ReaderDTO>>()
let loadSequence = 0
let playbackSequence = 0
let switchingChapter = false
let lastWheelTime = 0
let previousOverflow = ''
let errorTimer: number | null = null
let seekPressTimer: number | null = null
let resumeAfterSeek = false
let seekPointerId: number | null = null
let speedPressTimer: number | null = null
let speedPressStartX = 0
let speedPressStartY = 0
let suppressVideoClick = false
let slideUnlockTimer: number | null = null
let chapterCueTimer: number | null = null
let bufferingConfirmTimer: number | null = null
let autoPlayRetryTimer: number | null = null
let autoPlayToken = 0
const playbackRates = [0.5, 1, 1.25, 1.5, 2] as const
const BUFFERING_CONFIRM_DELAY = 160
const PLAYABLE_READY_STATE = 3
const AUTO_PLAY_RETRY_DELAYS = [120, 360] as const

function imageUrl(page: MediaItemInfo): string {
  if ((!page.hqStatus || page.hqStatus === 'READY') && page.hqUrl) return page.hqUrl
  return page.lqUrl || ''
}

async function toggleReaction(reaction: Exclude<MediaReaction, 'NONE'>): Promise<void> {
  const item = currentItem.value
  if (!item || reactionPending.value) return
  const nextReaction: MediaReaction = currentReaction.value === reaction ? 'NONE' : reaction
  reactionPending.value = true
  const previousReaction = currentReaction.value
  reactionOverrides.value = { ...reactionOverrides.value, [item.id]: nextReaction }
  try {
    const response = await readerApi.updateReaction(item.id, nextReaction)
    reactionOverrides.value = { ...reactionOverrides.value, [item.id]: response.data.reaction }
    showControls()
  } catch (error: unknown) {
    reactionOverrides.value = { ...reactionOverrides.value, [item.id]: previousReaction }
    edgeError.value = getApiErrorMessage(error, '保存媒体标记失败')
  } finally {
    reactionPending.value = false
  }
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
async function getScopedSearchChapterIds(comicId: number): Promise<number[] | undefined> {
  const searchKeyword = route.query.search
  if (typeof searchKeyword !== 'string' || !searchKeyword.trim()) return undefined
  try {
    let catalogTree = catalogTreeCache.get(comicId)
    if (!catalogTree) {
      const response = await catalogApi.tree(comicId)
      catalogTree = [...response.data]
      catalogTreeCache.set(comicId, catalogTree)
    }
    return searchCatalogChapters(catalogTree, searchKeyword).map((item) => item.chapter.id)
  } catch {
    return undefined
  }
}

async function findPlayableChapter(
  chapterId: number | null,
  direction: 'next' | 'previous',
  comicId: number,
  scopedChapterIds?: readonly number[],
): Promise<ReaderDTO | null> {
  if (scopedChapterIds) {
    const startIndex = scopedChapterIds.indexOf(chapterId ?? -1)
    if (startIndex < 0) return null
    for (
      let candidateIndex = startIndex;
      candidateIndex >= 0 && candidateIndex < scopedChapterIds.length;
      candidateIndex += direction === 'next' ? 1 : -1
    ) {
      const scopedChapterId = scopedChapterIds[candidateIndex]
      if (scopedChapterId == null) continue
      const candidate = await requestChapter(scopedChapterId)
      if (candidate.comicId !== comicId) return null
      if (playableItems(candidate.pages).length > 0) {
        return {
          ...candidate,
          prevChapterId: scopedChapterIds[candidateIndex - 1] ?? null,
          nextChapterId: scopedChapterIds[candidateIndex + 1] ?? null,
        }
      }
    }
    return null
  }

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
    void getScopedSearchChapterIds(activeChapter.comicId)
      .then((chapterIds) => findPlayableChapter(activeChapter.nextChapterId, 'next', activeChapter.comicId, chapterIds))
      .then((candidate) => {
        if (chapter.value?.chapterId === chapterId) nextChapter.value = candidate
      })
      .catch(() => {
        // 正式滑动到章节边界时会重试，并在失败时显示提示。
      })
  }
  if (activeChapter.prevChapterId != null) {
    void getScopedSearchChapterIds(activeChapter.comicId)
      .then((chapterIds) =>
        findPlayableChapter(activeChapter.prevChapterId, 'previous', activeChapter.comicId, chapterIds),
      )
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
    let scopedChapterIds = await getScopedSearchChapterIds(response.comicId)
    if (scopedChapterIds) {
      const currentSearchIndex = scopedChapterIds.indexOf(response.chapterId)
      if (currentSearchIndex >= 0) {
        response = {
          ...response,
          prevChapterId: scopedChapterIds[currentSearchIndex - 1] ?? null,
          nextChapterId: scopedChapterIds[currentSearchIndex + 1] ?? null,
        }
      } else {
        scopedChapterIds = undefined
      }
    }
    if (playableItems(response.pages).length === 0) {
      response =
        (await findPlayableChapter(response.nextChapterId, 'next', response.comicId, scopedChapterIds)) ?? response
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
      scheduleControlsHide()
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
    query: { ...route.query, page: currentItem.value.pageNumber },
  })
}

function setVideoRef(element: unknown): void {
  if (element instanceof HTMLVideoElement) {
    videoRef.value = element
    return
  }
  if (videoRef.value && !videoRef.value.isConnected) videoRef.value = null
}

/** 预热视频只读取元数据；节点移除时释放连接，避免章节/下一项切换后遗留下载。 */
function setPreloadVideoRef(element: unknown): void {
  if (element instanceof HTMLVideoElement) {
    nextPreloadVideoRef.value = element
    return
  }

  const video = nextPreloadVideoRef.value
  if (!video) return
  video.pause()
  video.removeAttribute('src')
  video.load()
  nextPreloadVideoRef.value = null
}

function beginSlide(direction: number): void {
  slideDirection.value = direction > 0 ? 'up' : 'down'
  isSliding.value = true
  if (slideUnlockTimer != null) window.clearTimeout(slideUnlockTimer)
  slideUnlockTimer = window.setTimeout(finishSlide, 500)
}

function showChapterCue(title: string): void {
  chapterCue.value = title ? `已进入 ${title}` : '已进入下一章'
  if (chapterCueTimer != null) window.clearTimeout(chapterCueTimer)
  chapterCueTimer = window.setTimeout(() => {
    chapterCue.value = ''
  }, 1400)
}

function finishSlide(): void {
  isSliding.value = false
  if (slideUnlockTimer != null) window.clearTimeout(slideUnlockTimer)
  slideUnlockTimer = null
}

function clearAutoPlayRetry(): void {
  if (autoPlayRetryTimer != null) window.clearTimeout(autoPlayRetryTimer)
  autoPlayRetryTimer = null
}

function scheduleAutoPlayRetry(video: HTMLVideoElement, token: number, attempt: number): void {
  if (attempt >= AUTO_PLAY_RETRY_DELAYS.length) return
  clearAutoPlayRetry()
  autoPlayRetryTimer = window.setTimeout(() => {
    autoPlayRetryTimer = null
    if (token !== autoPlayToken || video !== videoRef.value || !video.paused) return
    void playCurrent(token, attempt + 1)
  }, AUTO_PLAY_RETRY_DELAYS[attempt])
}

async function playCurrent(requestToken = ++autoPlayToken, attempt = 0): Promise<void> {
  if (!currentIsVideo.value) return
  const video = videoRef.value
  if (!video) return
  clearAutoPlayRetry()
  video.muted = muted.value
  video.playbackRate = playbackRate.value
  mediaError.value = ''
  try {
    await video.play()
  } catch (error: unknown) {
    if (requestToken !== autoPlayToken || video !== videoRef.value) return
    // 浏览器禁止自动播放时保留手动入口。
    if (error instanceof DOMException && error.name === 'NotAllowedError') return
    scheduleAutoPlayRetry(video, requestToken, attempt)
    if (attempt >= AUTO_PLAY_RETRY_DELAYS.length - 1) mediaError.value = '视频无法播放，请重试'
    return
  }
  if (requestToken === autoPlayToken && video === videoRef.value && video.paused) {
    scheduleAutoPlayRetry(video, requestToken, attempt)
  }
}

function stopCurrent(): void {
  setPlaybackActive(false)
  ++autoPlayToken
  clearAutoPlayRetry()
  const video = videoRef.value
  if (!video) return
  video.pause()
  video.playbackRate = playbackRate.value
  video.removeAttribute('src')
  video.load()
}

function pauseCurrent(): void {
  setPlaybackActive(false)
  ++autoPlayToken
  clearAutoPlayRetry()
  videoRef.value?.pause()
}

function showEdgeError(message: string): void {
  edgeError.value = message
  if (errorTimer != null) window.clearTimeout(errorTimer)
  errorTimer = window.setTimeout(() => {
    edgeError.value = ''
  }, 2500)
}

async function move(direction: number, animate = true): Promise<void> {
  if (switchingChapter || isSliding.value || !chapter.value || isSpeedSheetOpen.value) return
  const nextIndex = currentIndex.value + direction
  if (nextIndex >= 0 && nextIndex < items.value.length) {
    const sequence = ++playbackSequence
    if (animate) beginSlide(direction)
    pauseCurrent()
    currentIndex.value = nextIndex
    resetMediaState()
    updateRoute()
    scheduleProgressSave()
    await nextTick()
    if (sequence === playbackSequence) {
      void playCurrent()
      scheduleControlsHide()
    }
    return
  }

  const activeChapter = chapter.value
  const adjacentId = direction > 0 ? activeChapter.nextChapterId : activeChapter.prevChapterId
  if (adjacentId == null) return
  switchingChapter = true
  try {
    const prefetched = direction > 0 ? nextChapter.value : previousChapter.value
    const target =
      prefetched ??
      (await findPlayableChapter(
        adjacentId,
        direction > 0 ? 'next' : 'previous',
        activeChapter.comicId,
        await getScopedSearchChapterIds(activeChapter.comicId),
      ))
    if (!target || chapter.value?.chapterId !== activeChapter.chapterId) return
    ++playbackSequence
    if (animate) beginSlide(direction)
    pauseCurrent()
    chapter.value = target
    currentIndex.value = direction > 0 ? 0 : playableItems(target.pages).length - 1
    resetMediaState()
    prefetchAdjacent()
    updateRoute()
    scheduleProgressSave()
    await nextTick()
    void playCurrent()
    scheduleControlsHide()
    showChapterCue(target.chapterTitle)
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
  clearBufferingIndicator()
  isBuffering.value = currentIsVideo.value
  clearSpeedPressTimer()
  imageReloadKey.value = 0
}

function togglePlayback(event?: Event): void {
  if (!currentIsVideo.value) return
  if (suppressVideoClick) {
    suppressVideoClick = false
    if (event) return
  }
  showControls(false)
  const video = videoRef.value
  if (!video) return
  if (video.paused) void playCurrent()
  else {
    ++autoPlayToken
    clearAutoPlayRetry()
    video.pause()
  }
}

function toggleMute(): void {
  showControls()
  muted.value = !muted.value
  if (videoRef.value) videoRef.value.muted = muted.value
}

function selectPlaybackRate(rate: number): void {
  playbackRate.value = rate
  isSpeedSheetOpen.value = false
  if (videoRef.value) videoRef.value.playbackRate = rate
  scheduleControlsHide()
}

function closeSpeedSheet(): void {
  isSpeedSheetOpen.value = false
  scheduleControlsHide()
}

function clearSpeedPressTimer(): void {
  if (speedPressTimer != null) window.clearTimeout(speedPressTimer)
  speedPressTimer = null
}

function openSpeedSheet(): void {
  if (!currentIsVideo.value || !videoRef.value) return
  isSpeedSheetOpen.value = true
  showControls(false)
  suppressVideoClick = true
}

function onVideoPointerDown(event: PointerEvent | MouseEvent): void {
  if (!currentIsVideo.value || event.button > 0) return
  if (event.target instanceof Element && event.target.closest('button, [role="slider"], .video-speed-sheet')) return
  if (!(event instanceof PointerEvent) || event.pointerType !== 'touch') showControls()
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
  clearBufferingIndicator()
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

function seekFromClientX(clientX: number, commit: boolean): void {
  const video = videoRef.value
  const progressBar = progressControlRef.value?.getElement()
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
  progressControlRef.value?.getElement()?.setPointerCapture?.(event.pointerId)
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
  if (isSpeedSheetOpen.value) return
  if (event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return
  if (duration.value <= 0) return
  const step = event.shiftKey ? 10 : 5
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault()
    event.stopPropagation()
    const direction = event.key === 'ArrowLeft' ? -1 : 1
    const video = videoRef.value
    if (!video) return
    video.currentTime = Math.min(duration.value, Math.max(0, video.currentTime + direction * step))
    onTimeUpdate()
  }
}

function onVideoError(event: Event): void {
  if (event.currentTarget === videoRef.value) {
    clearBufferingIndicator()
    mediaError.value = '视频无法播放，请重试'
    showControls(false)
  }
}

function onImageError(): void {
  mediaError.value = '图片无法显示，请重试'
}

function clearBufferingIndicator(): void {
  if (bufferingConfirmTimer != null) window.clearTimeout(bufferingConfirmTimer)
  bufferingConfirmTimer = null
  isBuffering.value = false
}

function onVideoLoadStart(event: Event): void {
  if (event.currentTarget !== videoRef.value) return
  clearBufferingIndicator()
  isBuffering.value = true
}

function onVideoWaiting(event: Event): void {
  const waitingVideo = event.currentTarget
  if (!(waitingVideo instanceof HTMLVideoElement) || waitingVideo !== videoRef.value) return
  if (bufferingConfirmTimer != null) window.clearTimeout(bufferingConfirmTimer)
  bufferingConfirmTimer = window.setTimeout(() => {
    bufferingConfirmTimer = null
    if (waitingVideo === videoRef.value && !waitingVideo.paused && waitingVideo.readyState < PLAYABLE_READY_STATE) {
      isBuffering.value = true
    }
  }, BUFFERING_CONFIRM_DELAY)
}

function onVideoCanPlay(): void {
  clearBufferingIndicator()
}

function onVideoPlay(event: Event): void {
  if (event.currentTarget !== videoRef.value) return
  setPlaybackActive(true)
  isPlaying.value = true
  scheduleControlsHide()
}

function onVideoPlaying(event: Event): void {
  if (event.currentTarget !== videoRef.value) return
  setPlaybackActive(true)
  isPlaying.value = true
  clearBufferingIndicator()
  scheduleControlsHide()
}

function onVideoPause(event: Event): void {
  if (event.currentTarget !== videoRef.value) return
  setPlaybackActive(false)
  isPlaying.value = false
  clearBufferingIndicator()
  showControls(false)
}

function onVideoEnded(event: Event): void {
  if (event.currentTarget !== videoRef.value || !currentIsVideo.value) return
  setPlaybackActive(false)
  void playCurrent()
}

function onWheel(event: WheelEvent): void {
  if (Math.abs(event.deltaY) < 12 || Date.now() - lastWheelTime < 450) return
  lastWheelTime = Date.now()
  void move(event.deltaY > 0 ? 1 : -1)
}

useShortVideoKeyboard({
  isVideo: () => currentIsVideo.value,
  isDialogOpen: () => isSpeedSheetOpen.value,
  move: (direction) => {
    void move(direction)
  },
  togglePlayback: () => togglePlayback(),
  seek: (seconds) => {
    const video = videoRef.value
    if (!video || duration.value <= 0 || isSeeking.value) return
    video.currentTime = Math.min(duration.value, Math.max(0, video.currentTime + seconds))
    onTimeUpdate()
  },
  toggleMute,
  toggleFullscreen: () => {
    void toggleFullscreen()
  },
  closeDialog: closeSpeedSheet,
  showControls,
})

function currentProgress(): ReadingProgressPayload | null {
  if (!chapter.value || !currentItem.value) return null
  return {
    comicId: chapter.value.comicId,
    chapterId: chapter.value.chapterId,
    pageNumber: currentItem.value.pageNumber,
    totalPages: chapter.value.pages.length,
  }
}

const progressPersistence = useReadingProgressPersistence(currentProgress, 350, {
  onError: (progressToSave, error) => {
    clientLogger.error('沉浸阅读进度保存失败', {
      operation: 'immersive.history',
      comicId: progressToSave.comicId,
      chapterId: progressToSave.chapterId,
      reason: error instanceof Error ? error.name : 'unknown',
    })
  },
})

function scheduleProgressSave(): void {
  progressPersistence.scheduleSave()
}

function goBack(): void {
  if (chapter.value && currentItem.value) {
    readingNavigation.goToReader(chapter.value.chapterId, { ...route.query, page: currentItem.value.pageNumber })
  } else {
    readingNavigation.goToSource()
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
  void loadChapter()
})

onBeforeUnmount(() => {
  ++loadSequence
  ++playbackSequence
  if (errorTimer != null) window.clearTimeout(errorTimer)
  if (seekPressTimer != null) window.clearTimeout(seekPressTimer)
  clearSpeedPressTimer()
  if (slideUnlockTimer != null) window.clearTimeout(slideUnlockTimer)
  if (chapterCueTimer != null) window.clearTimeout(chapterCueTimer)
  clearBufferingIndicator()
  clearAutoPlayRetry()
  disposeSwipe()
  disposeControls()
  stopCurrent()
  document.body.style.overflow = previousOverflow
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
  transition:
    opacity 180ms ease,
    transform 180ms ease;
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

.video-fullscreen {
  justify-self: end;
  width: 44px;
  height: 44px;
  color: #fff;
  border: 0;
  background: transparent;
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

.short-video-page.is-fullscreen .video-stage {
  width: 100%;
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

.video-media-frame.is-touch-dragging {
  transition: none;
}

.video-media-frame.is-touch-settling {
  transition: transform 220ms cubic-bezier(0.22, 0.72, 0.24, 1);
}

.video-media-preview {
  pointer-events: none;
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
  transition:
    opacity 180ms ease,
    transform 180ms ease;
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
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.video-sound svg {
  width: 23px;
  height: 23px;
}

.video-reactions {
  position: absolute;
  right: 18px;
  bottom: calc(env(safe-area-inset-bottom) + 132px);
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.video-reaction {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgb(15 15 20 / 22%);
  box-shadow: none;
  color: rgb(255 255 255 / 72%);
  backdrop-filter: blur(6px);
  transition:
    transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1),
    background 180ms ease,
    color 180ms ease,
    box-shadow 180ms ease;
}

.video-reaction svg {
  width: 21px;
  height: 21px;
}

.video-reaction.is-active {
  background: rgb(15 15 20 / 32%);
  color: #ef8194;
}

.video-reaction.is-active.is-dislike {
  color: #bdcde0;
}

.video-reaction:active {
  transform: scale(0.94);
}

.video-reaction .reaction-heart {
  fill: transparent;
  transition: fill 180ms ease;
}

.video-reaction.is-active .reaction-heart {
  fill: currentColor;
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

.video-buffering {
  position: absolute;
  z-index: 2;
  top: 50%;
  left: 50%;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  transform: translate(-50%, -50%);
}

.video-buffering span {
  width: 26px;
  height: 26px;
  border: 2px solid rgb(255 255 255 / 28%);
  border-top-color: #fff;
  border-radius: 50%;
  animation: video-buffering-spin 720ms linear infinite;
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
  transition: opacity 180ms ease;
}

.short-video-page.is-fullscreen .video-nav {
  right: max(20px, env(safe-area-inset-right));
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

.chapter-cue {
  position: absolute;
  z-index: 3;
  top: calc(env(safe-area-inset-top) + 72px);
  left: 50%;
  max-width: min(82vw, 360px);
  padding: 8px 14px;
  border: 1px solid rgb(255 255 255 / 14%);
  border-radius: 999px;
  transform: translateX(-50%);
  background: rgb(20 20 20 / 72%);
  box-shadow: 0 8px 28px rgb(0 0 0 / 24%);
  color: rgb(255 255 255 / 92%);
  font-size: 12px;
  text-align: center;
  backdrop-filter: blur(12px);
}

.controls-hidden .video-header {
  transform: translateY(-12px);
  opacity: 0;
  pointer-events: none;
}

.controls-hidden .video-meta {
  transform: translateY(8px);
  opacity: 0;
}

.controls-hidden .video-sound {
  transform: scale(0.92);
  opacity: 0;
  pointer-events: none;
}

.controls-hidden .video-reactions {
  transform: translateY(8px) scale(0.92);
  opacity: 0;
  pointer-events: none;
}

.controls-hidden .video-nav {
  opacity: 0;
  pointer-events: none;
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
}

@media (max-width: 680px), (pointer: coarse) {
  .video-nav {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .media-slide-up-enter-active,
  .media-slide-up-leave-active,
  .media-slide-down-enter-active,
  .media-slide-down-leave-active {
    transition: none;
  }

  .video-media-frame.is-touch-settling,
  .video-header,
  .video-meta,
  .video-sound,
  .video-reactions,
  .video-nav {
    transition: none;
  }
}

@keyframes video-buffering-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
