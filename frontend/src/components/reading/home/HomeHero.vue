<template>
  <HeroBanner
    :background-url="backgroundUrl"
    :poster-url="posterUrl"
    :title="title"
    :subtitle="subtitle"
    :primary-action="primaryAction"
    :secondary-action="secondaryAction"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import HeroBanner from '@/components/layout/HeroBanner.vue'
import type { HistoryVO } from '@/types'

interface HomeHeroProps {
  historyItem?: HistoryVO
}

const props = defineProps<HomeHeroProps>()
const router = useRouter()

const backgroundUrl = computed(() => props.historyItem?.coverUrl ?? '')
const posterUrl = computed(() => props.historyItem?.coverUrl)

const title = computed(() =>
  props.historyItem ? props.historyItem.comicTitle : '开始探索你的漫画库'
)

const subtitle = computed(() => {
  if (!props.historyItem) {
    return '你的漫画库还没有阅读记录，从最近加入的漫画开始吧'
  }
  const { chapterNo, pageNumber, totalPages, progressPercent } = props.historyItem
  const progressText = progressPercent != null && progressPercent > 0
    ? ` · 进度 ${progressPercent}%`
    : ''
  return `第 ${chapterNo} 章 · 第 ${pageNumber}/${totalPages} 页${progressText}`
})

const primaryAction = computed(() => {
  if (props.historyItem) {
    const { comicId, chapterId, pageNumber } = props.historyItem
      return {
        label: '继续阅读',
        onClick: () => router.push(`/reader/${chapterId}?page=${pageNumber}`),
      }
    }
    return {
      label: '浏览漫画库',
      onClick: () => router.push('/library'),
    }
  })

const secondaryAction = computed(() => {
  if (props.historyItem) {
      return {
        label: '详情',
        onClick: () => router.push(`/comic/${props.historyItem!.comicId}`),
      }
  }
  return undefined
})
</script>
