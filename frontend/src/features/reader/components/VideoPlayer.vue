<template>
  <div class="video-player" :style="containerStyle">
    <!-- ============================================================
         Placeholder state: user hasn't activated playback yet.
         预览 <video> 只按需解码首帧（必要时静音微播放一瞬后立即暂停），
         不预加载完整媒体，也不生成独立预览图。
         ============================================================ -->
    <div
      v-if="!activated"
      class="video-placeholder"
      data-reader-video-surface
      @click="handleActivate"
    >
      <!-- 浏览器原生首帧预览：静音微播放只为解码一帧，随后立即暂停。 -->
      <video
        ref="previewRef"
        class="video-preview"
        :src="hqUrl"
        muted
        playsinline
        webkit-playsinline
        preload="metadata"
        @loadeddata="onPreviewLoadedData"
        @loadedmetadata="onPreviewMetadata"
        @error="onPreviewError"
      />
      <div
        class="video-placeholder-overlay"
        :class="{ 'preview-ready': previewReady }"
      >
        <el-icon v-if="!previewReady" :size="32"><VideoPlay /></el-icon>
        <span v-if="duration" class="video-duration">{{ formatDuration(duration) }}</span>
      </div>
    </div>

    <!-- ============================================================
         Video: rendered ONLY after user activation and only when no
         error has occurred. Metadata was preloaded by the placeholder video.
         ============================================================ -->
    <video
      v-else-if="!error"
      ref="videoRef"
      class="video-element"
      :src="hqUrl"
      :width="width"
      :height="height"
      controls
      playsinline
      webkit-playsinline
      preload="metadata"
      @loadedmetadata="onMetadata"
      @play="onPlay"
      @pause="onPause"
      @ended="onEnded"
      @error="onError"
    />

    <!-- ============================================================
         Error state: play() rejection, network error, unsupported
         codec. User sees codec info + retry button.
         ============================================================ -->
    <div v-else class="video-error" data-reader-interactive>
      <el-icon :size="32"><VideoPlay /></el-icon>
      <span class="video-error-title">无法播放此视频</span>
      <div v-if="hasCodecInfo" class="video-error-info">
        <span v-if="container">容器: {{ container }}</span>
        <span v-if="videoCodec">视频编码: {{ videoCodec }}</span>
        <span v-if="audioCodec">音频编码: {{ audioCodec }}</span>
      </div>
      <button class="video-retry-btn" @click="handleRetry">重试</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { VideoPlay } from '@element-plus/icons-vue'
import {
  activateSession,
  releaseSession,
  getPosition,
  savePosition,
} from '@/features/reader/videoPlaybackCoordinator'

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface Props {
  readonly hqUrl: string
  readonly mediaType: string
  readonly mediaId?: number
  readonly width?: number
  readonly height?: number
  readonly duration?: number
  readonly container?: string
  readonly videoCodec?: string
  readonly audioCodec?: string
  /** Whether this video slot is visible/active in the parent viewport. false → immediate unload. */
  readonly active?: boolean
  /** The scrollable ancestor to use as IntersectionObserver root (null = default viewport). */
  readonly scrollerRoot?: HTMLElement | null
}

const props = withDefaults(defineProps<Props>(), {
  mediaId: undefined,
  width: undefined,
  height: undefined,
  duration: undefined,
  container: undefined,
  videoCodec: undefined,
  audioCodec: undefined,
  active: true,
  scrollerRoot: null,
})

const emit = defineEmits<{
  (event: 'started'): void
}>()

// ---------------------------------------------------------------------------
// Reactive state — drives the 5-state machine
// ---------------------------------------------------------------------------

type PlayerState = 'placeholder' | 'loading' | 'playing' | 'paused' | 'error'

/** Logical state for debugging. DOM branches are driven by `activated` + `error`. */
const playerState = ref<PlayerState>('placeholder')

/** Whether the user has clicked to activate the video (triggers <video> creation). */
const activated = ref(false)

/** True when play() rejected or <video> fired an error event. */
const error = ref(false)

