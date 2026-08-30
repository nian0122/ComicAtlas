<template>
  <div class="reader-page">
    <!-- 桌面工具栏：迁移前行为 100% 保留（常驻渲染，隐藏由 settings.showToolbar 的 CSS 类控制，不进移动端状态机） -->
    <ReaderToolbar
      v-if="mode === 'desktop'"
      :mode="mode"
      :title="toolbarTitle"
      :current-page="store.currentPage"
      :total-pages="store.totalPages"
      :prev-chapter-id="store.prevChapterId"
      :next-chapter-id="store.nextChapterId"
      @back="nav.goBack"
      @prev-chapter="goChapter(store.prevChapterId!)"
      @next-chapter="goChapter(store.nextChapterId!)"
      @jump-to-page="onPageChange"
    />

    <!-- Loading -->
    <div v-if="store.loading" class="reader-state">
      <div class="spinner" />
      <span>加载中...</span>
    </div>

    <!-- Error -->
    <div v-else-if="store.error" class="reader-state error">
      <el-icon :size="48"><WarningFilled /></el-icon>
      <span>{{ store.error }}</span>
      <button class="primary-btn" @click="reload">重试</button>
    </div>

    <!-- Empty -->
    <div v-else-if="store.pages.length === 0" class="reader-state">
      <el-icon :size="48"><PictureFilled /></el-icon>
      <span>暂无页面</span>
    </div>

    <!-- Reader Viewport:纵向=连续滚动,横向=单页翻页(§需求 2026-07) -->
    <ReaderPagedViewport
      v-else-if="isPagedMode"
      ref="viewportComponentRef"
      :pages="store.pages"
      :current-page="store.currentPage"
      :force-hq-pages="forceHqPages"
      @page-request="onPageRequest"
      @visible-range="onVisibleRange"
      @scroll-direction="onViewportScrollDirection"
      @video-started="onVideoStarted"
    />
    <ReaderViewport
      v-else
      ref="viewportComponentRef"
      :pages="store.pages"
      :current-page="store.currentPage"
      :force-hq-pages="forceHqPages"
      :page-mode="mode === 'mobile'"
      @update:current-page="onViewportPageChange"
      @visible-range="onVisibleRange"
      @scroll-direction="onViewportScrollDirection"
      @video-started="onVideoStarted"
    />

    <!-- 移动端覆盖层：显隐全部由 useReaderToolbar 状态机驱动 -->
    <template v-if="mode === 'mobile'">
      <ReaderToolbar
        v-if="toolbarVisible"
        :mode="mode"
        :title="toolbarTitle"
        @back="nav.goBack"
        @open-settings="dispatch(ReaderAction.OpenSettings)"
      />
      <ReaderBottomNav
        v-if="toolbarVisible"
        :current-page="store.currentPage"
        :total-pages="store.totalPages"
        :has-prev="store.prevChapterId !== null"
        :has-next="store.nextChapterId !== null"
        @prev-chapter="nav.goPrevChapter()"
        @catalog="nav.goToCatalog"
        @next-chapter="nav.goNextChapter"
        @jump-to-page="onPageChange"
      />
      <ReaderSettingsDrawer
        :visible="isSettings"
        :current-page="store.currentPage"
        :total-pages="store.totalPages"
        @close="dispatch(ReaderAction.CloseSettings)"
        @jump-to-page="onPageChange"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import { useReaderStore } from '@/features/reader/store'
