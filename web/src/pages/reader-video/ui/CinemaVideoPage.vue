<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { reactionApi, type ReactionValue } from '@/entities/reaction'
import { comicApi } from '@/entities/comic'
import { useHistoryStore } from '@/entities/history'
import { getApiErrorMessage } from '@/shared/api/http'
import { ReadingDialog, ReadingIcon } from '@/shared/ui/reading'
import { useReadingNavigation } from '@/features/reading-navigation'
import { nextMark } from '@/features/reading-marks'
import { formatTime, isVideoItem, seekTarget, sourceOf } from '../model/feed'
import { useChapterFeed } from '../model/use-chapter-feed'
import { useFeedSwipe } from '../model/use-feed-swipe'

const route = useRoute(),
  router = useRouter(),
  history = useHistoryStore(),
  navigation = useReadingNavigation()
const {
  chapter,
  items,
  index,
  loading,
  error,
  current,
  title: chapterTitle,
  hasNext,
  hasPrevious,
  nextItem,
  previousItem,
  load,
  move: moveFeed,
  goToMedia,
} = useChapterFeed()

const stage = ref<HTMLElement>(),
  video = ref<HTMLVideoElement>(),
  ready = ref(false),
  isPlaying = ref(false),
  muted = ref(true),
  isBuffering = ref(false),
  mediaError = ref(''),
  currentTime = ref(0),
  duration = ref(0),
  videoRatio = ref(0),
  playbackRate = ref(1),
  speedOpen = ref(false),
  isSeeking = ref(false),
  imageVersion = ref(0),
  isSliding = ref(false),
  slideDirection = ref<'up' | 'down'>('up'),
  cue = ref(''),
  notice = ref(''),
  saveError = ref(''),
  reactions = ref<Record<string, ReactionValue>>({}),
  reactionReady = ref(false),
  reactionPending = ref(false),
  comicTitle = ref(''),
  burst = ref<{ key: number; x: number; y: number } | null>(null)

const playbackRates = [0.5, 1, 1.25, 1.5, 2] as const
const currentVideo = computed(() => isVideoItem(current.value))
const stageRatio = computed(() => {
  if (!currentVideo.value) return 9 / 16
  if (videoRatio.value > 0) return videoRatio.value
  const width = current.value?.width,
    height = current.value?.height
  if (width && height && width > 0 && height > 0) return width / height
  return 9 / 16
})
const nextVideo = computed(() => (isVideoItem(nextItem.value) ? nextItem.value : undefined))
const currentReaction = computed<ReactionValue>(() =>
  current.value ? (reactions.value[current.value.id] ?? 'none') : 'none',
)
const frameKey = computed(() => (current.value ? `${current.value.id}:${imageVersion.value}` : 'empty'))
const progressPercent = computed(() =>
  duration.value > 0 ? Math.min(100, Math.max(0, (currentTime.value / duration.value) * 100)) : 0,
)
const showDots = computed(() => !currentVideo.value && items.value.length <= 9)

const swipe = useFeedSwipe({
  hasPrevious,
  hasNext,
  previousItem,
  nextItem,
  canStart: () => !speedOpen.value && !isSliding.value,
  move: (direction) => move(direction),
  onTap: (point) => handleTap(point),
})
const {
  isTouchDragging,
  isTouchSettling,
  skipMediaTransition,
  previewItem,
  currentFrameStyle,
  previewFrameStyle,
  onTouchStart,
  onTouchMove,
  onTouchEnd,
  cancelTouchGesture,
  disposeSwipe,
} = swipe
const transitionName = computed(() => (skipMediaTransition.value ? '' : `feed-slide-${slideDirection.value}`))

let openSequence = 0,
  playToken = 0,
  slideTimer: ReturnType<typeof setTimeout> | undefined,
  bufferingTimer: ReturnType<typeof setTimeout> | undefined,
  cueTimer: ReturnType<typeof setTimeout> | undefined,
  noticeTimer: ReturnType<typeof setTimeout> | undefined,
  burstTimer: ReturnType<typeof setTimeout> | undefined,
  tapTimer: ReturnType<typeof setTimeout> | undefined,
  saveTimer: ReturnType<typeof setTimeout> | undefined,
  saveChain = Promise.resolve(),
  lastTouchTap = 0,
  lastTapAt = 0,
  lastWheel = 0,
  resumeAfterSeek = false,
  reactionSequence = 0,
  comicTitleId = '',
  previousOverflow = ''

function formatRate(rate: number): string {
  return String(rate)
}

function resetMediaState(): void {
  playToken++
  isPlaying.value = false
  mediaError.value = ''
  currentTime.value = 0
  duration.value = 0
  videoRatio.value = 0
  isSeeking.value = false
  resumeAfterSeek = false
  imageVersion.value = 0
  burst.value = null
  clearTimeout(tapTimer)
  tapTimer = undefined
  lastTapAt = 0
  clearTimeout(burstTimer)
  clearBuffering()
  isBuffering.value = currentVideo.value
}

