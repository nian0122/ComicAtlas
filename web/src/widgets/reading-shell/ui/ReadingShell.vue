<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { RouterView, RouterLink, useRoute } from 'vue-router'
import { AppButton } from '@/shared/ui/button'
import { ReadingIcon } from '@/shared/ui/reading'
const route = useRoute(),
  menu = ref(false),
  personal = ref<HTMLElement | null>(null),
  menuTrigger = ref<InstanceType<typeof AppButton> | null>(null)
const links = [
  { path: '/library', name: '漫画库', icon: 'library' },
  { path: '/history', name: '阅读历史', icon: 'history' },
  { path: '/favorites', name: '喜欢', icon: 'heart' },
] as const
const selected = (path: string) => route.path.startsWith(path)
function closeMenu(restoreFocus = true) {
  if (!menu.value) return
  menu.value = false
  if (restoreFocus) (menuTrigger.value?.$el as HTMLElement | undefined)?.focus()
}
function onPointerDown(event: PointerEvent) {
  if (menu.value && !personal.value?.contains(event.target as Node)) menu.value = false
}
watch(menu, (open) => {
  if (open) document.addEventListener('pointerdown', onPointerDown)
  else document.removeEventListener('pointerdown', onPointerDown)
})
onBeforeUnmount(() => document.removeEventListener('pointerdown', onPointerDown))
</script>
<template>
  <div
    class="ca-scope ca-shell"
    :class="{ 'is-detail': route.name === 'comic-detail', 'is-library': route.name === 'library' }"
  >
    <header class="ca-header">
      <RouterLink to="/library" class="ca-brand" aria-label="漫图馆 · 漫画库">
        <span class="ca-brand-word">漫图馆</span>
        <span class="ca-brand-tag">私人漫画馆<br />只属于你的一份收藏。</span>
      </RouterLink>
      <nav class="ca-top-nav" aria-label="阅读主导航">
        <RouterLink
          v-for="link in links"
          :key="link.path"
          :to="link.path"
          :aria-current="selected(link.path) ? 'page' : undefined"
          >{{ link.name }}</RouterLink
        >
      </nav>
      <div ref="personal" class="ca-personal" @keydown.esc="closeMenu()">
        <AppButton
          ref="menuTrigger"
          variant="text"
          icon-only
          aria-label="个人菜单"
          :aria-expanded="menu"
          @click="menu = !menu"
          ><ReadingIcon name="user"
        /></AppButton>
        <div v-if="menu" class="ca-personal-menu atlas-menu">
          <RouterLink to="/history" class="atlas-menu-item" @click="menu = false">我的阅读历史</RouterLink>
          <a href="/manage-entry" class="atlas-menu-item is-manage-item">漫画管理</a>
        </div>
      </div>
    </header>
    <main class="ca-main"><RouterView /></main>
    <nav class="ca-bottom-nav" aria-label="阅读主导航">
      <RouterLink
        v-for="link in links"
        :key="link.path"
        :to="link.path"
        :aria-current="selected(link.path) ? 'page' : undefined"
        ><ReadingIcon :name="link.icon" /><span>{{ link.name }}</span></RouterLink
      >
    </nav>
  </div>
</template>
