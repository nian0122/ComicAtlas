<template>
  <main class="short-video-page" @touchstart.passive="onTouchStart" @touchend="onTouchEnd" @wheel.prevent="onWheel">
    <header class="video-header">
      <AppButton class="video-back" type="button" aria-label="返回章节阅读" @click="goBack">←</AppButton>
      <strong class="video-title">{{ chapterTitle || '短视频播放' }}</strong>
      <span v-if="videos.length" class="video-count"
        >{{ currentIndex + 1 }} <span>/ {{ videos.length }}</span></span
      >
    </header>

    <div v-if="loading" class="video-state">正在加载本章视频…</div>
    <div v-else-if="loadError" class="video-state">
      <p>{{ loadError }}</p>
      <AppButton type="button" @click="loadChapter">重试</AppButton>
    </div>
    <div v-else-if="videos.length === 0" class="video-state">本章没有可播放的视频</div>
    <template v-else>
      <section class="video-stage" aria-label="短视频播放器">
        <video
          :key="currentVideo.id"
          ref="videoRef"
          class="video-media"
          :src="currentVideo.hqUrl"
          :muted="muted"
          playsinline
          webkit-playsinline
          preload="auto"
          loop
          @click="togglePlayback"
          @loadedmetadata="onMetadata"
          @timeupdate="onTimeUpdate"
          @play="isPlaying = true"
          @pause="isPlaying = false"
          @error="onVideoError"
        />
        <div class="video-shade" aria-hidden="true" />
        <div v-if="playError" class="video-play-error">
          <span>{{ playError }}</span>
          <AppButton type="button" @click="retryPlayback">重试播放</AppButton>
        </div>
        <AppButton
          v-else-if="!isPlaying"
          class="video-play-button"
          type="button"
          aria-label="播放视频"
          @click="togglePlayback"
          >▶</AppButton
        >
        <div class="video-footer">
          <span>第 {{ currentVideo.pageNumber }} 页</span>
          <AppButton class="video-sound" type="button" :aria-label="muted ? '开启声音' : '静音'" @click="toggleMute">
            {{ muted ? '开启声音' : '静音' }}
          </AppButton>
        </div>
        <div
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
        <AppButton type="button" aria-label="上一个视频" :disabled="currentIndex === 0" @click="move(-1)">↑</AppButton>
        <AppButton type="button" aria-label="下一个视频" :disabled="currentIndex === videos.length - 1" @click="move(1)"
          >↓</AppButton
        >
      </div>
      <video
        v-if="nextVideo"
        :key="nextVideo.id"
        class="video-preload"
        :src="nextVideo.hqUrl"
        muted
        playsinline
        preload="metadata"
        aria-hidden="true"
      />
    </template>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AppButton } from '@/shared/ui/button'
import { getApiErrorMessage } from '@/shared/api/http'
import { readerApi } from '@/entities/chapter'
import { isVideoMedia, type MediaItemInfo } from '@/entities/media'

const route = useRoute()
const router = useRouter()
const videos = ref<MediaItemInfo[]>([])
const chapterTitle = ref('')
const currentIndex = ref(0)
const videoRef = ref<HTMLVideoElement | null>(null)
const loading = ref(true)
const loadError = ref('')
const playError = ref('')
const isPlaying = ref(false)
const muted = ref(true)
const progress = ref(0)
const currentVideo = computed(() => videos.value[currentIndex.value]!)
const nextVideo = computed(() => videos.value[currentIndex.value + 1])
let requestSequence = 0
let playbackSequence = 0
let touchStartY = 0
let touchStartX = 0
let lastWheelTime = 0
let previousOverflow = ''