const videoRef = ref<HTMLVideoElement | null>(null)

/** 浏览器端首帧预览用 video，不会生成或保存独立预览图。 */
const previewRef = ref<HTMLVideoElement | null>(null)

/** 首帧已解码并可渲染。 */
const previewReady = ref(false)

/** Video native dimensions populated by loadedmetadata (fallback aspect ratio). */
const nativeWidth = ref(0)
const nativeHeight = ref(0)

/** 微播放等待首帧呈现的最长时间，超时放弃解码并暂停，保持占位状态。 */
const PREVIEW_DECODE_MAX_WAIT_MS = 5000

/** 正式播放器的可见性观察器：移出视窗只暂停，资源释放由 active 回收负责。 */
let visibilityObserver: IntersectionObserver | null = null

// ---------------------------------------------------------------------------
// Computed
// ---------------------------------------------------------------------------

const aspectRatio = computed(() => {
  // Prefer backend ffprobe dimensions
  if (props.width && props.height && props.height > 0) {
    return props.width / props.height
  }
  // Fallback: video element metadata
  if (nativeWidth.value && nativeHeight.value) {
    return nativeWidth.value / nativeHeight.value
  }
  // Last resort
  return 16 / 9
})

const containerStyle = computed(() => ({
  aspectRatio: `${aspectRatio.value}`,
  width: '100%',
}))

const hasCodecInfo = computed(
  () => !!(props.container || props.videoCodec || props.audioCodec),
)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

// ---------------------------------------------------------------------------
// Core: activate → create <video> → restore position → play()
// ---------------------------------------------------------------------------

async function handleActivate(): Promise<void> {
  error.value = false
  activated.value = true
  playerState.value = 'loading'
  previewReady.value = false

  // 正式播放器接管前暂停并释放首帧预览（含进行中的微播放解码）。
  releasePreviewElement()

  await nextTick()

  // Connect IntersectionObserver now that <video> is in the DOM
  setupVisibilityObserver()

  const video = videoRef.value
  if (video === null) return

  // Restore saved playback position
  video.currentTime = getPosition(props.mediaId ?? 0)

  try {
    await video.play()
    // play() resolved — state is set by onPlay event handler
  } catch (e: unknown) {
    // AbortError: interrupted by a new play request (expected race, ignore)
    // Safari 可能因 DOM 更新后用户手势链断开而返回 NotAllowedError。
    // 这不是媒体损坏：保留已创建的视频和原生控件，交给用户点击播放。
    if (
      e instanceof DOMException &&
      (e.name === 'AbortError' || e.name === 'NotAllowedError')
    ) {
      playerState.value = 'paused'
      return
    }
    console.debug('[VideoPlayer] play rejected:', e)
    error.value = true
    playerState.value = 'error'
  }
}

// ---------------------------------------------------------------------------
// Core: fully release video resources and return to placeholder
// ---------------------------------------------------------------------------

function unloadVideo(reason: string, mediaIdToSave?: number): void {
  console.debug('[VideoPlayer] unloadVideo:', reason)
  const id = mediaIdToSave ?? props.mediaId ?? 0
  const video = videoRef.value
  if (video === null) {
    activated.value = false
    error.value = false
    playerState.value = 'placeholder'
    return
  }

  savePosition(id, video.currentTime)
  video.pause()
  releaseSession(id)

  // Release network resources
  video.removeAttribute('src')
  video.load()

  // Destroy the DOM element
  activated.value = false
  error.value = false
  playerState.value = 'placeholder'
  previewReady.value = false
}

defineExpose({ unloadVideo })

// ---------------------------------------------------------------------------
// Retry: unload → fresh activation
// ---------------------------------------------------------------------------

async function handleRetry(): Promise<void> {
  unloadVideo('retry')
  await nextTick()
  await handleActivate()
}

// ---------------------------------------------------------------------------
// Event handlers
// ---------------------------------------------------------------------------

function onError(): void {
  error.value = true
  playerState.value = 'error'
}

