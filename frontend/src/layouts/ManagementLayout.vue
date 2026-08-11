<template>
  <div class="management-layout">
    <header class="management-header">
      <router-link to="/" class="header-logo" aria-label="返回 ComicAtlas 阅读端">
        <span>ComicAtlas</span>
      </router-link>
      <nav class="management-topnav" aria-label="主导航">
        <router-link to="/">首页</router-link>
        <router-link to="/library">漫画库</router-link>
        <router-link to="/history">阅读历史</router-link>
        <router-link to="/manage" class="active">管理</router-link>
      </nav>
      <div class="header-context">
        <router-link to="/manage/import" class="header-import"><el-icon :size="16"><UploadFilled /></el-icon>导入</router-link>
        <router-link to="/" class="profile-button" aria-label="返回阅读端"><el-icon :size="18"><User /></el-icon></router-link>
      </div>
    </header>

    <div class="management-body">
      <aside class="management-sidenav">
        <div class="sidenav-brand">
          <strong>Management</strong>
          <span>Private Console</span>
        </div>
        <router-link to="/manage/import" class="new-import-link"><el-icon :size="18"><Plus /></el-icon>新建导入</router-link>
        <nav class="sidenav-menu" aria-label="管理导航">
          <router-link to="/manage" class="sidenav-link" exact-active-class="active">
            <el-icon :size="18"><HomeFilled /></el-icon>
            <span>仓库控制台</span>
          </router-link>
          <router-link to="/manage/status" class="sidenav-link" active-class="active">
            <el-icon :size="18"><DataAnalysis /></el-icon>
            <span>漫画状态</span>
          </router-link>
          <router-link to="/manage/operations" class="sidenav-link" active-class="active">
            <el-icon :size="18"><Operation /></el-icon>
            <span>漫画操作台</span>
          </router-link>
          <router-link to="/manage/trash" class="sidenav-link" active-class="active">
            <el-icon :size="18"><Delete /></el-icon>
            <span>回收站</span>
          </router-link>
          <router-link to="/manage/tasks" class="sidenav-link" active-class="active">
            <el-icon :size="18"><List /></el-icon>
            <span>统一管理任务</span>
          </router-link>
          <router-link to="/manage/upload" class="sidenav-link" active-class="active">
            <el-icon :size="18"><UploadFilled /></el-icon>
            <span>媒体上传</span>
          </router-link>
          <router-link to="/manage/structure" class="sidenav-link" active-class="active">
            <el-icon :size="18"><Share /></el-icon>
            <span>目录与媒体结构</span>
          </router-link>
          <router-link to="/manage/comics" class="sidenav-link" active-class="active">
            <el-icon :size="18"><Collection /></el-icon>
            <span>漫画信息编辑</span>
          </router-link>

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
            <span>导入任务</span>
          </router-link>

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

          <router-link to="/manage/settings" class="sidenav-link" active-class="active">
            <el-icon :size="18"><Setting /></el-icon>
            <span>设置</span>
          </router-link>
        </nav>

        <div class="sidenav-footer">
          <span><el-icon :size="18"><QuestionFilled /></el-icon>支持</span>
          <span><el-icon :size="18"><InfoFilled /></el-icon>系统</span>
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
  Coin,
  Collection,
  DataAnalysis,
  Delete,
  HomeFilled,
  List,
  Operation,
  Share,
  Setting,
  Tickets,
  UploadFilled,
  WarningFilled,
  Plus,
  User,
  QuestionFilled,
  InfoFilled,
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
  border-bottom: 1px solid var(--color-border-faint);
  background: var(--color-canvas);
}

.header-logo {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3);
  min-height: 44px;
  color: var(--text-primary);
  font-size: 17px;
  font-weight: 800;
  letter-spacing: 0.035em;
}

.logo-mark {
  display: inline-grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-xs);
  background: var(--accent);
  color: var(--color-on-brand);
  font-family: var(--font-ui);
  font-size: 10px;
  font-weight: 900;
}

.console-label {
  padding-left: var(--space-3);
  border-left: 1px solid var(--border-strong);
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.04em;
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
  font-weight: 600;
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
  border-right: 1px solid var(--color-border-faint);
  background: var(--bg-secondary);
}

.sidenav-menu {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-height: 0;
  overflow-y: auto;
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
  border-radius: var(--radius-md);
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
  left: -16px;
  width: 3px;
  content: "";
  background: var(--accent);
  opacity: 0;
}

.sidenav-link:hover {
  background: var(--bg-surface);
  color: var(--text-primary);
}