function clearBuffering(): void {
  clearTimeout(bufferingTimer)
  bufferingTimer = undefined
  isBuffering.value = false
}

function attachVideo(element: unknown): void {
  if (element instanceof HTMLVideoElement) {
    video.value = element
    return
  }
  if (video.value && !video.value.isConnected) video.value = undefined
}

async function playCurrent(): Promise<void> {
  if (!currentVideo.value) return
  const element = video.value
  if (!element) return
  const token = ++playToken
  element.muted = muted.value
  element.playbackRate = playbackRate.value
  mediaError.value = ''
  try {
    await element.play()
  } catch (reason: unknown) {
    if (token !== playToken || element !== video.value) return
    if (reason instanceof DOMException && ['NotAllowedError', 'AbortError'].includes(reason.name)) return
    mediaError.value = '视频无法播放，请重试'
  }
}

function pauseCurrent(): void {
  playToken++
  video.value?.pause()
}

function stopCurrent(): void {
  playToken++
  const element = video.value
  if (!element) return
  element.pause()
  element.removeAttribute('src')
  element.load()
  video.value = undefined
}

function togglePlayback(): void {
  const element = video.value
  if (!element) return
  if (element.paused) void playCurrent()
  else {
    playToken++
    element.pause()
  }
}

function toggleMute(): void {
  muted.value = !muted.value
  if (video.value) video.value.muted = muted.value
}

function onLoadedMetadata(event: Event): void {
  const element = event.currentTarget
  if (!(element instanceof HTMLVideoElement) || element !== video.value) return
  if (Number.isFinite(element.duration)) duration.value = element.duration
  if (element.videoWidth > 0 && element.videoHeight > 0) videoRatio.value = element.videoWidth / element.videoHeight
}

function onTimeUpdate(event: Event): void {
  const element = event.currentTarget
  if (!(element instanceof HTMLVideoElement) || element !== video.value) return
  clearBuffering()
  if (Number.isFinite(element.duration) && element.duration > 0) duration.value = element.duration
  currentTime.value = element.currentTime
}

function onVideoPlay(event: Event): void {
  if (event.currentTarget !== video.value) return
  isPlaying.value = true
  clearBuffering()
}

function onVideoPause(event: Event): void {
  if (event.currentTarget !== video.value) return
  isPlaying.value = false
  clearBuffering()
}

function onVideoEnded(event: Event): void {
  if (event.currentTarget !== video.value) return
  void playCurrent()
}

function onVideoError(event: Event): void {
  if (event.currentTarget !== video.value) return
  clearBuffering()
  isPlaying.value = false
  mediaError.value = '视频无法播放，请重试'
}

function onVideoLoadStart(event: Event): void {
  if (event.currentTarget !== video.value) return
  isBuffering.value = true
}

function onVideoWaiting(event: Event): void {
  const element = event.currentTarget
  if (!(element instanceof HTMLVideoElement) || element !== video.value) return
  clearTimeout(bufferingTimer)
  bufferingTimer = setTimeout(() => {
    if (element === video.value && !element.paused) isBuffering.value = true
  }, 160)
}

function onVideoCanPlay(): void {
  clearBuffering()
}

function onImageError(): void {
  mediaError.value = '图片无法加载，请重试'
}

function retryMedia(): void {
  mediaError.value = ''
  if (!currentVideo.value) {
    imageVersion.value++
    return
  }
  video.value?.load()
  void playCurrent()
}

/** 单指单击延迟 280ms，双击取消单击并点赞；位置用于心形反馈。 */
function handleTap(point: { x: number; y: number }): void {
  const now = performance.now()
  if (now - lastTapAt < 280) {
    lastTapAt = 0
    clearTimeout(tapTimer)
    tapTimer = undefined
    doubleTap(point)
    return
  }
  lastTapAt = now
  clearTimeout(tapTimer)
  tapTimer = setTimeout(() => {
    tapTimer = undefined
    lastTapAt = 0
    if (currentVideo.value) togglePlayback()
  }, 280)
}

function doubleTap(point: { x: number; y: number }): void {
  const bounds = stage.value?.getBoundingClientRect()
  if (!bounds) return
  const x = Math.min(bounds.width - 24, Math.max(24, point.x - bounds.left))
  const y = Math.min(bounds.height - 80, Math.max(80, point.y - bounds.top))
  burst.value = { key: Date.now(), x, y }
  clearTimeout(burstTimer)
  burstTimer = setTimeout(() => (burst.value = null), 620)
  if (currentReaction.value !== 'like') void toggleReaction('like')
}

