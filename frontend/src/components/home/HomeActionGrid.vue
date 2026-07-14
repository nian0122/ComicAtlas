<template>
  <section class="home-actions">
    <div class="actions-inner">
      <router-link to="/comics" class="action-card hover-lift">
        <div class="action-icon action-icon--library">
          <el-icon :size="28"><Collection /></el-icon>
        </div>
        <div class="action-body">
          <h3 class="action-title">漫画库</h3>
          <p class="action-desc">浏览全部漫画</p>
        </div>
        <el-icon :size="18" class="action-arrow"><ArrowRight /></el-icon>
      </router-link>

      <router-link to="/import" class="action-card hover-lift">
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
  margin-top: var(--space-2xl);
  padding: 0 var(--page-padding);
}

.actions-inner {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-base);
  max-width: var(--page-width);
  margin: 0 auto;
}

.action-card {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  padding: var(--space-lg);
  background: var(--bg-surface);
  border-radius: var(--card-radius);
  color: var(--text-primary);
  text-decoration: none;
  transition:
    background-color var(--transition-fast),
    transform var(--transition-normal),
    box-shadow var(--transition-normal);
}

.action-card:hover {
  background: var(--bg-secondary);
}

.action-icon {
  flex-shrink: 0;
  width: 52px;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius);
  background: var(--accent-bg);
  color: var(--accent);
}

.action-icon--import {
  background: rgba(70, 211, 105, 0.12);
  color: var(--success);
}

.action-icon--history {
  background: rgba(179, 179, 179, 0.12);
  color: var(--text-secondary);
}

.action-body {
  flex: 1;
  min-width: 0;
}

.action-title {
  margin: 0 0 var(--space-xs);
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
  }
}
</style>