function onPreviewLoadedData(): void {
  // loadeddata 表示当前帧已经可绘制。部分浏览器仍需要 play() 触发首帧，
  // 因此这里只标记已就绪，不阻止 onPreviewMetadata 启动受控微播放。
  previewReady.value = true
}

function onPreviewMetadata(): void {
  const preview = previewRef.value
  if (preview === null || preview.readyState < 1) return
  nativeWidth.value = preview.videoWidth
  nativeHeight.value = preview.videoHeight

  // preload=metadata 不保证所有浏览器绘制首帧。包括 iOS Safari 在内，
  // 统一使用静音微播放强制解码一帧，拿到后立即暂停，不进入正式播放。
  if (!props.active) return
  void decodePreviewFrame(preview)
}

/** 静音微播放强制解码首帧；不会调用正式播放器，也不会持续播放。 */
async function decodePreviewFrame(preview: HTMLVideoElement): Promise<void> {
  if (previewReady.value) return

  try {
    await preview.play()
  } catch {
    // 静音自动播放被浏览器策略拒绝：保持占位状态，不阻断用户手动播放。
    return
  }

  const framePainted = waitFirstPaintedFrame(preview)
  const decodeTimedOut = new Promise<boolean>((resolve) => {
    window.setTimeout(() => resolve(false), PREVIEW_DECODE_MAX_WAIT_MS)
  })
  const painted = await Promise.race([framePainted, decodeTimedOut])

  preview.pause()
  if (!painted || previewRef.value !== preview) return

  // 微播放可能前进数帧，回到起点保证冻结画面是首帧。
  preview.currentTime = 0
  previewReady.value = true
}

function waitFirstPaintedFrame(preview: HTMLVideoElement): Promise<boolean> {
  if (preview.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
    return Promise.resolve(true)
  }

  return new Promise((resolve) => {
    const videoWithFrameCallback = preview as HTMLVideoElement & {
      requestVideoFrameCallback?: (callback: () => void) => number
    }
    if (typeof videoWithFrameCallback.requestVideoFrameCallback === 'function') {
      videoWithFrameCallback.requestVideoFrameCallback(() => resolve(true))
      return
    }
    preview.addEventListener('loadeddata', () => resolve(true), { once: true })
  })
}

/** 暂停并释放预览元素，中止其网络加载与进行中的微播放解码。 */
function releasePreviewElement(): void {
  const preview = previewRef.value
  if (preview === null) return
  preview.pause()
  preview.removeAttribute('src')
  preview.load()
}

function onPreviewError(): void {
  // 首帧预览失败不影响点击后的正式播放。
  previewReady.value = false
}

function onMetadata(event: Event): void {
  const video = event.currentTarget
  if (!(video instanceof HTMLVideoElement)) return
  nativeWidth.value = video.videoWidth
  nativeHeight.value = video.videoHeight
}

function onPlay(event: Event): void {
  if (!(event.currentTarget instanceof HTMLVideoElement)) return
  activateSession(props.mediaId ?? 0, event.currentTarget)
  playerState.value = 'playing'
  emit('started')
}

function onPause(event: Event): void {
  if (!(event.currentTarget instanceof HTMLVideoElement)) return
  savePosition(props.mediaId ?? 0, event.currentTarget.currentTime)
  playerState.value = 'paused'
}

function onEnded(event: Event): void {
  if (!(event.currentTarget instanceof HTMLVideoElement)) return
  savePosition(props.mediaId ?? 0, event.currentTarget.currentTime)
  playerState.value = 'paused'
}

// ---------------------------------------------------------------------------
// Visibility observer — connects when <video> element is in DOM
// ---------------------------------------------------------------------------

// 移出视窗只暂停：保留加载状态与解码帧，滑回视窗不会因为 active 切换而黑屏。
// active 由虚拟滚动器控制，不能当作“真正离开视窗”的资源回收信号。
function setupVisibilityObserver(): void {
  visibilityObserver?.disconnect()
  visibilityObserver = null

  const video = videoRef.value ?? previewRef.value
  if (!video) return

  visibilityObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.target !== video) continue
        if (!entry.isIntersecting || entry.intersectionRatio <= 0) {
          video.pause()
        }
      }
    },
    { root: props.scrollerRoot ?? undefined, threshold: [0] },
  )
  visibilityObserver.observe(video)
}

