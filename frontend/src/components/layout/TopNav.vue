<template>
  <nav :class="['top-nav', { scrolled: isScrolled }]">
    <div class="nav-left">
      <router-link to="/" class="nav-logo">ComicAtlas</router-link>
      <div class="nav-links">
        <router-link to="/" class="nav-link" active-class="active">首页</router-link>
        <router-link to="/library" class="nav-link" active-class="active">漫画库</router-link>
        <router-link to="/history" class="nav-link" active-class="active">历史</router-link>
        <router-link to="/manage" class="nav-link" active-class="active">管理</router-link>
      </div>
    </div>
    <div class="nav-right">
      <router-link to="/manage/import" class="import-btn">+ 导入</router-link>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'

const isScrolled = ref(false)

function onScroll() {
  isScrolled.value = window.scrollY > 80
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  onScroll()
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
})
</script>

<style scoped>
.top-nav {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--nav-height);
  padding: 0 var(--page-padding);
  background: rgba(20, 20, 20, 0.3);
  border-bottom: 1px solid transparent;
  transition:
    background-color var(--transition-fast),
    border-color var(--transition-fast);
}

.top-nav.scrolled {
  background: var(--bg-primary);
  border-bottom-color: rgba(255, 255, 255, 0.05);
}

.nav-left {
  display: flex;
  align-items: center;
  gap: var(--space-xl);
}

.nav-logo {
  font-size: 22px;
  font-weight: 900;
  color: var(--accent);
  text-decoration: none;
  letter-spacing: -0.03em;
}

.nav-links {
  display: flex;
  align-items: center;
  gap: var(--space-base);
}

.nav-link {
  position: relative;
  padding: 4px 0;
  font-size: 14px;
  font-weight: 400;
  color: var(--text-secondary);
  text-decoration: none;
  transition: color var(--transition-fast);
}

.nav-link:hover {
  color: var(--text-primary);
}

.nav-link.active {
  color: var(--text-primary);
  font-weight: 600;
}

/* 任务红点徽章 */
.task-badge {
  position: absolute;
  top: -6px;
  right: -20px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  background: var(--accent);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
  border-radius: 9px;
  box-shadow: 0 0 0 2px var(--bg-primary);
  animation: badge-pulse 2s ease-in-out infinite;
}

@keyframes badge-pulse {
  0%, 100% { box-shadow: 0 0 0 2px var(--bg-primary), 0 0 0 0 rgba(229, 9, 20, 0.4); }
  50% { box-shadow: 0 0 0 2px var(--bg-primary), 0 0 0 6px rgba(229, 9, 20, 0); }
}

.nav-right {
  display: flex;
  align-items: center;
  gap: var(--space-base);
}

.import-btn {
  padding: 6px 14px;
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  border-radius: var(--radius-sm);
  transition: background var(--transition-fast);
}

.import-btn:hover {
  background: var(--accent-hover);
}

.nav-avatar {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}
</style>
