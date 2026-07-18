<template>
  <div class="reader-page">
    <!-- 桌面工具栏：迁移前行为 100% 保留（常驻渲染，隐藏由 settings.showToolbar 的 CSS 类控制，不进移动端状态机） -->
    <ReaderToolbar
      v-if="mode === 'desktop'"
      :mode="mode"
      :title="comicTitle"
      :current-page="store.currentPage"
      :total-pages="store.totalPages"
      :prev-chapter-id="store.prevChapterId"
      :next-chapter-id="store.nextChapterId"
      @back="goBack"
      @prev-chapter="goChapter(store.prevChapterId!)"
      @next-chapter="goChapter(store.nextChapterId!)"
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

    <!-- Reader Viewport -->
    <ReaderViewport
      v-else
      ref="viewportComponentRef"
      :pages="store.pages"
      :current-page="store.currentPage"
      @update:current-page="onPageChange"
      @visible-range="onVisibleRange"
    />

    <!-- 移动端覆盖层：显隐全部由 useReaderToolbar 状态机驱动 -->
    <template v-if="mode === 'mobile'">
      <ReaderToolbar
        v-if="toolbarVisible"
        :mode="mode"
        :title="comicTitle"
        @back="nav.goBack"
        @open-settings="dispatch(ReaderAction.OpenSettings)"
      />
      <ReaderBottomNav
        v-if="toolbarVisible"
        :current-page="store.currentPage"
        :total-pages="store.totalPages"
        :has-prev="store.prevChapterId !== null"
        :has-next="store.nextChapterId !== null"
        @prev-chapter="nav.goPrevChapter"
        @catalog="nav.goToCatalog"
        @next-chapter="nav.goNextChapter"
      />
      <ReaderSettingsDrawer
        :visible="isSettings"
        @close="dispatch(ReaderAction.CloseSettings)"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import { useReaderStore } from '@/stores/reader-store'
import { useReaderSettingsStore } from '@/stores/reader-settings-store'
import ReaderViewport from '@/views/reading/reader/components/ReaderViewport.vue'
import ReaderToolbar from '@/views/reading/reader/components/ReaderToolbar.vue'
import ReaderBottomNav from '@/views/reading/reader/components/ReaderBottomNav.vue'
import ReaderSettingsDrawer from '@/views/reading/reader/components/ReaderSettingsDrawer.vue'
import { useInteractionMode } from '@/views/reading/reader/composables/useInteractionMode'
import { useReaderGesture } from '@/views/reading/reader/composables/useReaderGesture'
import {
  ReaderAction,
  useReaderToolbar,
} from '@/views/reading/reader/composables/useReaderToolbar'
import { useReaderNavigation } from '@/views/reading/reader/composables/useReaderNavigation'
import { comicApi } from '@/services/reading'
import { preloadEngine } from '@/utils/preload-engine'
import type { ComicDetailVO } from '@/types'

const route = useRoute()
const router = useRouter()
const store = useReaderStore()
const settings = useReaderSettingsStore()

// ── 移动端交互系统（设计规范 §3/§9）────────────────────────────
const { mode } = useInteractionMode()
const nav = useReaderNavigation()
// EXIT 哨兵（IMMERSIVE 下 AndroidBack）→ 返回详情页
const { dispatch, toolbarVisible, isSettings } = useReaderToolbar({ onExit: nav.goBack })

// ReaderViewport 组件实例 → 根元素（.reader-viewport），供手势绑定。
// 组件经 v-else 晚挂载，useReaderGesture 内部 watch 会补绑。
const viewportComponentRef = ref<InstanceType<typeof ReaderViewport> | null>(null)
const viewportElRef = computed<HTMLElement | null>(() => {
  const el: unknown = viewportComponentRef.value?.$el
  return el instanceof HTMLElement ? el : null
})
const gesture = useReaderGesture(viewportElRef)

// 手势 → 状态机：tap 仅移动端派发（桌面工具栏走 settings.showToolbar 布尔，
// 不进状态机）。swipe 不注册——章节切换由 BottomNav 负责（设计规范 §10 0.3）。
gesture.onTap(() => {
  if (mode.value === 'mobile') {
    dispatch(ReaderAction.TapCenter)
  }
})

const lastSyncedPage = ref(1)
const comicTitle = ref('')
const saveDebounceTimer = ref<number | null>(null)

