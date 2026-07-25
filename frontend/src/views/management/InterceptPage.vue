<template>
  <div class="intercept-page">
    <div class="intercept-content">
      <div class="intercept-icon" aria-hidden="true">
        <el-icon><Monitor /></el-icon>
      </div>
      <h1 class="intercept-title">管理功能面向桌面端设计</h1>
      <p class="intercept-body">
        阅读功能支持手机和平板。管理功能为了更高的编辑效率，仅支持桌面浏览器。请使用电脑访问<span class="no-break">管理后台</span>。
      </p>
      <button class="intercept-button" type="button" @click="goHome">回到阅读首页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Monitor } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  router.push('/')
}
</script>

<style scoped>
.intercept-page {
  position: fixed;
  inset: 0;
  z-index: var(--z-dialog);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-lg);
  background: var(--bg-primary);
}

.intercept-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  max-width: var(--content-narrow);
  text-align: center;
}

.intercept-icon {
  display: grid;
  place-items: center;
  width: var(--space-16);
  height: var(--space-16);
  margin-bottom: var(--space-lg);
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-lg);
  background: var(--accent-bg);
  color: var(--accent-hover);
}

.intercept-icon :deep(.el-icon) {
  font-size: var(--text-section);
}

.intercept-title {
  font-size: var(--text-section);
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-md);
}

.intercept-body {
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--text-secondary);
  margin: 0 0 var(--space-xl);
}

.no-break {
  white-space: nowrap;
}

.intercept-button {
  min-height: var(--control-min-size);
  padding: var(--space-3) var(--space-8);
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-on-brand);
  background: var(--accent);
  border: none;
  border-radius: var(--radius);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.intercept-button:hover {
  background: var(--accent-hover);
}
</style>
