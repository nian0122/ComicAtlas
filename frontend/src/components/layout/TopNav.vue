<template>
  <nav class="top-nav">
    <div class="nav-left">
      <router-link to="/comics" class="nav-logo">ComicAtlas</router-link>
      <div class="nav-links">
        <router-link to="/comics" class="nav-link" active-class="active">漫画库</router-link>
        <router-link to="/tasks" class="nav-link task-link" active-class="active">
          任务
          <span v-if="importStore.activeCount > 0" class="task-badge">
            {{ importStore.activeCount > 99 ? '99+' : importStore.activeCount }}
          </span>
        </router-link>
        <router-link to="/history" class="nav-link" active-class="active">历史</router-link>
        <router-link to="/dashboard" class="nav-link" active-class="active">仪表盘</router-link>
      </div>
    </div>
    <div class="nav-right">
      <router-link to="/import" class="import-btn">+ 导入</router-link>
      <span class="nav-avatar">U</span>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue'
import { useImportStore } from '@/stores/import-store'

const importStore = useImportStore()

onMounted(() => {
  // 全局任务状态 bootstrap：拉一次列表，如有进行中任务自动启动轮询
  // 任何页面进入都会经过 AppLayout → TopNav，因此这里是最合适的启动点
  importStore.bootstrap()
})

onBeforeUnmount(() => {
  // 不停止轮询：单例 store 生命周期与 app 等长
  // 轮询在没有进行中任务时会自动停止
})
</script>

<style scoped>
.top-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 var(--space-lg);
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
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
  color: var(--text);
  text-decoration: none;
  transition: color 150ms ease;
}

.nav-link:hover {
  color: var(--text-h);
}

.nav-link.active {
  color: var(--text-h);
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
  box-shadow: 0 0 0 2px var(--surface);
  animation: badge-pulse 2s ease-in-out infinite;
}

@keyframes badge-pulse {
  0%, 100% { box-shadow: 0 0 0 2px var(--surface), 0 0 0 0 rgba(229, 9, 20, 0.4); }
  50% { box-shadow: 0 0 0 2px var(--surface), 0 0 0 6px rgba(229, 9, 20, 0); }
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
  transition: background 150ms ease;
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
