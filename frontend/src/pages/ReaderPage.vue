<template>
  <div class="reader-page" v-loading="loading">
    <div class="reader-toolbar">
      <el-button :icon="ArrowLeft" circle @click="goBack" />
      <span class="toolbar-title">{{ comicTitle }}</span>
      <span class="toolbar-page">
        {{ store.currentPage }} / {{ store.pages.length }}
      </span>
      <el-button :icon="Setting" circle @click="drawerVisible = true" />
    </div>

    <div
      v-if="store.pages.length > 0"
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
          :src="hqMode ? page.hqUrl : page.lqUrl"
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

    <el-empty v-else-if="!loading" description="暂无页面" />

    <el-drawer
      v-model="drawerVisible"
      title="阅读设置"
      direction="rtl"
      size="280px"
    >
      <div class="drawer-content">
        <div class="setting-item">
          <span class="setting-label">高清模式</span>
          <el-switch v-model="hqMode" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Setting, PictureFilled } from '@element-plus/icons-vue'
import { useReaderStore } from '@/stores/reader-store'
import { historyApi, comicApi } from '@/services/api'
import type { ComicDetailVO } from '@/types'

const route = useRoute()
const router = useRouter()
const store = useReaderStore()

const loading = ref(true)
const drawerVisible = ref(false)
const scrollContainer = ref<HTMLElement | null>(null)
const pageRefs = ref<HTMLElement[]>([])
const lastSyncedPage = ref(0)
const comicTitle = ref('')

const hqMode = computed({
  get: () => store.hqMode,
  set: (v: boolean) => (store.hqMode = v),
})

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

function onImageLoad() {
  // placeholder for any image load tracking
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
    if (elMid <= containerHeight) {
      visibleIndex = i
    }
  }

  const currentPageNumber = visibleIndex + 1
  if (currentPageNumber !== store.currentPage) {
    store.currentPage = currentPageNumber
  }

  if (Math.abs(currentPageNumber - lastSyncedPage.value) >= 5 && store.comicId > 0) {
    lastSyncedPage.value = currentPageNumber
    syncProgress(currentPageNumber)
  }
}

async function syncProgress(pageNumber: number) {
  try {
    await historyApi.update(store.comicId, {
      chapterId: store.chapterId,
      pageNumber,
    })
  } catch {
    // silent fail on progress sync
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowRight') {
    store.nextPage()
    scrollToCurrentPage()
  } else if (e.key === 'ArrowLeft') {
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
  const page = Number(route.query.page) || 1

  if (!id || !chapterId) {
    loading.value = false
    ElMessage.warning('参数不完整')
    return
  }

  try {
    await store.loadChapter(id, chapterId)
    await store.restoreProgress()

    if (page > 1 && page <= store.pages.length) {
      store.currentPage = page
    }

    // Extract comic title for toolbar
    try {
      const detail = await comicApi.detail(id)
      const detailData = detail.data as ComicDetailVO
      comicTitle.value = detailData.title || `漫画 #${id}`
    } catch {
      comicTitle.value = `漫画 #${id}`
    }

    loading.value = false
    lastSyncedPage.value = store.currentPage
  } catch {
    loading.value = false
    ElMessage.error('加载章节失败')
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  if (store.comicId > 0 && store.currentPage !== lastSyncedPage.value) {
    syncProgress(store.currentPage)
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
  gap: 12px;
  padding: 8px 16px;
  background: var(--code-bg);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  z-index: 10;
}

.toolbar-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-h);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toolbar-page {
  font-size: 13px;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

.scroll-container {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

.page-item {
  display: flex;
  justify-content: center;
  padding: 8px 0;
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
  background: var(--code-bg);
  border-radius: 4px;
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
  gap: 8px;
  color: var(--text);
  padding: 40px;
}

.drawer-content {
  padding-top: 8px;
}

.setting-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}

.setting-label {
  font-size: 14px;
  color: var(--text-h);
}
</style>