function onStageClick(event: MouseEvent): void {
  if ((event.target as HTMLElement).closest('button, a, input')) return
  if (performance.now() - lastTouchTap < 600) return
  handleTap({ x: event.clientX, y: event.clientY })
}

function touchStart(event: TouchEvent): void {
  lastTouchTap = performance.now()
  onTouchStart(event)
}

function onTouchMoveStage(event: TouchEvent): void {
  if (event.target instanceof Element && event.target.closest('input[type="range"]')) return
  event.preventDefault()
  onTouchMove(event)
}

function finishSlide(): void {
  isSliding.value = false
  clearTimeout(slideTimer)
  slideTimer = undefined
}

async function move(direction: 1 | -1): Promise<void> {
  if (speedOpen.value || isSliding.value) return
  slideDirection.value = direction > 0 ? 'up' : 'down'
  isSliding.value = true
  clearTimeout(slideTimer)
  slideTimer = setTimeout(finishSlide, 380)
  try {
    const result = await moveFeed(direction)
    if (result === 'chapter') showCue(chapterTitle.value)
  } catch (reason: unknown) {
    showNotice(getApiErrorMessage(reason, '相邻章节加载失败'))
  }
}

function showCue(text: string): void {
  cue.value = text
  clearTimeout(cueTimer)
  cueTimer = setTimeout(() => (cue.value = ''), 1600)
}

function showNotice(text: string): void {
  notice.value = text
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => (notice.value = ''), 2600)
}

function syncRoute(): void {
  const data = chapter.value,
    item = current.value
  if (!ready.value || !data || !item) return
  if (String(route.params.chapterId) === data.chapter.id && route.query.media === item.id) return
  void router.replace({
    name: 'reader-video',
    params: { chapterId: data.chapter.id },
    query: { ...route.query, media: item.id },
  })
}

function snapshot() {
  const data = chapter.value,
    item = current.value
  return data && item ? { comicId: data.comicId, chapterId: data.chapter.id, mediaId: item.id } : null
}

function persist(): void {
  clearTimeout(saveTimer)
  if (!ready.value) return
  const value = snapshot()
  if (!value) return
  saveChain = saveChain
    .catch(() => {})
    .then(async () => {
      try {
        await history.recordProgress(value.comicId, value.chapterId, value.mediaId)
        if (chapter.value?.chapter.id === value.chapterId) saveError.value = ''
      } catch (reason: unknown) {
        if (chapter.value?.chapter.id === value.chapterId) saveError.value = getApiErrorMessage(reason, '进度保存失败')
      }
    })
}

function schedulePersist(): void {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(persist, 700)
}

async function loadComicTitle(id: string): Promise<void> {
  if (!id || id === comicTitleId) return
  comicTitleId = id
  try {
    const data = await comicApi.detail(id)
    comicTitle.value = data.title
  } catch {
    comicTitle.value = ''
  }
}

async function loadReaction(): Promise<void> {
  const item = current.value
  if (!item) {
    reactionReady.value = false
    return
  }
  if (reactions.value[item.id] !== undefined) {
    reactionReady.value = true
    return
  }
  const sequence = ++reactionSequence
  reactionReady.value = false
  try {
    const saved = await reactionApi.get('media', item.id)
    if (sequence === reactionSequence) reactions.value = { ...reactions.value, [item.id]: saved.value }
  } catch {
    // 标记状态只影响侧栏，不阻断播放。
  } finally {
    if (sequence === reactionSequence) reactionReady.value = true
  }
}

async function toggleReaction(target: 'like' | 'dislike'): Promise<void> {
  const item = current.value
  if (!item || reactionPending.value || !reactionReady.value) return
  const previous = reactions.value[item.id] ?? 'none'
  const value = nextMark(previous, target)
  reactions.value = { ...reactions.value, [item.id]: value }
  reactionPending.value = true
  try {
    const saved = await reactionApi.set('media', item.id, value)
    reactions.value = { ...reactions.value, [item.id]: saved.value }
  } catch (reason: unknown) {
    reactions.value = { ...reactions.value, [item.id]: previous }
    showNotice(getApiErrorMessage(reason, '保存媒体标记失败'))
  } finally {
    reactionPending.value = false
  }
}

function onSeekStart(): void {
  resumeAfterSeek = isPlaying.value
  if (resumeAfterSeek) pauseCurrent()
  isSeeking.value = true
  clearBuffering()
}

function onSeekInput(event: Event): void {
  const value = Number((event.target as HTMLInputElement).value)
  currentTime.value = value
  if (video.value) video.value.currentTime = value
}

function onSeekEnd(): void {
  isSeeking.value = false
  if (resumeAfterSeek) void playCurrent()
  resumeAfterSeek = false
}

