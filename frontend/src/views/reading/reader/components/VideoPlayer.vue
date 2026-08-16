<template>
  <div class="video-player" :style="containerStyle">
    <!-- ============================================================
         Placeholder state: user hasn't activated playback yet.
         NO <video> element in DOM — zero network requests.
         ============================================================ -->
    <div
      v-if="!activated"
      class="video-placeholder"
      data-reader-video-surface
      @click="handleActivate"
    >
      <!-- 预览帧：静音、暂停的 <video> 加载首帧画面，仅取 metadata + 首帧，不持续下载 -->
      <video
        ref="previewRef"
        class="video-preview"
        :src="hqUrl"
        muted
        playsinline
        webkit-playsinline
        preload="metadata"
        @loadedmetadata="onPreviewMetadata"
        @seeked="onPreviewSeeked"
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
         error has occurred. preload="none" — no bytes until play().
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
      preload="none"
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
} from '@/views/reading/reader/videoPlaybackCoordinator'

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
  active: true,
  scrollerRoot: null,
})

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

/** 预览用 <video>（placeholder 状态加载首帧画面）。 */
const previewRef = ref<HTMLVideoElement | null>(null)

/** 首帧是否已成功显示（用于控制占位 overlay 是否隐藏图标层）。 */
const previewReady = ref(false)

/** Video native dimensions populated by loadedmetadata (fallback aspect ratio). */
const nativeWidth = ref(0)
const nativeHeight = ref(0)

/** IntersectionObserver — wired by parent in Todo 4 (preserved pattern). */
let visibilityObserver: IntersectionObserver | null = null
let unloadTimer: ReturnType<typeof setTimeout> | null = null

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

  // 释放预览 <video> 资源（src 切走后 seek/播放帧不再占用内存）
  const preview = previewRef.value
  if (preview !== null) {
    preview.removeAttribute('src')
    preview.load()
  }

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
    if (e instanceof DOMException && e.name === 'AbortError') {
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

function onPreviewMetadata(): void {
  const video = previewRef.value
  if (video === null) return
  // 定位到首帧（0.1s 而非 0，部分编码器在 0 处无关键帧）
  try {
    video.currentTime = 0.1
  } catch {
    // 某些容器不支持 seek，忽略——保持图标占位
  }
}

function onPreviewSeeked(): void {
  previewReady.value = true
}

function onPreviewError(): void {
  // 预览帧加载失败不阻塞播放——用户仍可点击激活完整播放
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

function setupVisibilityObserver(): void {
  visibilityObserver?.disconnect()
  visibilityObserver = null
  if (unloadTimer !== null) {
    clearTimeout(unloadTimer)
    unloadTimer = null
  }
  const video = videoRef.value
  if (!video) return
  const root = props.scrollerRoot ?? undefined

  visibilityObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.target !== video) continue
        if (entry.intersectionRatio < 0.01) {
          // Off-screen: pause immediately, start unload timer
          video.pause()
          savePosition(props.mediaId ?? 0, video.currentTime)
          if (unloadTimer !== null) {
            clearTimeout(unloadTimer)
          }
          unloadTimer = setTimeout(() => {
            unloadVideo('off-screen-timeout')
            unloadTimer = null
          }, 1200)
        } else {
          // Back on-screen: cancel pending unload
          if (unloadTimer !== null) {
            clearTimeout(unloadTimer)
            unloadTimer = null
          }
        }
      }
    },
    { root, threshold: [0, 0.01] },
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

  // If video element already exists, connect observer immediately
  setupVisibilityObserver()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  document.removeEventListener('pagehide', onVisibilityChange)
  unloadVideo('component-unmount')
  visibilityObserver?.disconnect()
  visibilityObserver = null
  if (unloadTimer !== null) {
    clearTimeout(unloadTimer)
    unloadTimer = null
  }
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
    }
  },
)

// When active becomes false (parent viewport scrolls item out of slots),
// immediately unload the video to free GPU/memory resources.
watch(
  () => props.active,
  (isActive) => {
    if (!isActive) {
      unloadVideo('inactive')
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
/* Placeholder (shown before user clicks to activate)                 */
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

.video-placeholder:hover {
  opacity: 0.8;
}

/* 预览帧：填满占位区，contain 保持完整画面 */
.video-preview {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* 覆盖层：预览就绪后仅保留时长角标，隐藏播放图标 */
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