function onVisibilityChange(): void {
  if (document.hidden && activated.value) {
    const video = videoRef.value
    if (video && !video.paused) {
      video.pause()
      savePosition(props.mediaId ?? 0, video.currentTime)
    }
  }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

onMounted(() => {
  // Register page visibility listener: pause active video when page is hidden
  document.addEventListener('visibilitychange', onVisibilityChange)
  document.addEventListener('pagehide', onVisibilityChange)
  setupVisibilityObserver()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  document.removeEventListener('pagehide', onVisibilityChange)
  releasePreviewElement()
  unloadVideo('component-unmount')
  visibilityObserver?.disconnect()
  visibilityObserver = null
})

// ---------------------------------------------------------------------------
// Watch: reset state when mediaId changes (parent reused the component)
// ---------------------------------------------------------------------------

watch(
  () => props.mediaId,
  (newId, oldId) => {
    if (oldId !== undefined && newId !== oldId) {
      // Save position for old media, release session, reset state
      unloadVideo('mediaId-changed', oldId)
      releasePreviewElement()
      previewReady.value = false
    }
  },
)

// active 是 RecycleScroller 的视图状态，不等于资源生命周期。
// 这里只暂停，保留预览 DOM、src 和已解码帧；真正复用到另一条媒体时，
// mediaId watcher 再释放旧媒体资源。
watch(
  () => props.active,
  (isActive) => {
    if (!isActive) {
      previewRef.value?.pause()
      videoRef.value?.pause()
      return
    }

    setupVisibilityObserver()
    const preview = previewRef.value
    if (!activated.value && preview && preview.readyState >= HTMLMediaElement.HAVE_METADATA) {
      void decodePreviewFrame(preview)
    }
  },
)

// When scrollerRoot changes (e.g. viewport resized and DOM recreated),
// reconnect the IntersectionObserver with the new root element.
watch(
  () => props.scrollerRoot,
  () => {
    setupVisibilityObserver()
  },
)
</script>

<style scoped>
/* ------------------------------------------------------------------ */
/* Container                                                          */
/* ------------------------------------------------------------------ */

.video-player {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  background: var(--surface);
  overflow: hidden;
}

/* ------------------------------------------------------------------ */
/* Video element (rendered only after activation)                     */
/* ------------------------------------------------------------------ */

.video-element {
  max-width: 100%;
  max-height: 100%;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* ------------------------------------------------------------------ */
/* Placeholder (shown before user clicks; no video request)           */
/* ------------------------------------------------------------------ */

.video-placeholder {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  width: 100%;
  height: 100%;
  min-width: 44px;
  min-height: 44px;
  cursor: pointer;
  color: var(--text);
  background: var(--surface);
  user-select: none;
}

.video-preview {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.video-placeholder:hover {
  opacity: 0.8;
}

.video-placeholder-overlay {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.video-placeholder-overlay.preview-ready {
  justify-content: flex-end;
  padding-bottom: var(--space-xs);
  background: linear-gradient(transparent 60%, rgba(0, 0, 0, 0.45));
}

.video-duration {
  font-size: 12px;
  color: var(--text-secondary);
}

/* ------------------------------------------------------------------ */
/* Error state                                                        */
/* ------------------------------------------------------------------ */

.video-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  width: 100%;
  height: 100%;
  color: var(--text);
  background: var(--surface);
  padding: var(--space-md);
  text-align: center;
}

.video-error-title {
  font-size: 14px;
  font-weight: 500;
}

.video-error-info {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs) var(--space-md);
  font-size: 12px;
  color: var(--text-secondary);
}

.video-retry-btn {
  margin-top: var(--space-xs);
  padding: 4px 16px;
  min-width: 44px;
  min-height: 44px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
  color: var(--text);
  font-size: 14px;
  cursor: pointer;
}

.video-retry-btn:hover {
  background: var(--surface-hover);
}
</style>
