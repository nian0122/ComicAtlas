<template>
  <header :class="['top-nav', { scrolled: isScrolled }]">
    <div class="nav-shell">
      <router-link to="/" class="nav-logo" aria-label="ComicAtlas 首页">
        <span class="logo-mark" aria-hidden="true">CA</span>
        <span class="logo-wordmark">COMICATLAS</span>
      </router-link>

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
      <router-link to="/library" class="mobile-search" aria-label="搜索漫画">
        <el-icon :size="22"><Search /></el-icon>
      </router-link>
    </div>
  </header>

  <nav class="mobile-tabbar" aria-label="移动端主要导航">
    <router-link to="/" class="mobile-tab" exact-active-class="active">
      <el-icon :size="21"><House /></el-icon>
      <span>首页</span>
    </router-link>
    <router-link to="/library" class="mobile-tab" active-class="active">
      <el-icon :size="21"><Collection /></el-icon>
      <span>漫画库</span>
    </router-link>
    <router-link to="/history" class="mobile-tab" active-class="active">
      <el-icon :size="21"><Clock /></el-icon>
      <span>历史</span>
    </router-link>
  </nav>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Clock, Collection, House, Search, UploadFilled } from '@element-plus/icons-vue'

const isScrolled = ref(false)

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

.mobile-search {
  display: none;
}

@media (max-width: 768px) {
  .top-nav {
    border-bottom-color: var(--border);
    background: var(--nav-solid);
  }

  .nav-shell {
    display: flex;
    justify-content: space-between;
    gap: var(--space-4);
  }

  .desktop-nav {
    display: none;
  }

  .logo-wordmark {
    font-size: 16px;
  }

  .desktop-action {
    display: none;
  }

  .mobile-search {
    display: inline-grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: 50%;
    color: var(--text-primary);
  }

  .mobile-tabbar {
    position: fixed;
    right: var(--space-3);
    bottom: calc(var(--space-3) + env(safe-area-inset-bottom));
    left: var(--space-3);
    z-index: var(--z-nav);
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    min-height: var(--mobile-tabbar-height);
    padding: var(--space-2);
    border: 1px solid var(--color-border-faint);
    border-radius: var(--radius-pill);
    background: var(--mobile-tabbar-bg);
    box-shadow: var(--shadow-overlay);
  }

  .mobile-tab {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-1);
    min-width: 0;
    min-height: 52px;
    border-radius: var(--radius-md);
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 600;
  }

  .mobile-tab.active {
    background: var(--surface-highlight);
    color: var(--text-primary);
  }

  .mobile-tab.active::after {
    position: absolute;
    right: 28%;
    bottom: 4px;
    left: 28%;
    height: 2px;
    border-radius: var(--radius-pill);
    content: "";
    background: var(--accent);
  }
}
</style>
