<template>
  <div class="management-layout">
    <header class="management-header">
      <router-link to="/" class="header-logo">ComicAtlas</router-link>
      <router-link to="/" class="back-link">← 返回阅读</router-link>
    </header>
    <div class="management-body">
      <aside class="management-sidenav">
        <nav class="sidenav-menu">
          <router-link to="/manage/comics" class="sidenav-link" active-class="active">漫画</router-link>
          <router-link to="/manage/import" class="sidenav-link" active-class="active">导入</router-link>
          <router-link to="/manage/storage" class="sidenav-link" active-class="active">存储</router-link>
          <router-link to="/manage/metadata" class="sidenav-link" active-class="active">元数据</router-link>
          <router-link to="/manage/settings" class="sidenav-link" active-class="active">设置</router-link>
        </nav>
      </aside>
      <main class="management-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useImportStore } from '@/stores/import-store'

const importStore = useImportStore()

onMounted(() => {
  // 进入管理模块时启动导入任务轮询
  importStore.bootstrap()
})
</script>

<style scoped>
.management-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--bg-primary);
}

.management-header {
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
  background: var(--bg-primary);
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.header-logo {
  font-size: 22px;
  font-weight: 900;
  color: var(--accent);
  text-decoration: none;
  letter-spacing: -0.03em;
}

.back-link {
  font-size: 14px;
  color: var(--text-secondary);
  text-decoration: none;
}

.back-link:hover {
  color: var(--text-primary);
}

.management-body {
  display: flex;
  flex: 1;
  padding-top: var(--nav-height);
}

.management-sidenav {
  width: 200px;
  flex-shrink: 0;
  padding: var(--page-padding);
  border-right: 1px solid rgba(255, 255, 255, 0.05);
}

.sidenav-menu {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.sidenav-link {
  padding: 10px 14px;
  font-size: 14px;
  color: var(--text-secondary);
  text-decoration: none;
  border-radius: var(--radius-sm);
  transition:
    color var(--transition-fast),
    background var(--transition-fast);
}

.sidenav-link:hover {
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.05);
}

.sidenav-link.active {
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.08);
  font-weight: 600;
}

.management-content {
  flex: 1;
  padding: var(--page-padding);
  overflow: auto;
}
</style>
