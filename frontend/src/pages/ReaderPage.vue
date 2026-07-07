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

    <!-- Pages -->
    <div
      v-else
      ref="scrollContainer"
      class="scroll-container"
      @scroll="onScroll"
    >
      <div
        v-for="(page, index) in store.pages"
        :key="page.id"
        :ref="(el) => setPageRef(el as HTMLElement, index)"
        class="page-item"
      >
        <el-image
          :src="store.hqMode ? page.hqUrl : page.lqUrl"
          :alt="`Page ${page.pageNumber}`"
          fit="contain"
          class="page-image"
          @load="onImageLoad"
        >
          <template #placeholder>
            <div class="page-skeleton">
              <div
                class="skeleton-box"
                :style="{
                  aspectRatio: page.width && page.height ? `${page.width}/${page.height}` : '3/4',
                }"
              />
            </div>
          </template>
          <template #error>
            <div class="page-error">
              <el-icon :size="32"><PictureFilled /></el-icon>
              <span>图片加载失败</span>
            </div>
          </template>
        </el-image>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import { useReaderStore } from '@/stores/reader-store'
import { comicApi } from '@/services/api'
import type { ComicDetailVO } from '@/types'

const route = useRoute()
const router = useRouter()
const store = useReaderStore()

const scrollContainer = ref<HTMLElement | null>(null)
const pageRefs = ref<HTMLElement[]>([])
const lastSyncedPage = ref(1)
const comicTitle = ref('')

function setPageRef(el: HTMLElement, index: number) {
  if (el) pageRefs.value[index] = el
}

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

function onImageLoad() {
  // placeholder for future preload tracking
}

function onScroll() {
  if (!scrollContainer.value || store.pages.length === 0) return

  const container = scrollContainer.value
  const containerHeight = container.clientHeight
  let visibleIndex = 0

  for (let i = 0; i < pageRefs.value.length; i++) {
    const el = pageRefs.value[i]
    if (!el) continue
    const rect = el.getBoundingClientRect()
    const containerRect = container.getBoundingClientRect()
    const elMid = rect.top - containerRect.top + rect.height / 2
    if (elMid <= containerHeight) visibleIndex = i
  }

  const currentPageNumber = visibleIndex + 1
  if (currentPageNumber !== store.currentPage) {
    store.currentPage = currentPageNumber
  }

  if (Math.abs(currentPageNumber - lastSyncedPage.value) >= 3 && store.comicId > 0) {
    lastSyncedPage.value = currentPageNumber
    store.saveProgress()
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === ' ') {
    e.preventDefault()
    store.nextPage()
    scrollToCurrentPage()
  } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
    e.preventDefault()
    store.prevPage()
    scrollToCurrentPage()
  }
}

function scrollToCurrentPage() {
  if (!scrollContainer.value) return
  const el = pageRefs.value[store.currentPage - 1]
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
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

  try {
    const detail = await comicApi.detail(id)
    const detailData = detail.data as ComicDetailVO
    comicTitle.value = detailData.title || `漫画 #${id}`
  } catch {
    comicTitle.value = `漫画 #${id}`
  }

  lastSyncedPage.value = store.currentPage
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
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

.scroll-container {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

.page-item {
  display: flex;
  justify-content: center;
  padding: var(--space-sm) 0;
}

.page-image {
  max-width: 100%;
  width: auto;
  display: block;
}

.page-skeleton {
  width: 100%;
  max-width: 800px;
  display: flex;
  justify-content: center;
}

.skeleton-box {
  width: 100%;
  background: var(--surface-elevated);
  border-radius: var(--radius-sm);
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.page-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  color: var(--text);
  padding: var(--space-2xl);
}
</style>
