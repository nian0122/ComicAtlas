<template>
  <div class="import-page">
    <header class="page-header">
      <h1 class="page-title">导入漫画</h1>
      <p class="page-subtitle">选择来源类型并输入路径，开始你的导入流程</p>
    </header>

    <section class="import-form-card">
      <!-- 来源类型选择 -->
      <div class="form-group">
        <label class="form-label">来源类型</label>
        <div class="source-types">
          <label
            v-for="opt in sourceTypeOptions"
            :key="opt.value"
            class="source-type-radio"
            :class="{ active: sourceType === opt.value }"
          >
            <input
              v-model="sourceType"
              type="radio"
              :value="opt.value"
              class="radio-input"
            />
            <span class="radio-label">
              <span class="radio-title">{{ opt.label }}</span>
              <span class="radio-desc">{{ opt.desc }}</span>
            </span>
          </label>
        </div>
      </div>

      <!-- 路径输入 -->
      <div class="form-group">
        <label class="form-label">路径</label>
        <input
          v-model="sourcePath"
          type="text"
          class="path-input"
          :placeholder="pathPlaceholder"
          @keyup.enter="doImport"
        />
        <p class="form-hint">{{ pathHint }}</p>
      </div>

      <!-- 提交 -->
      <div class="form-actions">
        <button
          class="primary-btn large"
          :disabled="!canSubmit || creating"
          @click="doImport"
        >
          <span v-if="creating" class="spinner-sm" />
          <span>{{ creating ? '创建中...' : '开始导入' }}</span>
        </button>
        <router-link to="/tasks" class="ghost-link">查看任务中心 →</router-link>
      </div>
    </section>

    <!-- 简易近期任务预览（最多 3 条进行中） -->
    <section v-if="store.activeTasks.length > 0" class="recent-section">
      <h2 class="section-title">进行中 ({{ store.activeTasks.length }})</h2>
      <div class="recent-list">
        <div v-for="task in store.activeTasks.slice(0, 3)" :key="task.id" class="recent-item">
          <div class="recent-info">
            <span class="recent-name">{{ taskName(task) }}</span>
            <span class="recent-status">{{ statusLabel(task.status) }}</span>
          </div>
          <div class="recent-bar">
            <div class="recent-bar-fill" :style="{ width: `${task.progress}%` }" />
          </div>
        </div>
      </div>
      <router-link v-if="store.activeTasks.length > 3" to="/tasks" class="more-link">
        查看全部 {{ store.activeTasks.length }} 个任务 →
      </router-link>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useImportStore } from '@/stores/import-store'
import type { ImportTaskVO } from '@/types'

const router = useRouter()
const store = useImportStore()

const sourceType = ref<'ZIP' | 'DIRECTORY'>('ZIP')
const sourcePath = ref('')
const creating = ref(false)

const sourceTypeOptions = [
  { value: 'ZIP' as const, label: 'ZIP 文件', desc: '压缩包，自动解压并解析目录结构' },
  { value: 'DIRECTORY' as const, label: '本地目录', desc: '已存在的漫画目录，原样解析' },
]

const pathPlaceholder = computed(() =>
  sourceType.value === 'ZIP'
    ? 'D:/comics/my_comic.zip'
    : 'D:/comics/my_comic_dir'
)

const pathHint = computed(() =>
  sourceType.value === 'ZIP'
    ? '完整 ZIP 文件路径，包含 .zip 扩展名'
    : '漫画根目录绝对路径，包含章节子目录'
)

const canSubmit = computed(() => sourcePath.value.trim().length > 0)

const STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中',
  PARSING: '解析中',
  IMPORTING: '导入中',
  DOWNLOADING: '下载中',
  EXTRACTING: '解压中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
}

function statusLabel(s: string) {
  return STATUS_LABELS[s] || s
}

