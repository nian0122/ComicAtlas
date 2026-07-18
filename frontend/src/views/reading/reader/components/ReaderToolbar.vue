<template>
  <!--
    Reader 工具栏入口组件（设计规范 §9）：按 mode 分发到桌面/移动变体。

    透传方案决策：采用「显式类型化 props/emits 联合面」而非 v-bind="$attrs"
    透明包装——两个变体的接口面很小（桌面 5 props / 3 emits，移动 1 prop /
    2 emits），显式声明可让 vue-tsc 对 ReaderPage 的调用点保持完整类型检查，
    $attrs 方案会丢失 prop 类型与 emits 校验。
  -->
  <ReaderToolbarDesktop
    v-if="mode === 'desktop'"
    :title="title"
    :current-page="currentPage"
    :total-pages="totalPages"
    :prev-chapter-id="prevChapterId"
    :next-chapter-id="nextChapterId"
    @back="emit('back')"
    @prev-chapter="emit('prevChapter')"
    @next-chapter="emit('nextChapter')"
  />
  <ReaderToolbarMobile
    v-else
    :title="title"
    @back="emit('back')"
    @open-settings="emit('openSettings')"
  />
</template>

<script setup lang="ts">
import ReaderToolbarDesktop from './ReaderToolbarDesktop.vue'
import ReaderToolbarMobile from './ReaderToolbarMobile.vue'
import type { InteractionMode } from '../composables/useInteractionMode'

// 哑组件：mode 由父级（ReaderPage）注入，自身不调用 useInteractionMode。
interface Props {
  /** 交互模式，决定渲染桌面或移动变体 */
  mode: InteractionMode
  /** 标题（两种模式共用） */
  title: string
  /** 当前页码——仅桌面变体使用 */
  currentPage?: number
  /** 总页数——仅桌面变体使用 */
  totalPages?: number
  /** 上一章 id——仅桌面变体使用（null 时隐藏按钮） */
  prevChapterId?: number | null
  /** 下一章 id——仅桌面变体使用（null 时隐藏按钮） */
  nextChapterId?: number | null
}

withDefaults(defineProps<Props>(), {
  currentPage: 1,
  totalPages: 0,
  prevChapterId: null,
  nextChapterId: null,
})

const emit = defineEmits<{
  /** 返回（两种模式共用） */
  (e: 'back'): void
  /** 上一章——桌面变体发出 */
  (e: 'prevChapter'): void
  /** 下一章——桌面变体发出 */
  (e: 'nextChapter'): void
  /** 打开设置抽屉——移动变体发出 */
  (e: 'openSettings'): void
}>()
</script>