import { useReaderSettingsStore } from '@/features/reader/settings-store'
import ReaderViewport from '@/features/reader/components/ReaderViewport.vue'
import ReaderPagedViewport from '@/features/reader/components/ReaderPagedViewport.vue'
import ReaderToolbar from '@/features/reader/components/ReaderToolbar.vue'
import ReaderBottomNav from '@/features/reader/components/ReaderBottomNav.vue'
import ReaderSettingsDrawer from '@/features/reader/components/ReaderSettingsDrawer.vue'
import { useInteractionMode } from '@/features/reader/composables/useInteractionMode'
import { useReaderGesture } from '@/features/reader/composables/useReaderGesture'
import { useReaderShortcuts } from '@/features/reader/composables/useReaderShortcuts'
import {
  ReaderAction,
  useReaderToolbar,
} from '@/features/reader/composables/useReaderToolbar'
import { useReaderNavigation } from '@/features/reader/composables/useReaderNavigation'
import { comicApi } from '@/entities/comic/api'
import { preloadEngine } from '@/features/reader/preload-engine'
import { isVideoMedia } from '@/entities/media/guards'
import type { ComicDetailVO } from '@/entities/comic/types'

const route = useRoute()
const router = useRouter()
const store = useReaderStore()
const settings = useReaderSettingsStore()

// ── 移动端交互系统（设计规范 §3/§9）────────────────────────────
const { mode } = useInteractionMode()
const nav = useReaderNavigation()
// EXIT 哨兵（IMMERSIVE 下 AndroidBack）→ 返回详情页
const { dispatch, toolbarVisible, isSettings } = useReaderToolbar({ onExit: nav.goBack })

// ReaderViewport / ReaderPagedViewport 组件实例 → 根元素，供手势绑定。
// 两组件互斥渲染共用同一 ref 位；v-if 切换时 useReaderGesture 内部 watch 会重绑。
const viewportComponentRef = ref<InstanceType<typeof ReaderViewport> | null>(null)
const viewportElRef = computed<HTMLElement | null>(() => {
  const el: unknown = viewportComponentRef.value?.$el
  return el instanceof HTMLElement ? el : null
})
const gesture = useReaderGesture(viewportElRef)

// 横向 = 单页翻页模式；其余方向（vertical/ltr/rtl）沿用纵向连续滚动
const isPagedMode = computed(() => settings.readingDirection === 'horizontal')

// 翻页请求统一决策：页内步进，越界自动跳章（上一章落到最后一页）
function onPageRequest(direction: 'next' | 'prev') {
  if (direction === 'next') {
    if (store.currentPage < store.totalPages) {
      store.currentPage++
    } else {
      nav.goNextChapter()
    }
  } else {
    if (store.currentPage > 1) {
      store.currentPage--
    } else {
      nav.goPrevChapter('last')
    }
  }
}

// 手势路由：翻页模式下 tap 三分区（左 30% 上一页 / 右 30% 下一页 / 中央唤工具栏）；
// 纵向模式 tap 仅移动端派发状态机（桌面工具栏走 settings.showToolbar 布尔）。
gesture.onTap((point) => {
  if (isPagedMode.value && viewportElRef.value) {
    const rect = viewportElRef.value.getBoundingClientRect()
    if (rect.width > 0) {
      const ratio = (point.x - rect.left) / rect.width
      if (ratio < 0.3) {
        onPageRequest('prev')
        return
      }
      if (ratio > 0.7) {
        onPageRequest('next')
        return
      }
    }
  }
  if (mode.value === 'mobile') {
    dispatch(ReaderAction.TapCenter)
  }
})

// swipe 仅翻页模式响应：内容随手指方向前进（左划=下一页）
gesture.onSwipe((direction) => {
  if (direction === 'left' || direction === 'right') {
    if (!isPagedMode.value) return
    onPageRequest(direction === 'left' ? 'next' : 'prev')
    return
  }

  if (mode.value !== 'mobile') return
  dispatch(direction === 'up' ? ReaderAction.SwipeUp : ReaderAction.SwipeDown)
})

