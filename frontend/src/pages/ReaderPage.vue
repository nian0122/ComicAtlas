<template>
  <div class="reader-page">
    <!-- Toolbar -->
    <header class="reader-toolbar">
      <div class="toolbar-left">
        <button class="tool-btn" @click="goBack">
          <el-icon :size="20"><ArrowLeft /></el-icon>
        </button>
        <span class="toolbar-title">{{ comicTitle }}</span>
      </div>
      <div class="toolbar-center">
        <span class="page-indicator">{{ store.currentPage }} / {{ store.totalPages }}</span>
      </div>
      <div class="toolbar-right">
        <el-select
          v-model="settings.qualityMode"
          size="small"
          class="quality-select"
          @change="settings.setQualityMode"
        >
          <el-option label="自动" value="AUTO" />
          <el-option label="原图" value="HQ_ONLY" />
          <el-option label="省流" value="LQ_ONLY" />
        </el-select>
        <button
          v-if="store.prevChapterId"
          class="tool-btn chapter-btn"
          @click="goChapter(store.prevChapterId!)"
        >
          上一章
        </button>
        <button
          v-if="store.nextChapterId"
          class="tool-btn chapter-btn primary"
          @click="goChapter(store.nextChapterId!)"
        >
          下一章
        </button>
      </div>
    </header>

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
      :pages="store.pages"
      :current-page="store.currentPage"
      @update:current-page="onPageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import { useReaderStore } from '@/stores/reader-store'
import { useReaderSettingsStore } from '@/stores/reader-settings-store'
import ReaderViewport from '@/components/reader/ReaderViewport.vue'
import { comicApi } from '@/services/api'
import type { ComicDetailVO } from '@/types'

const route = useRoute()
const router = useRouter()
const store = useReaderStore()
const settings = useReaderSettingsStore()

const lastSyncedPage = ref(1)
const comicTitle = ref('')
const saveDebounceTimer = ref<number | null>(null)

function goBack() {
  if (store.comicId) {
    router.push(`/comics/${store.comicId}`)
  } else {
    router.push('/comics')
  }
}

function goChapter(chId: number) {
  router.push(`/comics/${store.comicId}/read?chapterId=${chId}&page=1`)
}

function reload() {
  const chapterId = Number(route.query.chapterId)
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
  }
}

function preloadPages() {
  if (!settings.enablePreload || store.pages.length === 0) return

  const current = store.currentPage - 1
  const windowSize = Math.max(0, settings.preloadWindow)

  for (let offset = -windowSize; offset <= windowSize; offset++) {
    const idx = current + offset
    if (idx < 0 || idx >= store.pages.length) continue
    const page = store.pages[idx]
    if (!page) continue

    if (offset === 0 || offset === 1) {
      // Current page and next page: preload HQ
      const img = new Image()
      img.src = page.hqUrl
    } else {
      // Other nearby pages: preload LQ
      const img = new Image()
      img.src = page.lqUrl
    }
  }
}

onMounted(async () => {
  document.addEventListener('keydown', onKeydown)

  const id = Number(route.params.id)
  const chapterId = Number(route.query.chapterId)
  const pageFromQuery = Number(route.query.page)

  if (!id || !chapterId) {
    store.error = '参数不完整'
    return
  }

  store.comicId = id
  await store.loadChapter(chapterId)

  if (store.error) {
    ElMessage.error(store.error)
    return
  }

  if (pageFromQuery >= 1 && pageFromQuery <= store.totalPages) {
    store.currentPage = pageFromQuery
  } else {
    await store.restoreProgress()
  }

  preloadPages()

  try {
    const detail = await comicApi.detail(id)
    const detailData = detail.data as ComicDetailVO
    comicTitle.value = detailData.title || `漫画 #${id}`
  } catch {
    comicTitle.value = `漫画 #${id}`
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
    preloadPages()
  })
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  if (saveDebounceTimer.value) {
    clearTimeout(saveDebounceTimer.value)
  }
  if (store.comicId > 0 && store.currentPage !== lastSyncedPage.value) {
    store.saveProgress()
  }
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

.reader-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 var(--space-lg);
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  z-index: 10;
}

.toolbar-left,
.toolbar-right,
.toolbar-center {
  display: flex;
  align-items: center;
  gap: var(--space-base);
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-h);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}

.page-indicator {
  font-size: 13px;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

.quality-select {
  width: 90px;
}

:deep(.quality-select .el-input__wrapper) {
  background: var(--surface-elevated);
  box-shadow: 0 0 0 1px var(--border) inset;
}

:deep(.quality-select .el-input__inner) {
  color: var(--text-h);
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-h);
  font-size: 14px;
  cursor: pointer;
  transition: background 150ms ease;
}

.tool-btn:hover {
  background: var(--surface-elevated);
}

.chapter-btn.primary {
  background: var(--accent);
  color: #fff;
}

.chapter-btn.primary:hover {
  background: var(--accent-hover);
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