function taskName(task: ImportTaskVO): string {
  const path = task.sourcePath || task.sourceRef || ''
  if (!path) return `任务 #${task.id}`
  const parts = path.replace(/\\/g, '/').split('/')
  const last = parts[parts.length - 1]
  return last || path
}

async function doImport() {
  const path = sourcePath.value.trim()
  if (!path) {
    ElMessage.warning('请输入路径')
    return
  }
  creating.value = true
  try {
    const task = await store.create(sourceType.value, path)
    ElMessage.success(`导入任务已创建：${taskName(task)}`)
    sourcePath.value = ''
    // 工作流闭环：创建后直接跳到任务中心观察进度
    router.push('/tasks')
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '创建导入任务失败')
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.import-page {
  max-width: 720px;
  margin: 0 auto;
  padding: var(--space-xl) var(--space-lg) var(--space-3xl);
  background: var(--bg-primary);
  min-height: 100%;
}

.page-header {
  margin-bottom: var(--space-xl);
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 8px;
}

.page-subtitle {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
}

/* Form card */
.import-form-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-xl);
  margin-bottom: var(--space-2xl);
}

.form-group {
  margin-bottom: var(--space-xl);
}

.form-group:last-of-type {
  margin-bottom: var(--space-lg);
}

.form-label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: var(--space-sm);
}

/* Source type radio cards */
.source-types {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-base);
}

.source-type-radio {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm);
  padding: var(--space-base);
  background: var(--bg-primary);
  border: 2px solid var(--border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all 150ms ease;
}

.source-type-radio:hover {
  border-color: var(--border-strong);
}

.source-type-radio.active {
  border-color: var(--accent);
  background: var(--accent-bg);
}

.radio-input {
  margin-top: 3px;
  accent-color: var(--accent);
}

.radio-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.radio-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.radio-desc {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.4;
}

/* Path input */
.path-input {
  width: 100%;
  padding: 12px 16px;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 14px;
  font-family: var(--mono);
  transition: border-color 150ms ease;
  box-sizing: border-box;
}

.path-input:focus {
  outline: none;
  border-color: var(--accent);
}

.path-input::placeholder {
  color: var(--text-muted);
}

.form-hint {
  font-size: 12px;
  color: var(--text-muted);
  margin: 6px 0 0;
}

/* Form actions */
.form-actions {
  display: flex;
  align-items: center;
  gap: var(--space-lg);
  margin-top: var(--space-lg);
}

.primary-btn {
  padding: 8px 16px;
  background: var(--accent);
  color: var(--text-primary);
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 150ms ease;
}

.primary-btn:hover {
  background: var(--accent-hover);
}

.primary-btn.large {
  font-size: 15px;
  padding: 12px 28px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.primary-btn:disabled {
  background: var(--text-muted);
  cursor: not-allowed;
}

.spinner-sm {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.ghost-link {
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 14px;
  transition: color 150ms ease;
}

.ghost-link:hover {
  color: var(--text-primary);
}

/* Recent tasks preview */
.recent-section {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg) var(--space-xl);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 var(--space-base);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.recent-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-base);
}

.recent-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.recent-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.recent-name {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 80%;
}

.recent-status {
  font-size: 12px;
  color: var(--text-secondary);
}

.recent-bar {
  height: 3px;
  background: var(--border);
  border-radius: var(--radius-pill);
  overflow: hidden;
}

.recent-bar-fill {
  height: 100%;
  background: var(--accent);
  border-radius: var(--radius-pill);
  transition: width 300ms ease;
}

.more-link {
  display: inline-block;
  margin-top: var(--space-base);
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 13px;
  transition: color 150ms ease;
}

.more-link:hover {
  color: var(--text-primary);
}

/* Shared */
@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 640px) {
  .source-types {
    grid-template-columns: 1fr;
  }
  .form-actions {
    flex-direction: column;
    align-items: stretch;
  }
  .ghost-link {
    text-align: center;
  }
}
</style>