function seekBy(delta: number): void {
  const element = video.value
  if (!element || duration.value <= 0) return
  const target = seekTarget(element.currentTime, delta, duration.value)
  element.currentTime = target
  currentTime.value = target
}

function onSeekKeydown(event: KeyboardEvent): void {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  event.stopPropagation()
  seekBy((event.key === 'ArrowRight' ? 1 : -1) * (event.shiftKey ? 10 : 5))
}

function openSpeed(): void {
  speedOpen.value = true
}

function closeSpeed(): void {
  speedOpen.value = false
}

function selectRate(rate: number): void {
  playbackRate.value = rate
  if (video.value) video.value.playbackRate = rate
  closeSpeed()
}

function onWheel(event: WheelEvent): void {
  if (speedOpen.value || Math.abs(event.deltaY) < 12 || Date.now() - lastWheel < 450) return
  lastWheel = Date.now()
  void move(event.deltaY > 0 ? 1 : -1)
}

async function toggleFullscreen(): Promise<void> {
  if (document.fullscreenEnabled === false || typeof document.documentElement.requestFullscreen !== 'function') return
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await document.documentElement.requestFullscreen({ navigationUI: 'hide' })
  } catch {
    // 浏览器拒绝全屏时保持窗口内播放。
  }
}

function keydown(event: KeyboardEvent): void {
  if (event.defaultPrevented || event.isComposing || event.ctrlKey || event.metaKey || event.altKey) return
  const target = event.target instanceof Element ? event.target : null
  if (target?.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"])')) return
  if (speedOpen.value) {
    if (event.key === 'Escape') closeSpeed()
    return
  }
  const key = event.key.toLowerCase()
  if (key === 'arrowup' || key === 'arrowdown') {
    event.preventDefault()
    void move(key === 'arrowdown' ? 1 : -1)
    return
  }
  if (key === 'f') {
    event.preventDefault()
    void toggleFullscreen()
    return
  }
  if (!currentVideo.value) return
  if (event.code === 'Space' || key === 'k') {
    if (target?.closest('button, a')) return
    event.preventDefault()
    togglePlayback()
    return
  }
  if (key === 'm') {
    event.preventDefault()
    toggleMute()
    return
  }
  if (key === 'arrowleft' || key === 'arrowright') {
    event.preventDefault()
    seekBy((key === 'arrowright' ? 1 : -1) * (event.shiftKey ? 10 : 5))
  }
}

function pagehide(): void {
  const value = snapshot()
  if (ready.value && value) history.recordProgressKeepalive(value)
}

async function open(): Promise<void> {
  const chapterId = String(route.params.chapterId ?? '')
  const mediaId = typeof route.query.media === 'string' ? route.query.media : ''
  const sequence = ++openSequence
  ready.value = false
  saveError.value = ''
  pauseCurrent()
  resetMediaState()
  const ok = await load(chapterId, mediaId)
  if (sequence !== openSequence || !ok) return
  ready.value = true
  syncRoute()
  await nextTick()
  if (sequence !== openSequence) return
  schedulePersist()
  void loadReaction()
  void playCurrent()
}

watch(
  () => route.params.chapterId,
  (value) => {
    if (value === chapter.value?.chapter.id) return
    void open()
  },
  { immediate: true },
)

watch(
  () => route.query.media,
  (value) => {
    if (!ready.value || typeof value !== 'string') return
    goToMedia(value)
  },
)

watch(
  () => chapter.value?.comicId,
  (id) => {
    if (id) void loadComicTitle(id)
  },
)

watch(
  () => current.value?.id,
  async (value) => {
    if (!ready.value || !value) return
    pauseCurrent()
    resetMediaState()
    syncRoute()
    schedulePersist()
    void loadReaction()
    if (speedOpen.value) speedOpen.value = false
    await nextTick()
    if (!ready.value || current.value?.id !== value) return
    void playCurrent()
  },
)

onMounted(() => {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  document.addEventListener('keydown', keydown)
  window.addEventListener('pagehide', pagehide)
})

onBeforeUnmount(() => {
  persist()
  openSequence++
  clearTimeout(saveTimer)
  clearTimeout(slideTimer)
  clearTimeout(cueTimer)
  clearTimeout(noticeTimer)
  clearTimeout(burstTimer)
  clearTimeout(tapTimer)
  clearTimeout(bufferingTimer)
  disposeSwipe()
  stopCurrent()
  document.removeEventListener('keydown', keydown)
  window.removeEventListener('pagehide', pagehide)
  document.body.style.overflow = previousOverflow
})

function leave(): void {
  persist()
  const chapterId = chapter.value?.chapter.id ?? String(route.params.chapterId ?? '')
  const item = current.value
  if (!chapterId) {
    navigation.goToSource()
    return
  }
  navigation.goToReader(chapterId, { ...route.query, ...(item ? { media: item.id } : {}) })
}
</script>

<template>
  <div class="ca-scope feed-page" @wheel.prevent="onWheel">
    <div v-if="loading" class="feed-state" role="status" aria-busy="true">正在加载…</div>
    <section v-else-if="error" class="feed-state" role="alert">
      <p>{{ error }}</p>
      <button class="feed-state-button" @click="open">重试</button>
      <button class="feed-state-button is-quiet" @click="leave">返回漫画阅读</button>
    </section>
    <section v-else-if="!current" class="feed-state">
      <p>本章暂无可播放内容</p>
      <button class="feed-state-button is-quiet" @click="leave">返回漫画阅读</button>
    </section>
    <div
      v-else
      ref="stage"
      class="feed-stage"
      :style="{ '--stage-ratio': String(stageRatio) }"
      aria-label="短视频阅读内容"
      @click="onStageClick"
      @touchstart="touchStart"
      @touchmove="onTouchMoveStage"
      @touchend="onTouchEnd"
      @touchcancel="cancelTouchGesture"
    >
      <Transition
        :name="transitionName"
        :css="!skipMediaTransition"
        @after-enter="finishSlide"
        @after-leave="finishSlide"
      >
        <div
          :key="frameKey"
          class="feed-frame"
          :class="{ 'is-touch-dragging': isTouchDragging, 'is-touch-settling': isTouchSettling }"
          :style="currentFrameStyle"
        >
          <template v-if="currentVideo">
            <video
              :ref="attachVideo"
              class="feed-media is-video"
              :src="sourceOf(current) || undefined"
              :muted="muted"
              playsinline
              webkit-playsinline
              preload="auto"
              loop
              draggable="false"
              @loadedmetadata="onLoadedMetadata"
              @timeupdate="onTimeUpdate"
              @loadstart="onVideoLoadStart"
              @waiting="onVideoWaiting"
              @loadeddata="onVideoCanPlay"
              @canplay="onVideoCanPlay"
              @seeked="onVideoCanPlay"
              @playing="onVideoPlay"
              @play="onVideoPlay"
              @pause="onVideoPause"
              @ended="onVideoEnded"
              @error="onVideoError"
            />
          </template>
          <template v-else>
            <img class="feed-backdrop" :src="sourceOf(current)" alt="" aria-hidden="true" draggable="false" />
            <img
              :key="imageVersion"
              class="feed-media is-image"
              :src="sourceOf(current)"
              :alt="`第 ${current.pageNumber} 页`"
              draggable="false"
              @error="onImageError"
            />
          </template>
        </div>
      </Transition>
      <div
        v-if="previewItem"
        class="feed-frame feed-preview"
        :class="{ 'is-touch-dragging': isTouchDragging, 'is-touch-settling': isTouchSettling }"
        :style="previewFrameStyle"
        aria-hidden="true"
      >
        <template v-if="isVideoItem(previewItem)">
          <video
            class="feed-media is-video"
            :src="sourceOf(previewItem)"
            muted
            playsinline
            preload="none"
            draggable="false"
          />
        </template>
        <template v-else>
          <img class="feed-backdrop" :src="sourceOf(previewItem)" alt="" aria-hidden="true" draggable="false" />
          <img class="feed-media is-image" :src="sourceOf(previewItem)" alt="" draggable="false" />
        </template>
      </div>
      <div class="feed-scrim" aria-hidden="true" />
      <div
        v-if="currentVideo && isBuffering && !mediaError"
        class="feed-buffering"
        role="status"
        aria-label="视频缓冲中"
      >
        <span aria-hidden="true" />
      </div>
      <div v-if="mediaError" class="feed-error" role="alert">
        <p>{{ mediaError }}</p>
        <button class="feed-state-button" @click="retryMedia">重试</button>
      </div>
      <button
        v-else-if="currentVideo && !isPlaying && !isBuffering"
        class="feed-play"
        aria-label="播放视频"
        @click.stop="togglePlayback"
      >
        <ReadingIcon name="play" :size="28" />
      </button>
      <span
        v-if="burst"
        :key="burst.key"
        class="feed-burst"
        :style="{ left: `${burst.x}px`, top: `${burst.y}px` }"
        aria-hidden="true"
      >
        <ReadingIcon name="heart" :size="86" />
      </span>
      <header class="feed-top">
        <button class="feed-top-button" aria-label="返回漫画阅读" @click.stop="leave">
          <ReadingIcon name="back" :size="25" />
        </button>
        <span class="feed-top-spacer" />
        <span class="feed-counter"
          ><b>{{ index + 1 }}</b> / {{ items.length }}</span
        >
        <button class="feed-top-button" :aria-label="muted ? '开启声音' : '静音'" @click.stop="toggleMute">
          <ReadingIcon :name="muted ? 'mute' : 'volume'" :size="21" />
        </button>
      </header>
      <aside class="feed-rail" aria-label="短视频操作">
        <button
          class="feed-rail-button"
          :class="{ 'is-on': currentReaction === 'like' }"
          aria-label="喜欢"
          :aria-pressed="currentReaction === 'like'"
          :disabled="!reactionReady || reactionPending"
          @click.stop="toggleReaction('like')"
        >
          <ReadingIcon name="heart" :size="31" />
        </button>
        <button
          class="feed-rail-button"
          :class="{ 'is-on': currentReaction === 'dislike' }"
          aria-label="不喜欢"
          :aria-pressed="currentReaction === 'dislike'"
          :disabled="!reactionReady || reactionPending"
          @click.stop="toggleReaction('dislike')"
        >
          <ReadingIcon name="dislike" :size="31" />
        </button>
        <button class="feed-rail-button" aria-label="更多" @click.stop="openSpeed">
          <ReadingIcon name="more" :size="31" />
        </button>
      </aside>
      <div class="feed-caption">
        <b>@{{ comicTitle || chapterTitle }}</b>
        <p>{{ chapterTitle }}</p>
        <small v-if="currentVideo">{{ formatTime(currentTime) }} / {{ formatTime(duration) }}</small>
      </div>
      <div v-if="currentVideo" class="feed-progress-wrap">
        <span v-if="isSeeking" class="feed-seek-time">{{ formatTime(currentTime) }} / {{ formatTime(duration) }}</span>
        <input
          class="feed-progress"
          type="range"
          min="0"
          :max="duration || 0"
          step="0.1"
          :value="currentTime"
          :disabled="duration <= 0"
          :style="{ '--feed-progress': `${progressPercent}%` }"
          :aria-valuetext="`${formatTime(currentTime)} / ${formatTime(duration)}`"
          aria-label="播放进度"
          @pointerdown="onSeekStart"
          @input="onSeekInput"
          @change="onSeekEnd"
          @pointerup="onSeekEnd"
          @keydown="onSeekKeydown"
        />
      </div>
      <div v-else-if="showDots" class="feed-dots" aria-hidden="true">
        <i v-for="(item, position) in items" :key="item.id" :class="{ on: position === index }" />
      </div>
      <div v-if="cue" class="feed-cue" role="status">{{ cue }}</div>
      <div v-if="notice" class="feed-notice" role="alert">{{ notice }}</div>
      <div v-if="saveError" class="feed-save-error" role="alert">
        <span>{{ saveError }}</span
        ><button class="feed-toast-action" @click="persist">重试保存</button>
      </div>
    </div>
    <video
      v-if="nextVideo"
      class="feed-preload"
      :src="sourceOf(nextVideo)"
      muted
      playsinline
      preload="metadata"
      aria-hidden="true"
    />
    <ReadingDialog class="feed-sheet" :open="speedOpen" title="播放速度" @close="closeSpeed">
      <div class="feed-speeds" role="group" aria-label="选择播放速度">
        <button
          v-for="rate in playbackRates"
          :key="rate"
          :class="{ on: playbackRate === rate }"
          :aria-pressed="playbackRate === rate"
          @click="selectRate(rate)"
        >
          {{ formatRate(rate) }}×
        </button>
      </div>
    </ReadingDialog>
  </div>
</template>

<style scoped>
.feed-page {
  position: fixed;
  inset: 0;
  z-index: 60;
  overflow: hidden;
  background: #000;
  color: #f2f0ec;
  touch-action: none;
  overscroll-behavior: none;
  user-select: none;
}
.feed-stage {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #000;
}
@media (min-width: 900px) {
  .feed-stage {
    top: 50%;
    right: auto;
    bottom: auto;
    left: 50%;
    width: min(100vw, calc(100dvh * var(--stage-ratio, 0.5625)));
    height: min(100dvh, calc(100vw / var(--stage-ratio, 0.5625)));
    transform: translate(-50%, -50%);
    transition:
      width 220ms ease,
      height 220ms ease;
  }
}
.feed-frame {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #000;
  backface-visibility: hidden;
  will-change: transform;
}
.feed-frame.is-touch-dragging {
  transition: none;
}
.feed-frame.is-touch-settling {
  transition: transform 220ms cubic-bezier(0.22, 0.72, 0.24, 1);
}
.feed-preview {
  pointer-events: none;
}
.feed-media {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  user-select: none;
}
.feed-media.is-video {
  object-fit: contain;
}
.feed-media.is-image {
  object-fit: contain;
  z-index: 1;
}
.feed-backdrop {
  position: absolute;
  inset: -8%;
  width: 116%;
  height: 116%;
  object-fit: cover;
  filter: blur(26px) brightness(0.46);
  transform: scale(1.06);
}
.feed-slide-up-enter-active,
.feed-slide-up-leave-active,
.feed-slide-down-enter-active,
.feed-slide-down-leave-active {
  transition: transform 320ms cubic-bezier(0.22, 0.72, 0.24, 1);
}
.feed-slide-up-enter-from,
.feed-slide-down-leave-to {
  transform: translate3d(0, 100%, 0);
}
.feed-slide-up-leave-to,
.feed-slide-down-enter-from {
  transform: translate3d(0, -100%, 0);
}
.feed-scrim {
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  background: linear-gradient(transparent 62%, #000000c7 100%), linear-gradient(#00000080, transparent 18%);
}
.feed-top {
  position: absolute;
  z-index: 3;
  top: calc(10px + env(safe-area-inset-top));
  right: 10px;
  left: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
  text-shadow: 0 1px 10px #000;
}
.feed-top-spacer {
  flex: 1;
}
.feed-top-button {
  display: grid;
  flex: none;
  place-items: center;
  width: 44px;
  height: 44px;
  padding: 0;
  color: #fff;
  background: none;
  border: 0;
  text-shadow: 0 1px 10px #000;
}
.feed-counter {
  color: #ffffffd9;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.08em;
}
.feed-counter b {
  color: #fff;
  font-size: 13px;
  font-weight: 500;
}
.feed-rail {
  position: absolute;
  z-index: 3;
  right: 10px;
  bottom: calc(138px + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 22px;
  color: #fff;
}
.feed-rail-button {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 48px;
  min-height: 48px;
  padding: 0;
  color: #fff;
  background: none;
  border: 0;
  text-shadow: 0 1px 10px #000;
}
.feed-rail-button :deep(svg) {
  filter: drop-shadow(0 1px 6px #0009);
}
.feed-rail-button.is-on :deep(svg) {
  fill: #f2f0ec;
  stroke: #f2f0ec;
}
.feed-rail-button:disabled {
  opacity: 0.5;
}
.feed-caption {
  position: absolute;
  z-index: 3;
  right: 82px;
  bottom: calc(30px + env(safe-area-inset-bottom));
  left: 16px;
  color: #fff;
  text-shadow: 0 1px 10px #000;
}
.feed-caption b {
  display: block;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.02em;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.feed-caption p {
  margin: 6px 0 0;
  color: #ffffffe6;
  font-size: 12.5px;
  line-height: 1.5;
  letter-spacing: 0.03em;
}
.feed-caption small {
  display: block;
  margin-top: 6px;
  color: #ffffffb3;
  font-size: 10.5px;
  letter-spacing: 0.06em;
}
.feed-progress-wrap {
  position: absolute;
  z-index: 4;
  right: 0;
  bottom: 0;
  left: 0;
}
.feed-progress {
  appearance: none;
  display: block;
  width: 100%;
  height: 24px;
  margin: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
.feed-progress::-webkit-slider-runnable-track {
  height: 2px;
  background: linear-gradient(to right, #f2f0ec var(--feed-progress), #ffffff38 var(--feed-progress));
}
.feed-progress::-webkit-slider-thumb {
  appearance: none;
  width: 10px;
  height: 10px;
  margin-top: -4px;
  background: #f2f0ec;
  border: 0;
  border-radius: 0;
  opacity: 0;
}
.feed-progress:active::-webkit-slider-thumb,
.feed-progress:focus-visible::-webkit-slider-thumb {
  opacity: 1;
}
.feed-progress::-moz-range-track {
  height: 2px;
  background: #ffffff38;
}
.feed-progress::-moz-range-progress {
  height: 2px;
  background: #f2f0ec;
}
.feed-progress::-moz-range-thumb {
  width: 10px;
  height: 10px;
  background: #f2f0ec;
  border: 0;
  border-radius: 0;
  opacity: 0;
}
.feed-progress:active::-moz-range-thumb,
.feed-progress:focus-visible::-moz-range-thumb {
  opacity: 1;
}
.feed-progress:disabled {
  cursor: default;
}
.feed-seek-time {
  position: absolute;
  right: 16px;
  bottom: 26px;
  padding: 5px 8px;
  color: #fff;
  background: #000000a6;
  font-size: 10.5px;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.04em;
}
.feed-dots {
  position: absolute;
  z-index: 4;
  right: 0;
  bottom: calc(10px + env(safe-area-inset-bottom));
  left: 0;
  display: flex;
  justify-content: center;
  gap: 6px;
}
.feed-dots i {
  width: 4px;
  height: 4px;
  background: #ffffff4d;
}
.feed-dots i.on {
  background: #f2f0ec;
}
.feed-play {
  position: absolute;
  z-index: 3;
  top: 50%;
  left: 50%;
  display: grid;
  place-items: center;
  width: 74px;
  height: 74px;
  padding: 0;
  color: #fff;
  background: #0000004d;
  border: 1px solid #ffffff4d;
  transform: translate(-50%, -50%);
}
.feed-burst {
  position: absolute;
  z-index: 5;
  color: #f2f0ec;
  transform: translate(-50%, -50%);
  animation: feed-burst 620ms ease-out forwards;
  pointer-events: none;
}
.feed-burst :deep(svg) {
  fill: #f2f0ec;
  stroke: #f2f0ec;
  filter: drop-shadow(0 2px 16px #0009);
}
.feed-buffering {
  position: absolute;
  z-index: 3;
  top: 50%;
  left: 50%;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  transform: translate(-50%, -50%);
}
.feed-buffering span {
  width: 28px;
  height: 28px;
  border: 2px solid #ffffff47;
  border-top-color: #f2f0ec;
  border-radius: 50%;
  animation: feed-spin 720ms linear infinite;
}
.feed-error {
  position: absolute;
  z-index: 3;
  top: 50%;
  left: 50%;
  display: grid;
  justify-items: center;
  gap: 14px;
  width: min(320px, 86vw);
  padding: 24px;
  color: #e6e2db;
  background: #0b0b0aee;
  border: 1px solid #ffffff24;
  transform: translate(-50%, -50%);
  text-align: center;
}
.feed-error p {
  margin: 0;
  font-size: 12.5px;
  letter-spacing: 0.06em;
  line-height: 1.8;
}
.feed-cue,
.feed-notice,
.feed-save-error {
  position: absolute;
  z-index: 5;
  left: 50%;
  max-width: min(420px, calc(100% - 32px));
  padding: 8px 12px;
  color: #fff;
  background: #000000b3;
  font-size: 11px;
  letter-spacing: 0.06em;
  text-align: center;
  transform: translateX(-50%);
}
.feed-cue {
  top: calc(64px + env(safe-area-inset-top));
}
.feed-notice,
.feed-save-error {
  top: calc(104px + env(safe-area-inset-top));
  display: flex;
  align-items: center;
  gap: 8px;
}
.feed-toast-action {
  padding: 0 2px;
  color: #f2f0ec;
  background: none;
  border: 0;
  font-size: 11px;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.feed-preload {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}
.feed-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  min-height: 100dvh;
  padding: 32px;
  color: #e6e2db;
  text-align: center;
}
.feed-state p {
  max-width: 440px;
  margin: 0;
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 18px;
  letter-spacing: 0.1em;
  line-height: 1.8;
}
.feed-state-button {
  min-height: 44px;
  padding: 0 18px;
  color: #0b0b0a;
  background: #f2f0ec;
  border: 0;
  font-size: 12px;
  letter-spacing: 0.12em;
}
.feed-state-button.is-quiet {
  color: #e6e2db;
  background: none;
  border: 1px solid #ffffff24;
}
.feed-sheet {
  padding: 0;
  color: #f2f0ec;
  background: #111110f7;
  border: 1px solid #ffffff1f;
  border-radius: 0;
}
.feed-sheet :deep(header) {
  height: 54px;
  margin: 0;
  padding: 0 20px;
  border-bottom: 1px solid #ffffff12;
}
.feed-sheet :deep(h2) {
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 15px;
  font-weight: 400;
  letter-spacing: 0.12em;
}
.feed-sheet :deep(.app-button) {
  border-radius: 0;
}
.feed-speeds {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  padding: 18px 20px;
}
.feed-speeds button {
  height: 48px;
  color: #cfcbc4;
  background: #171716;
  border: 1px solid #ffffff1a;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.feed-speeds button.on {
  color: #0b0b0a;
  background: #f2f0ec;
  border-color: #f2f0ec;
}
@keyframes feed-spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes feed-burst {
  0% {
    opacity: 0;
    transform: translate(-50%, -50%) scale(0.35);
  }
  22% {
    opacity: 1;
    transform: translate(-50%, -50%) scale(1.12);
  }
  100% {
    opacity: 0;
    transform: translate(-50%, -50%) scale(1.28);
  }
}
@media (max-width: 899px) {
  .feed-sheet {
    position: fixed;
    inset: auto 0 0;
    width: 100%;
    max-width: 100%;
    margin: 0;
    border-right: 0;
    border-bottom: 0;
    border-left: 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .feed-slide-up-enter-active,
  .feed-slide-up-leave-active,
  .feed-slide-down-enter-active,
  .feed-slide-down-leave-active,
  .feed-frame.is-touch-settling,
  .feed-burst {
    transition: none;
    animation: none;
  }
}
</style>