const lastSyncedPage = ref(1)
const comicTitle = ref('')
const toolbarTitle = computed(() => {
  const chapterTitle = store.chapterTitle?.trim()
  const fallbackTitle = comicTitle.value || `漫画 #${store.comicId}`
  if (!chapterTitle) return fallbackTitle
  if (mode.value === 'mobile') return chapterTitle
  return `${fallbackTitle} · ${chapterTitle}`
})
const saveDebounceTimer = ref<number | null>(null)
/** 存在未确认落库的进度：翻页置位，saveProgress 成功才清除；卸载兜底据此决定是否重发 */
const progressDirty = ref(false)
/** 章节请求期间禁止把 store 的临时初始页码写入阅读历史。 */
const chapterLoading = ref(false)
let chapterLoadToken = 0
/** 被双击切到 HQ 的页面索引（0-based），使用 reactive Set 保持响应性 */
const forceHqPages = reactive(new Set<number>())

const { onKeydown, onWheel, onDblClick } = useReaderShortcuts({
  isPagedMode,
  readerStore: store,
  readerSettings: settings,
  forceHqPages,
  onPageRequest,
})

// 桌面端返回/章节跳转：保留迁移前实现（含 /library 兜底），移动端走 nav.*
function goChapter(chId: number) {
  router.push(`/reader/${chId}?page=1`)
}

async function reload() {
  if (saveDebounceTimer.value) {
    clearTimeout(saveDebounceTimer.value)
    saveDebounceTimer.value = null
  }

  // 重载会暂时把 currentPage 重置为 1，先确认当前进度，避免重载覆盖历史。
  let canRestoreProgress = !progressDirty.value
  if (progressDirty.value && store.comicId > 0 && store.chapterId > 0) {
    const chapterId = store.chapterId
    const pageNumber = store.currentPage
    const saved = await store.saveProgress()
    if (saved && store.chapterId === chapterId && store.currentPage === pageNumber) {
      lastSyncedPage.value = pageNumber
      progressDirty.value = false
      canRestoreProgress = true
    }
  }

  await loadCurrentChapter(true, canRestoreProgress)
}

async function loadCurrentChapter(preservePage = false, restoreProgress = true) {
  const chapterId = Number(route.params.chapterId)
  const rawPage = preservePage ? undefined : route.query.page

  if (!chapterId) {
    store.error = '参数不完整'
    return
  }

  const loadToken = ++chapterLoadToken
  chapterLoading.value = true

  try {
    await store.loadChapter(chapterId, preservePage)

    // 竞态闸：等待期间用户又切了章，丢弃本次过期结果。
    if (loadToken !== chapterLoadToken || Number(route.params.chapterId) !== chapterId) return

    if (store.error) {
      ElMessage.error(store.error)
      return
    }

    forceHqPages.clear()
    preloadEngine.reset(store.totalPages)
    preloadEngine.setUrlResolver((index: number, priority: 'immediate' | 'cascade') => {
      const page = store.pages[index]
      if (!page) return null
      // 视频只由 <video preload="metadata"> 按需读取，禁止图片预加载器
      // 把视频 URL 交给 new Image() 并发起额外媒体请求。
      if (isVideoMedia(page)) return null
      // 只有可视区附近的 immediate 才预加载 HQ；远处 cascade 一律优先 LQ，
      // 避免快速滚动时同时下载和解码大量原图导致 Safari 内存崩溃。
      const wantHq =
        priority === 'immediate' &&
        (settings.qualityMode !== 'LQ_ONLY' || forceHqPages.has(index))
      if (wantHq) return page.hqUrl || page.lqUrl || null
      return page.lqUrl || page.hqUrl || null
    })

    if (rawPage === 'last') {
      store.currentPage = Math.max(1, store.totalPages)
    } else {
      const pageFromQuery = Number(rawPage)
      // page=1 通常只是章节导航的默认参数，不应覆盖已保存的阅读进度。
      if (pageFromQuery > 1 && pageFromQuery <= store.totalPages) {
        store.currentPage = pageFromQuery
      } else if (restoreProgress) {
        await store.restoreProgress()
      }
    }

    // 移动端连续滚动模式不监听 currentPage 回流，首次恢复历史进度后必须主动定位，
    // 否则页码状态已是第 N 页，但窗口仍停留在章节开头。
    if (!isPagedMode.value) {
      await nextTick()
      viewportComponentRef.value?.scrollToPage?.(store.currentPage)
    }

    if (loadToken !== chapterLoadToken || Number(route.params.chapterId) !== chapterId) return

    try {
      const detail = await comicApi.detail(store.comicId)
      const detailData = detail.data as ComicDetailVO
      comicTitle.value = detailData.title || `漫画 #${store.comicId}`
    } catch {
      comicTitle.value = `漫画 #${store.comicId}`
    }

    lastSyncedPage.value = store.currentPage

    // 页码参数只用于本次导航，加载完成后从地址栏移除。
    // 否则浏览器崩溃/刷新会重复使用 ?page=1，覆盖阅读历史恢复结果。
    if (rawPage !== undefined && route.query.page === rawPage) {
      const nextQuery = { ...route.query }
      delete nextQuery.page
      await router.replace({ query: nextQuery })
    }
  } finally {
    if (loadToken === chapterLoadToken) {
      chapterLoading.value = false
    }
  }
}