async function loadChapter(): Promise<void> {
  const chapterId = Number(route.params.chapterId)
  if (!Number.isSafeInteger(chapterId) || chapterId <= 0) {
    loading.value = false
    loadError.value = '章节地址无效'
    return
  }
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = ''
  try {
    const response = await readerApi.chapter(chapterId)
    if (sequence !== requestSequence) return
    chapterTitle.value = response.data.chapterTitle
    videos.value = response.data.pages.filter(
      (page) => isVideoMedia(page) && !!page.hqUrl && (!page.hqStatus || page.hqStatus === 'READY'),
    )
    const requestedPage = Number(route.query.page)
    const matchingIndex = videos.value.findIndex((page) => page.pageNumber >= requestedPage)
    currentIndex.value =
      !Number.isFinite(requestedPage) || requestedPage <= 0
        ? 0
        : matchingIndex >= 0
          ? matchingIndex
          : Math.max(0, videos.value.length - 1)
    loading.value = false
    await nextTick()
    if (sequence === requestSequence) await playCurrent()
  } catch (error: unknown) {
    if (sequence === requestSequence) loadError.value = getApiErrorMessage(error, '加载章节视频失败')
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function playCurrent(): Promise<void> {
  const video = videoRef.value
  if (!video) return
  playError.value = ''
  try {
    await video.play()
  } catch (error: unknown) {
    if (video !== videoRef.value) return
    // 浏览器禁止自动播放时保留手动播放入口。
    if (error instanceof DOMException && error.name === 'NotAllowedError') return
    playError.value = '视频无法播放，请重试'
  }
}

function stopCurrent(): void {
  const video = videoRef.value
  if (!video) return
  video.pause()
  video.removeAttribute('src')
  video.load()
}

async function move(direction: number): Promise<void> {
  const nextIndex = currentIndex.value + direction
  if (nextIndex < 0 || nextIndex >= videos.value.length) return
  const sequence = ++playbackSequence
  stopCurrent()
  currentIndex.value = nextIndex
  isPlaying.value = false
  playError.value = ''
  progress.value = 0
  await nextTick()
  if (sequence === playbackSequence) await playCurrent()
}

function togglePlayback(): void {
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
  const video = videoRef.value
  if (!video) return
  playError.value = ''
  video.load()
  void playCurrent()
}

function onMetadata(): void {
  progress.value = 0
}

function onTimeUpdate(): void {
  const video = videoRef.value
  progress.value =
    video && Number.isFinite(video.duration) && video.duration > 0 ? video.currentTime / video.duration : 0
}

function onVideoError(): void {
  playError.value = '视频无法播放，请重试'
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
  if (event.code === 'Space') {
    event.preventDefault()
    togglePlayback()
  }
}

function goBack(): void {
  void router.push({
    name: 'reader',
    params: { chapterId: route.params.chapterId },
    query: { page: currentVideo.value?.pageNumber ?? 1 },
  })
}

watch(
  () => route.params.chapterId,
  () => {
    stopCurrent()
    void loadChapter()
  },
)

onMounted(() => {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  document.addEventListener('keydown', onKeydown)
  void loadChapter()
})

onBeforeUnmount(() => {
  requestSequence++
  playbackSequence++
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
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 56px;
  padding: calc(env(safe-area-inset-top) + 12px) 18px 22px;
  background: linear-gradient(#0009, transparent);
}

.video-back {
  flex: none;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: #0006;
  color: #fff;
  font-size: 22px;
}

.video-title {
  flex: 1;
  overflow: hidden;
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.video-count {
  flex: none;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
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

.video-shade {
  position: absolute;
  inset: 75% 0 0;
  background: linear-gradient(transparent, #0008);
  pointer-events: none;
}

.video-footer {
  position: absolute;
  right: 18px;
  bottom: calc(env(safe-area-inset-bottom) + 24px);
  left: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #fffc;
  font-size: 13px;
  text-shadow: 0 1px 8px #000;
}

.video-sound {
  min-height: 40px;
  padding: 0 12px;
  border: 1px solid #fff4;
  border-radius: 999px;
  background: #0006;
  color: #fff;
  font-size: 12px;
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
