<template>
  <div
    class="reader-image-item"
    :style="containerStyle"
    :class="{
      'fit-width': settings.fitMode === 'WIDTH',
      'fit-height': settings.fitMode === 'HEIGHT',
      'fit-original': settings.fitMode === 'ORIGINAL',
    }"
  >
    <ProgressiveImage
      :lq="page.lqUrl"
      :hq="page.hqUrl"
      :mode="settings.qualityMode"
      :aspect-ratio="aspectRatio"
      :lq-status="page.lqStatus"
      :force-hq="forceHq"
      :class="imageClasses"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useReaderSettingsStore } from '@/stores/reader-settings-store'
import ProgressiveImage from './ProgressiveImage.vue'
import type { PageInfo } from '@/types'
import { DEFAULT_ASPECT_RATIO } from '@/types'

interface Props {
  item: PageInfo
  index: number
  active: boolean
  itemHeight: number
  forceHq: boolean
}

const props = defineProps<Props>()

const settings = useReaderSettingsStore()

const page = computed(() => props.item)
const aspectRatio = computed(() => {
  if (page.value.width && page.value.height && page.value.height > 0) {
    return page.value.width / page.value.height
  }
  return DEFAULT_ASPECT_RATIO
})

/*
 * 用明确的像素高度（= scroller 的 size 字段）替代 CSS aspect-ratio 控制
 * .reader-image-item 的高度。所有子元素的 height: 100% 此时有了确定参照，
 * 不再退化为 auto，内容高度与 slot 高度严格一致。
 */
const containerStyle = computed(() => ({
  height: `${props.itemHeight}px`,
}))

const imageClasses = computed(() => ({
  'fit-width-image': settings.fitMode === 'WIDTH',
  'fit-height-image': settings.fitMode === 'HEIGHT',
  'fit-original-image': settings.fitMode === 'ORIGINAL',
  'fit-auto-image': settings.fitMode === 'AUTO',
}))
</script>

<style scoped>
.reader-image-item {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

/*
 * ProgressiveImage 容器统一填满虚拟滚动 slot，由 object-fit: contain
 * 处理图片在容器内的适配。zoom 放大时 slot 变高，图片在 taller 容器中
 * 居中显示——用户滚动查看不同区域。此规则同时消除了"图片间缝隙"问题
 * （容器高度严格等于 slot 高度，不存在 flexbox 居中产生的间隙）。
 */
.reader-image-item :deep(.progressive-image) {
  width: 100%;
  height: 100%;
}

/* WIDTH 模式：与默认一致，填满 slot */
.reader-image-item.fit-width :deep(.progressive-image) {
  width: 100%;
  height: 100%;
}

/*
 * HEIGHT 模式：高度填满 slot，宽度由 aspect-ratio 自动推导。
 * max-width: 100% 防止超宽图片溢出视口。
 */
.reader-image-item.fit-height :deep(.progressive-image) {
  width: auto;
  height: 100%;
  max-width: 100%;
}

/*
 * ORIGINAL 模式：保持原逻辑（图片按原始尺寸渲染，允许溢出）。
 * 注意：zoom ≠ 100% 时可能与 slot 尺寸不一致，属于已知局限。
 * overflow: hidden 由 .progressive-image 基类提供。
 */
.reader-image-item.fit-original :deep(.progressive-image) {
  width: auto;
  height: auto;
  max-width: none;
  max-height: none;
}
</style>
