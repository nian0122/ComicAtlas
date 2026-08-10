<template>
  <div class="management-list-page">
    <header class="page-header">
      <div>
        <p class="page-eyebrow">LIFECYCLE / TRASH</p>
        <h1 class="page-title">回收站</h1>
        <p class="page-subtitle">已移入回收站的漫画可恢复，或在保留期后永久清理。</p>
      </div>
      <button class="ghost-btn" :disabled="loading" @click="loadFirstPage">刷新</button>
    </header>

    <div v-if="error" class="state error">{{ error }}</div>
    <div v-else-if="loading && comics.length === 0" class="state loading">加载中...</div>
    <div v-else-if="comics.length === 0" class="state empty">回收站为空</div>
    <section v-else class="trash-list">
      <article v-for="comic in comics" :key="comic.id" class="trash-row">
        <img v-if="comic.coverUrl" :src="comic.coverUrl" :alt="comic.title" class="cover">
        <div class="trash-copy">
          <strong>{{ comic.title }}</strong>
          <span>{{ comic.pageCount }} 页 · {{ comic.author || '未知作者' }}</span>
        </div>
        <div class="trash-actions">
          <button class="ghost-btn" :disabled="busyId === comic.id" @click="restore(comic)">恢复</button>
          <button class="danger-btn" :disabled="busyId === comic.id" @click="purge(comic)">永久清理</button>
        </div>
      </article>
      <div ref="sentinel" class="infinite-sentinel" aria-live="polite">
        <span v-if="infiniteLoading">正在加载更多...</span>
        <span v-else-if="!infiniteHasMore">已加载全部内容</span>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { comicApi, trashApi } from '@/services/api'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import type { ComicListVO } from '@/types'

const comics = ref<ComicListVO[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)

const { sentinel, loading: infiniteLoading, hasMore: infiniteHasMore, reset: resetInfinite } = useInfiniteScroll({
  loadMore: async () => {
    if (loading.value || comics.value.length >= total.value) return false
    page.value += 1
    await loadPage(true)
    return comics.value.length < total.value
  },
})
void sentinel

async function loadPage(append = false) {
  loading.value = true
  error.value = ''
  try {
    const response = await comicApi.list({ page: page.value, size: 24, status: 'TRASHED', sort: 'updatedAt' })
    const records = response.data.records ?? []
    comics.value = append ? [...comics.value, ...records] : records
    total.value = response.data.total ?? 0
  } catch (cause: unknown) {
    error.value = cause instanceof Error ? cause.message : '加载回收站失败'
    if (!append) comics.value = []
  } finally {
    loading.value = false
  }
}

async function loadFirstPage() {
  page.value = 1
  resetInfinite()
  await loadPage()
}

async function restore(comic: ComicListVO) {
  busyId.value = comic.id
  try {
    await trashApi.restoreComic(comic.id)
    comics.value = comics.value.filter((item) => item.id !== comic.id)
    total.value = Math.max(0, total.value - 1)
    ElMessage.success('漫画已恢复')
  } catch (cause: unknown) {
    ElMessage.error(cause instanceof Error ? cause.message : '恢复失败')
  } finally {
    busyId.value = null
  }
}

async function purge(comic: ComicListVO) {
  try {
    const result = await ElMessageBox.prompt('请输入永久清理确认 token。', '永久清理', {
      confirmButtonText: '永久清理',
      cancelButtonText: '取消',
      inputPattern: /.+/,
      inputErrorMessage: '请输入 token',
      type: 'warning',
    })
    busyId.value = comic.id
    await trashApi.purgeComic(comic.id, result.value)
    comics.value = comics.value.filter((item) => item.id !== comic.id)
    total.value = Math.max(0, total.value - 1)
    ElMessage.success('漫画已永久清理')
  } catch (cause: unknown) {
    if (cause instanceof Error && cause.message !== 'cancel') ElMessage.error(cause.message || '永久清理失败')
  } finally {
    busyId.value = null
  }
}

onMounted(loadFirstPage)
</script>

<style scoped>
.management-list-page { max-width: 1100px; }
.page-header { display: flex; justify-content: space-between; gap: 24px; margin-bottom: 24px; }
.page-eyebrow { color: var(--accent); font-size: 11px; letter-spacing: .14em; }
.page-title { margin: 6px 0; color: var(--text-primary); }
.page-subtitle { margin: 0; color: var(--text-secondary); }
.trash-list { display: grid; gap: 8px; }
.trash-row { display: flex; align-items: center; gap: 16px; padding: 12px; border: 1px solid var(--border); background: var(--bg-surface); }
.cover { width: 48px; height: 64px; object-fit: cover; }
.trash-copy { display: grid; flex: 1; gap: 6px; min-width: 0; }
.trash-copy strong { overflow: hidden; color: var(--text-primary); text-overflow: ellipsis; white-space: nowrap; }
.trash-copy span { color: var(--text-muted); font-size: 12px; }
.trash-actions { display: flex; gap: 8px; }
.danger-btn { border: 1px solid var(--color-danger, #d33); background: transparent; color: var(--color-danger, #d33); padding: 8px 12px; cursor: pointer; }
.infinite-sentinel { padding: 18px; color: var(--text-muted); text-align: center; }
</style>
