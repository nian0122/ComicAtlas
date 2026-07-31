<template>
  <header :class="['top-nav', `top-nav--${mobileHeaderKind}`, { scrolled: isScrolled }]">
    <div class="nav-shell">
      <router-link to="/" class="nav-logo desktop-brand" aria-label="ComicAtlas 首页">
        <span class="logo-mark" aria-hidden="true">CA</span>
        <span class="logo-wordmark">COMICATLAS</span>
      </router-link>

      <div class="mobile-header">
        <template v-if="mobileHeaderKind === 'detail'">
          <button type="button" class="mobile-header-action" aria-label="返回" @click="router.back()">
            <el-icon :size="22"><ArrowLeft /></el-icon>
          </button>
          <router-link to="/" class="mobile-detail-brand">COMICATLAS</router-link>
          <button type="button" class="mobile-header-action" aria-label="分享当前漫画" @click="onShare">
            <el-icon :size="21"><Share /></el-icon>
          </button>
        </template>

        <template v-else-if="mobileHeaderKind === 'library'">
          <span class="mobile-header-action" aria-hidden="true">
            <el-icon :size="23"><Menu /></el-icon>
          </span>
          <router-link to="/" class="mobile-wordmark mobile-wordmark--solo">COMICATLAS</router-link>
          <span class="mobile-header-spacer" aria-hidden="true" />
        </template>

        <template v-else-if="mobileHeaderKind === 'history'">
          <router-link to="/" class="mobile-brand" aria-label="ComicAtlas 首页">
            <span class="logo-mark" aria-hidden="true">CA</span>
            <span class="mobile-wordmark">COMICATLAS</span>
          </router-link>
          <button
            type="button"
            class="mobile-header-action"
            :disabled="historyStore.loading"
            aria-label="刷新阅读历史"
            @click="historyStore.refresh()"
          >
            <MaterialSymbolIcon
              name="refresh"
              :class="['mobile-history-refresh-icon', { 'refresh-icon--loading': historyStore.loading }]"
            />
          </button>
        </template>

        <template v-else>
          <router-link to="/" class="mobile-brand" aria-label="ComicAtlas 首页">
            <span class="logo-mark" aria-hidden="true">CA</span>
            <span class="mobile-wordmark">COMICATLAS</span>
          </router-link>
          <span v-if="mobileHeaderKind === 'home'" class="profile-badge" aria-label="当前用户">U</span>
        </template>
      </div>

      <nav class="desktop-nav" aria-label="主要导航">
        <router-link to="/" class="nav-link" exact-active-class="active">首页</router-link>
        <router-link to="/library" class="nav-link" active-class="active">漫画库</router-link>
        <router-link to="/history" class="nav-link" active-class="active">阅读历史</router-link>
        <span class="nav-divider" aria-hidden="true" />
        <router-link to="/manage" class="nav-link nav-link--management" active-class="active">
          仓库管理
        </router-link>
      </nav>

      <router-link to="/manage/import" class="import-btn desktop-action" aria-label="在桌面端导入漫画">
        <el-icon :size="18"><UploadFilled /></el-icon>
        <span>导入漫画</span>
      </router-link>
    </div>
  </header>

  <nav :class="['mobile-tabbar', `mobile-tabbar--${mobileHeaderKind}`]" aria-label="移动端主要导航">
    <router-link to="/" class="mobile-tab" exact-active-class="active">
      <MaterialSymbolIcon name="home" class="mobile-tab-icon" />
      <span>首页</span>
    </router-link>
    <router-link to="/library" class="mobile-tab" active-class="active">
      <MaterialSymbolIcon name="library" class="mobile-tab-icon" />
      <span>漫画库</span>
    </router-link>
    <router-link to="/history" class="mobile-tab" active-class="active">
      <MaterialSymbolIcon name="history" class="mobile-tab-icon" />
      <span>历史</span>
    </router-link>
  </nav>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  Menu,
  Share,
  UploadFilled,
} from '@element-plus/icons-vue'
import MaterialSymbolIcon from '@/components/icons/MaterialSymbolIcon.vue'
import { useHistoryStore } from '@/stores/history-store'

const isScrolled = ref(false)
const route = useRoute()
const router = useRouter()
const historyStore = useHistoryStore()

const mobileHeaderKind = computed(() => {
  if (route.name === 'comic-detail') return 'detail'
  if (route.name === 'library') return 'library'
  if (route.name === 'history') return 'history'
  return 'home'
})

async function shareCurrentPage() {
  try {
    if (navigator.share) {
      await navigator.share({ title: document.title, url: window.location.href })
      return
    }
    await navigator.clipboard.writeText(window.location.href)
    ElMessage.success('链接已复制')
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    if (error instanceof Error) {
      ElMessage.error('暂时无法分享')
      return
    }
    throw error
  }
}

function onShare() {
  void shareCurrentPage()
}

function onScroll() {
  isScrolled.value = window.scrollY > 24
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
  inset: 0 0 auto;
  z-index: var(--z-nav);
  height: var(--nav-height);
  border-bottom: 1px solid transparent;
  background: var(--nav-gradient);
  transition:
    background-color var(--transition-normal),
    border-color var(--transition-normal);
}

.top-nav.scrolled {
  border-bottom-color: var(--border);
  background: var(--nav-solid);
  box-shadow: var(--nav-shadow);
}

.nav-shell {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: var(--space-10);
  width: min(100%, var(--content-max));
  height: 100%;
  margin: 0 auto;
  padding-inline: var(--content-gutter);
}

.nav-logo {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3);
  min-height: 44px;
  color: var(--text-primary);
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
  letter-spacing: -0.04em;
  box-shadow: var(--brand-shadow);
}

.logo-wordmark {
  font-size: 18px;
  font-weight: 800;
  letter-spacing: 0.035em;
}

