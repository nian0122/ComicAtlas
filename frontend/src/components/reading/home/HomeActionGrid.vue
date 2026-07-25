<template>
  <section class="home-actions">
    <div class="actions-inner">
      <router-link to="/library" class="action-card hover-lift">
        <span class="action-index" aria-hidden="true">01</span>
        <div class="action-icon action-icon--library">
          <el-icon :size="28"><Collection /></el-icon>
        </div>
        <div class="action-body">
          <h3 class="action-title">漫画库</h3>
          <p class="action-desc">浏览全部漫画</p>
        </div>
        <el-icon :size="18" class="action-arrow"><ArrowRight /></el-icon>
      </router-link>

      <router-link to="/manage/import" class="action-card hover-lift">
        <span class="action-index" aria-hidden="true">02</span>
        <div class="action-icon action-icon--import">
          <el-icon :size="28"><Download /></el-icon>
        </div>
        <div class="action-body">
          <h3 class="action-title">导入</h3>
          <p class="action-desc">添加新的漫画</p>
        </div>
        <el-icon :size="18" class="action-arrow"><ArrowRight /></el-icon>
      </router-link>

      <router-link to="/history" class="action-card hover-lift">
        <span class="action-index" aria-hidden="true">03</span>
        <div class="action-icon action-icon--history">
          <el-icon :size="28"><Clock /></el-icon>
        </div>
        <div class="action-body">
          <h3 class="action-title">阅读历史</h3>
          <p class="action-desc">继续上次的阅读</p>
        </div>
        <el-icon :size="18" class="action-arrow"><ArrowRight /></el-icon>
      </router-link>
    </div>
  </section>
</template>

<script setup lang="ts">
import { Collection, Download, Clock, ArrowRight } from '@element-plus/icons-vue'
</script>

<style scoped>
.home-actions {
  margin-top: var(--space-12);
}

.actions-inner {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  width: min(100%, var(--content-max));
  border-block: 1px solid var(--border);
  margin: 0 auto;
}

.action-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-4);
  min-width: 0;
  min-height: 112px;
  padding: var(--space-5);
  border-right: 1px solid var(--border);
  background: transparent;
  color: var(--text-primary);
  text-decoration: none;
  transition:
    background-color var(--transition-fast),
    transform var(--transition-fast);
}

.action-card:last-child {
  border-right: 0;
}

.action-card:hover {
  background: var(--bg-secondary);
  color: var(--text-primary);
  transform: translateY(-1px);
}

.action-index {
  position: absolute;
  top: var(--space-2);
  left: var(--space-3);
  color: var(--text-muted);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.08em;
}

.action-icon {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-md);
  background: var(--accent-bg);
  color: var(--accent);
}

.action-icon--import {
  border-color: var(--success);
  background: var(--bg-surface);
  color: var(--success);
}

.action-icon--history {
  border-color: var(--border-strong);
  background: var(--bg-surface);
  color: var(--text-secondary);
}

.action-body {
  flex: 1;
  min-width: 0;
}

.action-title {
  margin: 0 0 var(--space-1);
  font-family: var(--font-editorial);
  font-size: 16px;
  font-weight: 700;
}

.action-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.action-arrow {
  color: var(--text-muted);
  transition: color var(--transition-fast), transform var(--transition-fast);
}

.action-card:hover .action-arrow {
  color: var(--text-primary);
  transform: translateX(2px);
}

@media (max-width: 768px) {
  .actions-inner {
    grid-template-columns: 1fr;
    border-bottom: 0;
  }

  .action-card {
    min-height: 92px;
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }
}
</style>
