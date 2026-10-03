<template>
  <section class="favorites-page">
    <header class="page-heading">
      <div>
        <h1>喜欢</h1>
        <p>留住想再看的漫画与片段</p>
      </div>
    </header>
    <div class="filters">
      <div class="tabs" role="group" aria-label="喜欢的内容类型">
        <AppButton
          v-for="tab in tabs"
          :key="tab.value"
          variant="ghost"
          :class="{ selected: target === tab.value }"
          :aria-pressed="target === tab.value"
          @click="setFilters(tab.value, oldest)"
          >{{ tab.label }}</AppButton
        >
      </div>
      <el-select
        :model-value="oldest"
        aria-label="标记时间排序"
        @update:model-value="setFilters(target, Boolean($event))"
      >
        <el-option label="最近喜欢" :value="false" /><el-option label="最早喜欢" :value="true" />
      </el-select>
    </div>
    <div v-if="error" class="notice" role="alert">
      {{ error }} <AppButton variant="ghost" @click="load()">重试</AppButton>
    </div>
    <div v-if="loading" class="empty-state" role="status">正在加载喜欢的内容…</div>
    <div v-else-if="!items.length && !error" class="empty-state">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M12 20.2 4.7 13a4.8 4.8 0 0 1 6.8-6.8L12 6.7l.5-.5A4.8 4.8 0 0 1 19.3 13L12 20.2Z" />
      </svg>
      <p>还没有喜欢的{{ tabs.find((tab) => tab.value === target)?.label }}</p>
      <router-link to="/library">去漫画库看看</router-link>
    </div>
    <div v-else class="favorites-grid" :class="{ 'media-grid': target === 'MEDIA' }">
      <FavoriteCard v-for="item in items" :key="item.id" :item="item" :busy="busy" @remove="change($event, 'NONE')" />
    </div>
    <div v-if="!loading && (page > 1 || hasNext)" class="pagination">
      <AppButton variant="ghost" :disabled="page === 1 || busy" @click="load(target, oldest, page - 1)"
        >上一页</AppButton
      >
      <span>第 {{ page }} 页</span>
      <AppButton variant="ghost" :disabled="!hasNext || busy" @click="load(target, oldest, page + 1)">下一页</AppButton>
    </div>
    <div v-if="removed" class="undo-notice" role="status">
      已取消喜欢 <AppButton variant="ghost" :disabled="busy" @click="change(removed, 'LIKE')">撤销</AppButton>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FavoriteTarget } from '@/features/favorites'
import { AppButton } from '@/shared/ui/button'
import FavoriteCard from './FavoriteCard.vue'
import { useFavorites } from '../model/useFavorites'
const route = useRoute()
const router = useRouter()
const tabs: { label: string; value: FavoriteTarget }[] = [
  { label: '漫画', value: 'COMIC' },
  { label: '章节', value: 'CHAPTER' },
  { label: '媒体', value: 'MEDIA' },
]
const target = computed<FavoriteTarget>(() => tabs.find((tab) => tab.value === route.query.tab)?.value || 'COMIC')
const oldest = computed(() => route.query.order === 'oldest')
const { items, loading, error, busy, removed, page, hasNext, load, change } = useFavorites()
function setFilters(tab: FavoriteTarget, ascending: boolean) {
  void router.replace({ query: { tab, order: ascending ? 'oldest' : 'recent' } })
}
watch(
  [target, oldest],
  () => {
    void load(target.value, oldest.value, 1)
  },
  { immediate: true },
)
</script>
<style scoped src="./favorites.css"></style>