.desktop-nav {
  display: flex;
  align-items: center;
  gap: var(--space-6);
}

.nav-link {
  position: relative;
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
}

.nav-link::after {
  position: absolute;
  right: 0;
  bottom: 5px;
  left: 0;
  height: 2px;
  content: "";
  background: var(--accent);
  opacity: 0;
  transform: scaleX(0.4);
  transition:
    opacity var(--transition-fast),
    transform var(--transition-normal);
}

.nav-link:hover,
.nav-link.active {
  color: var(--text-primary);
}

.nav-link.active::after {
  opacity: 1;
  transform: scaleX(1);
}

.nav-link--management {
  color: var(--text-secondary);
}

.nav-divider {
  width: 1px;
  height: 18px;
  background: var(--border);
}

.import-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  min-height: 44px;
  padding-inline: var(--space-4);
  border: 0;
  border-radius: var(--radius-pill);
  background: var(--text-primary);
  color: var(--bg-primary);
  font-size: var(--text-sm);
  font-weight: 700;
  transition:
    background-color var(--transition-fast),
    border-color var(--transition-fast),
    transform var(--transition-fast);
}

.import-btn:hover {
  background: var(--accent);
  color: var(--color-on-brand);
  transform: translateY(-2px);
}

.import-btn:active {
  transform: translateY(0);
}

.mobile-tabbar {
  display: none;
}

.mobile-header {
  display: none;
}

@media (max-width: 1024px) {
  .top-nav {
    height: var(--mobile-nav-height);
    border-bottom-color: transparent;
    background: var(--mobile-tabbar-bg);
  }

  .top-nav--home,
  .top-nav--detail {
    background: var(--mobile-header-scrim);
  }

  .top-nav--history {
    border-bottom-color: var(--border);
  }

  .top-nav--home.scrolled,
  .top-nav--detail.scrolled {
    background: var(--mobile-tabbar-bg);
  }

  .nav-shell {
    display: flex;
    width: 100%;
    padding-inline: var(--mobile-page-gutter);
  }

  .desktop-nav,
  .desktop-brand {
    display: none;
  }

  .mobile-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
  }

  .desktop-action {
    display: none;
  }

  .mobile-brand {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    min-height: 44px;
  }

  .mobile-wordmark,
  .mobile-detail-brand {
    color: var(--accent);
    font-size: var(--mobile-brand-font-size);
    font-weight: 800;
    letter-spacing: 0.035em;
  }

  .mobile-wordmark--solo {
    margin-right: auto;
    margin-left: var(--space-2);
  }

  .mobile-detail-brand {
    color: var(--text-primary);
    font-size: var(--mobile-detail-brand-font-size);
  }

  .mobile-header-action {
    display: inline-grid;
    place-items: center;
    width: var(--control-min-size);
    height: var(--control-min-size);
    padding: 0;
    border: 0;
    border-radius: var(--radius-pill);
    background: transparent;
    color: var(--text-primary);
  }

  .mobile-header-action:active {
    background: var(--color-overlay-soft);
  }

  .mobile-header-action:disabled {
    opacity: var(--disabled-opacity);
  }

  .refresh-icon--loading {
    animation: refresh-history var(--motion-standard) linear infinite;
  }

  .mobile-history-refresh-icon {
    width: var(--mobile-history-refresh-icon-size);
    height: var(--mobile-history-refresh-icon-size);
  }

  .mobile-header-spacer {
    width: 132px;
  }

  .profile-badge {
    display: inline-grid;
    place-items: center;
    width: var(--mobile-profile-size);
    height: var(--mobile-profile-size);
    border-radius: 50%;
    background: var(--accent);
    color: var(--color-on-brand);
    font-size: 13px;
    font-weight: 800;
  }

  .mobile-brand .logo-mark {
    width: var(--mobile-brand-mark-size);
    height: var(--mobile-brand-mark-size);
  }

  .top-nav--history .mobile-brand {
    gap: var(--space-3);
  }

  .top-nav--history .mobile-brand .logo-mark {
    width: var(--mobile-history-brand-size);
    height: var(--mobile-history-brand-size);
    border-radius: var(--mobile-history-brand-radius);
  }

  .top-nav--history .mobile-wordmark {
    font-size: var(--mobile-history-brand-font-size);
    letter-spacing: -0.04em;
  }

  .mobile-tabbar {
    position: fixed;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: var(--z-nav);
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    min-height: calc(var(--mobile-tabbar-height) + env(safe-area-inset-bottom));
    padding: 0 var(--space-8) env(safe-area-inset-bottom);
    border-top: 1px solid var(--color-border-faint);
    background: var(--mobile-tabbar-bg);
  }

  .mobile-tab {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-1);
    min-width: 0;
    min-height: var(--mobile-tabbar-height);
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 600;
  }

  .mobile-tab-icon {
    width: var(--mobile-tab-icon-size);
    height: var(--mobile-tab-icon-size);
  }

  .mobile-tab.active {
    color: var(--accent);
  }

  .mobile-tabbar--library .mobile-tab.active {
    color: var(--color-brand-pale);
  }

  .mobile-tabbar--history .mobile-tab.active {
    color: var(--text-primary);
  }

  .mobile-tab.active::after {
    position: absolute;
    right: 26%;
    bottom: 0;
    left: 26%;
    height: 2px;
    border-radius: var(--radius-pill);
    content: "";
    background: var(--accent);
    opacity: 0;
  }

  .mobile-tabbar--history .mobile-tab.active::after {
    right: auto;
    left: 50%;
    width: var(--mobile-history-tab-indicator-width);
    opacity: 1;
    transform: translateX(-50%);
  }

  @keyframes refresh-history {
    to {
      transform: rotate(1turn);
    }
  }
}
</style>
