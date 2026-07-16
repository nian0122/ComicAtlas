<template>
  <div class="storage-page">
    <header class="page-header">
      <h1 class="page-title">存储管理</h1>
    </header>

    <section class="stat-grid">
      <div class="stat-card">
        <span class="stat-value">-</span>
        <span class="stat-label">总大小</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">-</span>
        <span class="stat-label">HQ 占用</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">-</span>
        <span class="stat-label">LQ 占用</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">-</span>
        <span class="stat-label">缩略图</span>
      </div>
    </section>

    <section class="action-section">
      <h2 class="section-title">操作</h2>
      <div class="action-list">
        <button class="action-btn" disabled>扫描存储</button>
        <button class="action-btn" disabled>恢复孤立文件</button>
        <button class="action-btn danger" disabled>清理未引用文件</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.storage-page {
  max-width: 960px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-xl);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-base);
  margin-bottom: var(--space-2xl);
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-lg);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.stat-value {
  font-size: 24px;
  font-weight: 800;
  color: var(--text-primary);
}

.stat-label {
  font-size: 12px;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.action-section {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
}

.section-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-base);
}

.action-list {
  display: flex;
  gap: var(--space-base);
  flex-wrap: wrap;
}

.action-btn {
  padding: 8px 16px;
  background: var(--bg-primary);
  color: var(--text-primary);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.action-btn:hover:not(:disabled) {
  background: var(--bg-secondary);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-btn.danger {
  color: var(--danger);
  border-color: var(--danger);
}

@media (max-width: 768px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
