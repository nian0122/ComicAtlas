<script setup lang="ts">
import type { AnalysisResult } from '../model/useAiAnalysisTask'

defineProps<{ result: AnalysisResult; showIdentity?: boolean }>()
</script>

<template>
  <div class="analysis-result">
    <div class="result-layout" :class="{ 'result-layout--identity': showIdentity }">
      <div v-if="showIdentity" class="identity-card">
        <span class="result-label">作品候选</span>
        <strong>{{ result.titleCandidate || '暂未识别' }}</strong>
        <small>{{ result.authorCandidate || '作者未知' }}</small>
      </div>
      <div class="description-card">
        <span class="result-label">抽样简介</span>
        <p>{{ result.description || '模型没有返回简介。' }}</p>
      </div>
    </div>
    <div v-if="!showIdentity" class="category">
      <span class="result-label">分类</span><strong>{{ result.categoryCandidate || '未匹配现有分类' }}</strong>
    </div>
    <div class="tags-row">
      <span class="result-label">标签</span>
      <div>
        <el-tag v-for="tag in result.tags || []" :key="tag" effect="plain">{{ tag }}</el-tag>
        <span v-if="!result.tags?.length" class="muted">暂无标签</span>
      </div>
    </div>
    <el-alert
      v-if="result.warnings?.length"
      :title="result.warnings.join('；')"
      type="info"
      :closable="false"
      show-icon
    />
  </div>
</template>

<style scoped>
.analysis-result {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
}
.result-layout {
  display: grid;
  gap: var(--space-4);
}
.result-layout--identity {
  grid-template-columns: minmax(180px, 0.8fr) minmax(0, 1.2fr);
}
.identity-card,
.description-card {
  min-width: 0;
  padding: var(--space-4);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
.result-label {
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-weight: 700;
}
.identity-card strong,
.identity-card small {
  display: block;
  margin-top: var(--space-3);
  overflow-wrap: anywhere;
}
.identity-card strong {
  color: var(--text-primary);
  font-size: var(--text-lg);
}
.identity-card small,
.muted {
  color: var(--text-muted);
}
.description-card p {
  margin: var(--space-3) 0 0;
  color: var(--text-secondary);
  line-height: var(--leading-body);
  overflow-wrap: anywhere;
}
.category {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  color: var(--text-primary);
}
.tags-row {
  display: grid;
  gap: var(--space-3);
}
.tags-row > div {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
@media (max-width: 800px) {
  .result-layout--identity {
    grid-template-columns: 1fr;
  }
}
</style>