// 桌面端返回/章节跳转：保留迁移前实现（含 /library 兜底），移动端走 nav.*
function goBack() {
  if (store.comicId) {
    router.push(`/comic/${store.comicId}`)
  } else {
    router.push('/library')
  }
}

function goChapter(chId: number) {
  router.push(`/reader/${chId}?page=1`)
}

function reload() {
  const chapterId = Number(route.params.chapterId)
  if (chapterId) store.loadChapter(chapterId)
}

function onPageChange(page: number) {
  if (page >= 1 && page <= store.totalPages) {
    store.currentPage = page
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === ' ') {
    e.preventDefault()
    store.nextPage()
  } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
    e.preventDefault()
    store.prevPage()
  } else if (e.key === '+' || e.key === '=') {
    e.preventDefault()
    settings.zoomIn()
  } else if (e.key === '-') {
    e.preventDefault()
    settings.zoomOut()
  } else if (e.key === '0') {
    e.preventDefault()
    settings.resetZoom()
  }
}

function onWheel(e: WheelEvent) {
  if (e.ctrlKey || e.metaKey) {
    e.preventDefault()
    if (e.deltaY < 0) {
      settings.zoomIn()
    } else {
      settings.zoomOut()
    }
  }
}

function onDblClick(e: MouseEvent) {
  // Reset zoom on double click of the page area
  const target = e.target as HTMLElement
  if (target.closest('.reader-viewport') || target.closest('.reader-image-item')) {
    settings.resetZoom()
  }
}

function onVisibleRange(range: { start: number; end: number; total: number }) {
  if (!settings.enablePreload) return
  preloadEngine.onVisibleChange(range.start, range.end, range.total)
}

onMounted(async () => {
  // 桌面专属交互（键盘快捷键 / Ctrl+滚轮缩放 / 双击重置缩放）：
  // 移动端不注册，避免与触摸手势系统冲突。
  if (mode.value === 'desktop') {
    document.addEventListener('keydown', onKeydown)
    document.addEventListener('wheel', onWheel, { passive: false })
    document.addEventListener('dblclick', onDblClick)
  }

  const chapterId = Number(route.params.chapterId)
  const pageFromQuery = Number(route.query.page)

  if (!chapterId) {
    store.error = '参数不完整'
    return
  }

  await store.loadChapter(chapterId)

  if (store.error) {
    ElMessage.error(store.error)
    return
  }

  preloadEngine.reset(store.totalPages)
  preloadEngine.setUrlResolver((index: number, priority: 'immediate' | 'cascade') => {
    const page = store.pages[index]
    if (!page) return null
    if (priority === 'immediate') {
      return page.hqUrl || page.lqUrl || null
    }
    return page.lqUrl || page.hqUrl || null
  })

  if (pageFromQuery >= 1 && pageFromQuery <= store.totalPages) {
    store.currentPage = pageFromQuery
  } else {
    await store.restoreProgress()
  }

  try {
    const detail = await comicApi.detail(store.comicId)
    const detailData = detail.data as ComicDetailVO
    comicTitle.value = detailData.title || `漫画 #${store.comicId}`
  } catch {
    comicTitle.value = `漫画 #${store.comicId}`
  }

  lastSyncedPage.value = store.currentPage

  watch(() => store.currentPage, (newPage) => {
    if (store.comicId > 0 && newPage !== lastSyncedPage.value) {
      if (saveDebounceTimer.value) clearTimeout(saveDebounceTimer.value)
      saveDebounceTimer.value = window.setTimeout(() => {
        lastSyncedPage.value = newPage
        store.saveProgress()
      }, 300)
    }
  })
})

onBeforeUnmount(() => {
  // 移动端未注册这些监听器，remove 为无害 no-op
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('wheel', onWheel)
  document.removeEventListener('dblclick', onDblClick)
  if (saveDebounceTimer.value) {
    clearTimeout(saveDebounceTimer.value)
  }
  if (store.comicId > 0 && store.currentPage !== lastSyncedPage.value) {
    store.saveProgress()
  }
  preloadEngine.destroy()
})
</script>

<style scoped>
.reader-page {
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg);
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
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 150ms ease;
}

.primary-btn:hover {
  background: var(--accent-hover);
}
</style>