.sidenav-link.active {
  background: linear-gradient(90deg, var(--accent-bg), var(--surface-highlight));
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
  letter-spacing: 0.06em;
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
  overflow-y: auto;
  overflow-x: hidden;
  background:
    radial-gradient(circle at 100% 0, var(--accent-bg), transparent 26rem),
    var(--bg-primary);
}

/* Stitch 管理端：编辑台式布局，沿用阅读端同一组影院色阶。 */
.management-layout {
  grid-template-rows: var(--management-header-height) minmax(0, 1fr);
}

.management-header {
  padding-inline: var(--space-8);
  border-bottom-color: var(--border);
  background: var(--bg-primary);
}

.header-logo {
  font-size: 28px;
  letter-spacing: -0.04em;
}

.management-topnav {
  display: flex;
  align-items: center;
  align-self: stretch;
  gap: var(--space-10);
}

.management-topnav a {
  position: relative;
  display: inline-flex;
  align-items: center;
  min-height: 100%;
  color: var(--text-secondary);
  font-size: var(--text-md);
}

.management-topnav a:hover,
.management-topnav a.active {
  color: var(--color-brand-pale);
}

.management-topnav a.active::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: var(--color-brand-pale);
  content: "";
}

.header-context {
  gap: var(--space-4);
}

.header-import {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 44px;
  padding-inline: var(--space-5);
  border-radius: var(--radius-xs);
  background: var(--color-brand-pale);
  color: var(--color-canvas);
  font-size: var(--text-sm);
  font-weight: 700;
}

.profile-button {
  display: inline-grid;
  place-items: center;
  width: 44px;
  height: 44px;
  color: var(--text-secondary);
}

.management-body {
  grid-template-columns: var(--management-sidebar-width) minmax(0, 1fr);
}

.management-sidenav {
  padding: var(--space-6) var(--space-5);
  border-right-color: var(--border);
  background: var(--bg-secondary);
}

.sidenav-brand {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-2) var(--space-6);
}

.sidenav-brand strong {
  color: var(--text-primary);
  font-size: var(--text-lg);
}

.sidenav-brand span {
  color: var(--text-muted);
  font-size: var(--text-sm);
}

.new-import-link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  min-height: 48px;
  margin-bottom: var(--space-6);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-xs);
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-weight: 700;
}

.new-import-link:hover {
  border-color: var(--color-brand-pale);
  color: var(--color-brand-pale);
}

.sidenav-menu {
  gap: var(--space-2);
}

.sidenav-label {
  display: none;
}

.sidenav-link {
  min-height: 48px;
  padding-inline: var(--space-3);
  border-radius: var(--radius-xs);
  font-size: var(--text-md);
}

.sidenav-link::before {
  top: 0;
  bottom: 0;
  left: calc(-1 * var(--space-5));
  width: 2px;
}

.sidenav-link.active {
  background: var(--surface-highlight);
  color: var(--color-brand-pale);
}

.sidenav-footer {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-2);
  font-size: var(--text-sm);
}

.sidenav-footer span {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  color: var(--text-muted);
}

.archive-line {
  display: none;
}

.management-content {
  padding: var(--space-10) clamp(32px, 4vw, 64px);
  background: var(--bg-primary);
}

.management-content > * {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
}

.management-content :deep(.page-header) {
  padding-bottom: var(--space-8);
  margin-bottom: var(--space-8);
  border-bottom: 1px solid var(--border);
}

.management-content :deep(.page-title) {
  color: var(--text-primary);
  font-size: clamp(2rem, 3.3vw, 3rem);
  letter-spacing: -0.04em;
}

.management-content :deep(.page-subtitle),
.management-content :deep(.section-desc) {
  color: var(--text-muted);
  font-size: var(--text-lg);
}

.management-content :deep(.settings-card),
.management-content :deep(.import-form-card),
.management-content :deep(.batch-panel),
.management-content :deep(.recent-section),
.management-content :deep(.comic-table-section),
.management-content :deep(.task-card),
.management-content :deep(.settings-card),
.management-content :deep(.storage-summary),
.management-content :deep(.metadata-card) {
  border-color: var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
}

.management-content :deep(.primary-btn),
.management-content :deep(.el-button--primary) {
  border-radius: var(--radius-xs);
  background: var(--color-brand-pale);
  color: var(--color-canvas);
  font-weight: 700;
}

@media (max-width: 900px) {
  .management-topnav {
    display: none;
  }

  .management-body {
    grid-template-columns: 1fr;
  }

  .management-sidenav {
    display: none;
  }
}

@media (max-width: 480px) {
  .management-header {
    padding-inline: var(--space-5);
  }

  .header-logo {
    font-size: 24px;
  }

  .header-context {
    gap: var(--space-2);
  }

  .header-import {
    padding-inline: var(--space-4);
  }

  .profile-button {
    display: none;
  }
}
</style>
