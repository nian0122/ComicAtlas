<template>
  <div class="settings-page">
    <header class="page-header">
      <h1 class="page-title">设置</h1>
    </header>

    <section class="settings-card">
      <h2 class="section-title">阅读器</h2>
      <div class="setting-row">
        <span class="setting-label">默认图片质量</span>
        <el-select v-model="quality" disabled style="width: 180px">
          <el-option label="自动" value="auto" />
          <el-option label="高清" value="hq" />
          <el-option label="低清" value="lq" />
        </el-select>
      </div>
      <div class="setting-row">
        <span class="setting-label">预加载</span>
        <el-switch v-model="preload" disabled />
      </div>
    </section>

    <section class="settings-card">
      <h2 class="section-title">存储</h2>
      <div class="setting-row">
        <span class="setting-label">存储根目录</span>
        <el-input v-model="storageRoot" disabled style="width: 320px" />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const quality = ref('auto')
const preload = ref(true)
const storageRoot = ref('D:/manga')
</script>

<style scoped>
.settings-page {
  max-width: 720px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-xl);
}

.settings-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
  margin-bottom: var(--space-lg);
}

.section-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-base);
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-sm) 0;
  border-bottom: 1px solid var(--border);
}

.setting-row:last-child {
  border-bottom: none;
}

.setting-label {
  font-size: 14px;
  color: var(--text-secondary);
}
</style>
