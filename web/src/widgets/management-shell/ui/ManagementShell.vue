<script setup lang="ts">
import { ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ManagementIcon } from '@/shared/ui/management'
defineProps<{ section: string; activeTasks: number; inspector: boolean; outline: boolean; busy: boolean }>()
defineEmits<{ section: [value: string] }>()
const collapsed = ref(localStorage.getItem('comicatlas.management.sidebar') === 'closed')
watch(collapsed, (value) => localStorage.setItem('comicatlas.management.sidebar', value ? 'closed' : 'open'))
const sections = [
  { id: 'library', title: '漫画管理', icon: 'library' },
  { id: 'reactions', title: '媒体标记', icon: 'heart' },
  { id: 'tasks', title: '任务中心', icon: 'task' },
  { id: 'storage', title: '存储统计', icon: 'storage' },
  { id: 'timers', title: '定时器', icon: 'clock' },
  { id: 'trash', title: '回收站', icon: 'trash' },
  { id: 'system', title: '设置', icon: 'filter' },
] as const
</script>
<template>
  <div class="mw-scope mw-app" :class="{ 'mw-with-inspector': inspector, 'mw-sidebar-collapsed': collapsed }">
    <aside class="mw-sidebar">
      <button
        class="mw-icon-button mw-sidebar-toggle"
        :aria-label="collapsed ? '展开位置导航' : '收起位置导航'"
        :aria-expanded="!collapsed"
        @click="collapsed = !collapsed"
      >
        <ManagementIcon :name="collapsed ? 'right' : 'back'" />
      </button>
      <RouterLink to="/manage" class="mw-brand" aria-label="ComicAtlas 漫画管理"
        ><span class="mw-brand-name">ComicAtlas<small>漫画管理</small></span
        ><span class="mw-brand-compact" aria-hidden="true">CA</span></RouterLink
      >
      <nav class="mw-navigation" aria-label="管理主导航">
        <button
          v-for="item in sections"
          :key="item.id"
          :aria-label="item.title"
          :disabled="busy"
          :class="{ active: section === item.id, 'mw-maintenance-entry': item.id === 'storage' }"
          :aria-current="section === item.id ? 'page' : undefined"
          @click="$emit('section', item.id)"
        >
          <ManagementIcon :name="item.icon" /><span>{{ item.title }}</span
          ><span v-if="item.id === 'tasks' && activeTasks" class="mw-nav-count">{{ activeTasks }}</span>
        </button>
      </nav>
      <template v-if="outline">
        <div class="mw-tree-head"><span>当前作品目录</span><ManagementIcon name="folder" :size="14" /></div>
        <div class="mw-outline"><slot name="tree" /></div>
      </template>
      <div class="mw-sidebar-bottom">
        <RouterLink class="mw-reader-link" to="/library" aria-label="进入阅读端"
          ><ManagementIcon name="book" :size="16" /><span>进入阅读端</span><ManagementIcon name="external" :size="14"
        /></RouterLink>
      </div>
    </aside>
    <main class="mw-workspace"><slot /></main>
    <aside v-if="inspector" class="mw-inspector" aria-label="详细信息"><slot name="inspector" /></aside>
    <slot name="overlay" />
  </div>
</template>