function onPageChange(page: number) {
  if (page >= 1 && page <= store.totalPages) {
    store.currentPage = page
    // 连续滚动模式不依赖 currentPage watcher 回流定位，外部跳页必须显式执行定位。
    if (!isPagedMode.value) {
      viewportComponentRef.value?.scrollToPage?.(page)
    }
  }
}

/** 自然滚动只同步页码，不再次调用定位，避免滑动时页面位置被抢回。 */
function onViewportPageChange(page: number) {
  if (page >= 1 && page <= store.totalPages) {
    store.currentPage = page
  }
}

function onVisibleRange(range: { start: number; end: number; total: number }) {
  if (!settings.enablePreload) return
  preloadEngine.onVisibleChange(range.start, range.end, range.total)
}

function onVideoStarted(page: number) {
  if (page < 0 || page >= store.totalPages) return
  const pageNumber = page + 1
  if (store.currentPage !== pageNumber) {
    store.currentPage = pageNumber
  }
  if (store.comicId <= 0 || store.chapterId <= 0) return

  progressDirty.value = true
  store.saveProgress().then((ok) => {
    if (ok && store.currentPage === pageNumber) {
      lastSyncedPage.value = pageNumber
      progressDirty.value = false
    }
  })
}

/** 以真实滚动方向控制阅读端工具栏，避免依赖会被浏览器取消的 pointer swipe。 */
function onViewportScrollDirection(direction: 'up' | 'down') {
  if (mode.value === 'mobile') {
    dispatch(direction === 'up' ? ReaderAction.SwipeUp : ReaderAction.SwipeDown)
    return
  }

  if (direction === 'up' && settings.showToolbar) {
    settings.toggleToolbar()
  } else if (direction === 'down' && !settings.showToolbar) {
    settings.toggleToolbar()
  }
}

/**
 * 页面隐藏/卸载兜底保存：清除挂起 debounce，立即用 keepalive 发送最终进度。
 * 覆盖直接关闭标签页、刷新、移动端切后台（visibilitychange）等
 * onBeforeUnmount 不触发、且 debounce 未到点的场景。
 * 仅在 progressDirty 置位（存在未确认保存）时发送，axios 成功路径已清位，天然防重复。
 */
function flushProgressOnPageHide() {
  if (saveDebounceTimer.value) {
    clearTimeout(saveDebounceTimer.value)
    saveDebounceTimer.value = null
  }
  if (store.comicId > 0 && progressDirty.value) {
    lastSyncedPage.value = store.currentPage
    progressDirty.value = false
    store.saveProgressKeepalive()
  }
}

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    flushProgressOnPageHide()
  }
}

