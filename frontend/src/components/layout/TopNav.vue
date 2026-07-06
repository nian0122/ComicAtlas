<template>
  <nav class="top-nav">
    <div class="nav-left">
      <router-link to="/comics" class="nav-logo">ComicAtlas</router-link>
    </div>
    <div class="nav-center" />
    <div class="nav-right">
      <router-link to="/comics" class="nav-link" active-class="active">漫画库</router-link>
      <router-link to="/tasks" class="nav-link" active-class="active">任务</router-link>
      <router-link to="/history" class="nav-link" active-class="active">历史</router-link>
      <router-link to="/dashboard" class="nav-link" active-class="active">仪表盘</router-link>
    </div>
  </nav>
</template>

<style scoped>
.top-nav {
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 24px;
  background: #1a1a1a;
  border-bottom: 1px solid #2a2a2a;
  flex-shrink: 0;
}
.nav-left { display: flex; align-items: center; }
.nav-logo {
  font-size: 18px; font-weight: 700; color: #e5e7eb; text-decoration: none;
}
.nav-center { flex: 1; }
.nav-right { display: flex; gap: 4px; }
.nav-link {
  padding: 6px 14px; font-size: 14px; color: #9ca3af; text-decoration: none;
  border-radius: 6px; transition: background .15s, color .15s;
}
.nav-link:hover { color: #e5e7eb; background: rgba(255,255,255,.06); }
.nav-link.active { color: #6366f1; background: rgba(99,102,241,.12); }
</style>
