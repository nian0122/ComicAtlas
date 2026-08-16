<template>
  <div class="reading-layout">
    <TopNav />
    <main :class="['main-content', routeClass]">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TopNav from '@/components/layout/TopNav.vue'

const route = useRoute()
const routeClass = computed(() => `route-${String(route.name ?? 'unknown')}`)
</script>

<style scoped>
.reading-layout {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--bg-primary);
}

.main-content {
  flex: 1;
  min-width: 0;
  padding: var(--nav-height) var(--content-gutter) var(--space-10);
}

@media (max-width: 1024px) {
  .reading-layout {
    background: var(--mobile-canvas);
  }

  .main-content {
    padding-top: var(--mobile-nav-height);
    padding-right: var(--mobile-page-gutter);
    padding-bottom: calc(
      var(--mobile-tabbar-height) + var(--space-8) + env(safe-area-inset-bottom)
    );
    padding-left: var(--mobile-page-gutter);
  }

  .main-content.route-home,
  .main-content.route-comic-detail {
    padding-top: 0;
    padding-right: 0;
    padding-left: 0;
  }

  /* 历史页移动端使用 page-mode，跟随页面滚动以支持 Safari 地址栏收缩。 */
  .main-content.route-history {
    padding-top: var(--mobile-nav-height);
    padding-right: var(--mobile-page-gutter);
    padding-bottom: calc(
      var(--mobile-tabbar-height) + var(--space-8) + env(safe-area-inset-bottom)
    );
    padding-left: var(--mobile-page-gutter);
  }

}

@media (min-width: 1025px) {
  .main-content.route-history {
    padding-bottom: 0;
  }
}
</style>
