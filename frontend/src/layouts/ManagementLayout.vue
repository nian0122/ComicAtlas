<template>
  <div class="management-layout">
    <header class="management-header">
      <router-link to="/" class="header-logo" aria-label="返回 ComicAtlas 阅读端">
        <span class="logo-mark" aria-hidden="true">CA</span>
        <span>ComicAtlas</span>
      </router-link>
      <div class="header-context">
        <span>管理工作台</span>
        <router-link to="/" class="back-link">
          <el-icon :size="16"><Back /></el-icon>
          返回阅读
        </router-link>
      </div>
    </header>

    <div class="management-body">
      <aside class="management-sidenav">
        <nav class="sidenav-menu" aria-label="管理导航">
          <p class="sidenav-label">内容</p>
          <router-link to="/manage/comics" class="sidenav-link" active-class="active">
            <el-icon :size="18"><Collection /></el-icon>
            <span>漫画</span>
          </router-link>

          <p class="sidenav-label">导入</p>
          <router-link to="/manage/import" class="sidenav-link" active-class="active">
            <el-icon :size="18"><UploadFilled /></el-icon>
            <span>新建导入</span>
          </router-link>
          <router-link
            to="/manage/import/tasks"
            class="sidenav-link"
            active-class="active"
          >
            <el-icon :size="18"><List /></el-icon>
            <span>任务中心</span>
          </router-link>

          <p class="sidenav-label">维护</p>
          <router-link to="/manage/storage" class="sidenav-link" active-class="active">
            <el-icon :size="18"><Coin /></el-icon>
            <span>存储</span>
          </router-link>
          <router-link to="/manage/metadata" class="sidenav-link" active-class="active">
            <el-icon :size="18"><Tickets /></el-icon>
            <span>元数据</span>
          </router-link>
          <router-link to="/manage/dlq" class="sidenav-link" active-class="active">
            <el-icon :size="18"><WarningFilled /></el-icon>
            <span>死信队列</span>
          </router-link>

          <p class="sidenav-label">偏好</p>
          <router-link to="/manage/settings" class="sidenav-link" active-class="active">
            <el-icon :size="18"><Setting /></el-icon>
            <span>设置</span>
          </router-link>
        </nav>

        <div class="sidenav-footer">
          <span class="archive-line" aria-hidden="true" />
          <p>PERSONAL ARCHIVE</p>
          <span>Midnight Index</span>
        </div>
      </aside>

      <main class="management-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import {
  Back,
  Coin,
  Collection,
  List,
  Setting,
  Tickets,
  UploadFilled,
  WarningFilled,
} from '@element-plus/icons-vue'
import { useImportStore } from '@/stores/management/import'

const importStore = useImportStore()

onMounted(() => {
  importStore.bootstrap()
})
</script>

<style scoped>
.management-layout {
  display: grid;
  grid-template-rows: var(--nav-height) minmax(0, 1fr);
  height: 100dvh;
  overflow: hidden;
  background: var(--bg-primary);
}

.management-header {
  z-index: var(--z-nav);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-inline: var(--content-gutter);
  border-bottom: 1px solid var(--border);
  background: var(--bg-secondary);
}

.header-logo {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3);
  min-height: 44px;
  color: var(--text-primary);
  font-family: var(--font-editorial);
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.035em;
}

.logo-mark {
  display: inline-grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: var(--color-on-brand);
  font-family: var(--font-ui);
  font-size: 11px;
  font-weight: 900;
}

.header-context {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  color: var(--text-muted);
  font-size: var(--text-xs);
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 44px;
  color: var(--text-secondary);
  font-size: var(--text-sm);
}

.management-body {
  display: grid;
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
  min-height: 0;
}

.management-sidenav {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: var(--space-6) var(--space-4);
  border-right: 1px solid var(--border);
  background: var(--bg-secondary);
}

.sidenav-menu {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.sidenav-label {
  margin: var(--space-5) var(--space-3) var(--space-2);
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.sidenav-label:first-child {
  margin-top: 0;
}

.sidenav-link {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-height: var(--control-min-size);
  padding-inline: var(--space-3);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  transition:
    color var(--transition-fast),
    background-color var(--transition-fast);
}

.sidenav-link::before {
  position: absolute;
  top: 9px;
  bottom: 9px;
  left: -17px;
  width: 2px;
  content: "";
  background: var(--accent);
  opacity: 0;
}

.sidenav-link:hover {
  background: var(--bg-surface);
  color: var(--text-primary);
}

.sidenav-link.active {
  background: var(--surface-highlight);
  color: var(--text-primary);
}

.sidenav-link.active::before {
  opacity: 1;
}

.sidenav-footer {
  display: grid;
  grid-template-columns: 2px 1fr;
  column-gap: var(--space-3);
  margin-top: auto;
  padding: var(--space-4);
  color: var(--text-muted);
  font-size: 10px;
  letter-spacing: 0.08em;
}

.sidenav-footer p {
  color: var(--text-secondary);
  font-weight: 700;
}

.sidenav-footer span:last-child {
  grid-column: 2;
}

.archive-line {
  grid-row: 1 / 3;
  background: var(--accent);
}

.management-content {
  min-width: 0;
  min-height: 0;
  padding: var(--space-8) var(--content-gutter);
  overflow: auto;
}
</style>
