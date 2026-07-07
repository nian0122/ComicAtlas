<template>
  <nav class="top-nav">
    <div class="nav-left">
      <router-link to="/comics" class="nav-logo">ComicAtlas</router-link>
      <div class="nav-links">
        <router-link to="/comics" class="nav-link" active-class="active">漫画库</router-link>
        <router-link to="/tasks" class="nav-link" active-class="active">任务</router-link>
        <router-link to="/history" class="nav-link" active-class="active">历史</router-link>
        <router-link to="/dashboard" class="nav-link" active-class="active">仪表盘</router-link>
      </div>
    </div>
    <div class="nav-right">
      <span class="nav-avatar">U</span>
    </div>
  </nav>
</template>

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

.nav-right {
  display: flex;
  align-items: center;
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
