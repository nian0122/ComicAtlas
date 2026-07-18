<template>
  <div class="intercept-page">
    <div class="intercept-content">
      <span class="intercept-icon" role="img" aria-label="移动设备">📱</span>
      <h1 class="intercept-title">管理功能面向桌面端设计</h1>
      <p class="intercept-body">
        阅读功能支持手机和平板。管理功能为了更高的编辑效率，仅支持桌面浏览器。请使用电脑访问管理后台。
      </p>
      <button class="intercept-button" type="button" @click="goHome">回到阅读首页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
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
  z-index: 1000;
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
  max-width: 420px;
  text-align: center;
}

.intercept-icon {
  font-size: 64px;
  line-height: 1;
  margin-bottom: var(--space-lg);
}

.intercept-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-md);
}

.intercept-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-secondary);
  margin: 0 0 var(--space-xl);
}

.intercept-button {
  padding: 10px 28px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
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