onMounted(async () => {
  // 全局页面为导航使用 smooth，但阅读器页码跳转必须即时定位，
  // 否则移动端窗口滚动会与自然滑动、工具栏显隐叠加造成位置抢占。
  document.documentElement.classList.add('reader-document')

  // 桌面专属交互（键盘快捷键 / Ctrl+滚轮缩放 / 双击重置缩放）：
  // 移动端不注册，避免与触摸手势系统冲突。
  if (mode.value === 'desktop') {
    document.addEventListener('keydown', onKeydown)
    document.addEventListener('wheel', onWheel, { passive: false })
    document.addEventListener('dblclick', onDblClick)
  }

  // 页面卸载兜底：关闭标签页/刷新/切后台时 onBeforeUnmount 不触发，
  // 但 debounce 也可能尚未到点，必须在此强制落库（keepalive 请求）。
  document.addEventListener('pagehide', flushProgressOnPageHide)
  document.addEventListener('visibilitychange', onVisibilityChange)

  await loadCurrentChapter()

  watch(() => store.currentPage, (newPage) => {
    if (!chapterLoading.value && store.comicId > 0 && store.chapterId > 0 && store.pages.length > 0 && newPage !== lastSyncedPage.value) {
      progressDirty.value = true
      if (saveDebounceTimer.value) clearTimeout(saveDebounceTimer.value)
      saveDebounceTimer.value = window.setTimeout(() => {
        // 保存成功才清 dirty：期间若页面卸载，keepalive 兜底重发仍未确认的进度
        store.saveProgress().then((ok) => {
          if (ok && store.currentPage === newPage) {
            lastSyncedPage.value = newPage
            progressDirty.value = false
          }
        })
      }, 300)
    }
  })
})

// 同名路由仅换参数时 Vue Router 复用组件实例,onMounted 不会重跑——
// 章节切换(工具栏/BottomNav/自动跳章)必须显式监听 chapterId 重载。
watch(() => route.params.chapterId, (newId, oldId) => {
  if (!newId || newId === oldId) return
  // 清掉挂起的进度 debounce,防其在新章加载后用旧章页码写脏数据;
  // 再同步落袋旧章进度(payload 同步构造,读到的仍是旧 chapterId)
  if (saveDebounceTimer.value) {
    clearTimeout(saveDebounceTimer.value)
    saveDebounceTimer.value = null
  }
  if (store.comicId > 0 && store.currentPage !== lastSyncedPage.value) {
    const chapterId = store.chapterId
    const pageNumber = store.currentPage
    store.saveProgress().then((ok) => {
      if (ok && store.chapterId === chapterId && store.currentPage === pageNumber) {
        lastSyncedPage.value = pageNumber
        progressDirty.value = false
      }
    })
  }
  loadCurrentChapter()
})

onBeforeUnmount(() => {
  document.documentElement.classList.remove('reader-document')
  // 移动端未注册这些监听器，remove 为无害 no-op
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('wheel', onWheel)
  document.removeEventListener('dblclick', onDblClick)
  document.removeEventListener('pagehide', flushProgressOnPageHide)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (saveDebounceTimer.value) {
    clearTimeout(saveDebounceTimer.value)
  }
  if (store.comicId > 0 && store.currentPage !== lastSyncedPage.value) {
    const chapterId = store.chapterId
    const pageNumber = store.currentPage
    store.saveProgress().then((ok) => {
      if (ok && store.chapterId === chapterId && store.currentPage === pageNumber) {
        lastSyncedPage.value = pageNumber
        progressDirty.value = false
      }
    })
  }
  preloadEngine.destroy()
})
</script>

<style scoped>
.reader-page {
  width: 100%;
  height: 100vh;
  height: var(--app-viewport-height, 100dvh);
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}

:global(html.reader-document) {
  scroll-behavior: auto !important;
}

@media (max-width: 1024px) {
  .reader-page {
    height: auto;
    min-height: 100dvh;
    overflow: visible;
  }
}

.reader-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-base);
  color: var(--text);
}

.reader-state.error {
  color: var(--danger);
}

.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--border-strong);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.primary-btn {
  padding: 8px 20px;
  background: var(--accent);
  color: var(--color-on-brand);
  border: none;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.primary-btn:hover {
  background: var(--accent-hover);
}
</style>
