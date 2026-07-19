<template>
  <div class="import-page">
    <header class="page-header">
      <h1 class="page-title">导入漫画</h1>
      <p class="page-subtitle">选择来源类型并输入路径，开始你的导入流程</p>
    </header>

    <!-- 导入模式切换 -->
    <div class="import-tabs">
      <div
        class="import-tab"
        :class="{ active: activeTab === 'single' }"
        @click="activeTab = 'single'"
      >
        单个导入
      </div>
      <div
        class="import-tab"
        :class="{ active: activeTab === 'batch' }"
        @click="activeTab = 'batch'"
      >
        批量导入
      </div>
    </div>

    <!-- 单个导入 -->
    <section v-if="activeTab === 'single'" class="import-form-card">
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
        <router-link to="/manage/import/tasks" class="ghost-link">查看任务中心 →</router-link>
      </div>
    </section>

    <!-- 批量导入 -->
    <section v-if="activeTab === 'batch'" class="batch-panel">
      <!-- 父目录输入 -->
      <div class="form-group">
        <label class="form-label">父目录</label>
        <div class="scan-input-row">
          <input
            v-model="batchParentPath"
            type="text"
            class="path-input"
            placeholder="F:/games/comics/..."
            @keyup.enter="doScan"
          />
          <button
            class="primary-btn"
            :disabled="!batchParentPath.trim() || scanning"
            @click="doScan"
          >
            <span v-if="scanning" class="spinner-sm" />
            <span>{{ scanning ? '扫描中...' : '扫描' }}</span>
          </button>
        </div>
        <p class="form-hint">输入包含多个漫画目录的父文件夹路径，自动发现子目录</p>
      </div>

      <!-- 扫描结果 -->
      <div v-if="scanning || scanResult || scanError" class="scan-result-area">
        <!-- 加载 -->
        <div v-if="scanning" class="scan-loading">
          <span class="spinner" />
          <span>正在扫描...</span>
        </div>

        <!-- 错误 -->
        <el-alert v-else-if="scanError" type="error" :title="scanError" show-icon />

        <!-- 空结果 -->
        <el-empty v-else-if="!scanResult || scanResult.items.length === 0" description="此目录下未发现漫画子目录" />

        <!-- 成功 -->
        <div v-else class="scan-results">
          <div class="scan-results-header">
            <span class="check-all-links">
              <a class="link" @click="selectAll">全选</a>
              <span class="link-sep">/</span>
              <a class="link" @click="deselectAll">取消全选</a>
            </span>
            <span class="scan-count">已选 {{ selectedPaths.length }} / {{ scanResult.total }}</span>
          </div>

          <div class="scan-items-list">
            <div
              v-for="item in scanResult.items"
              :key="item.path"
              class="scan-item"
              :class="{ selected: selectedPaths.includes(item.path) }"
              @click="togglePath(item.path)"
            >
              <el-checkbox
                :model-value="selectedPaths.includes(item.path)"
                :label="item.path"
                class="scan-checkbox"
                @click.stop
              />
              <div class="scan-item-info" @click="togglePath(item.path)">
                <span class="scan-item-name">{{ item.name }}</span>
                <span class="scan-item-count">{{ item.imageCount }} 张</span>
              </div>
            </div>
          </div>

          <div class="scan-actions">
            <button
              class="primary-btn large"
              :disabled="selectedPaths.length === 0"
              :loading="batchCreating"
              @click="doBatchImport"
            >
              <span v-if="batchCreating" class="spinner-sm" />
              <span>{{ batchCreating ? '导入中...' : '确认导入' }}</span>
            </button>
          </div>
        </div>
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
      <router-link v-if="store.activeTasks.length > 3" to="/manage/import/tasks" class="more-link">
        查看全部 {{ store.activeTasks.length }} 个任务 →
      </router-link>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useImportStore } from '@/stores/management/import'
import type { ImportTaskVO, ScanResultVO } from '@/types'

const router = useRouter()
const store = useImportStore()

// ——— Tab ———
const activeTab = ref<'single' | 'batch'>('single')

// ——— 单个导入 ———
const sourceType = ref<'ZIP' | 'DIRECTORY'>('ZIP')
const sourcePath = ref('')
const creating = ref(false)

// ——— 批量导入 ———
const batchParentPath = ref('')
const scanning = ref(false)
const scanResult = ref<ScanResultVO | null>(null)
const scanError = ref('')
const selectedPaths = ref<string[]>([])
const batchCreating = ref(false)

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
    router.push('/manage/import/tasks')
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '创建导入任务失败')
  } finally {
    creating.value = false
  }
}

// ——— 批量导入 ———

function selectAll() {
  if (!scanResult.value) return
  selectedPaths.value = scanResult.value.items.map(i => i.path)
}

function deselectAll() {
  selectedPaths.value = []
}

function togglePath(path: string) {
  const idx = selectedPaths.value.indexOf(path)
  if (idx >= 0) {
    selectedPaths.value.splice(idx, 1)
  } else {
    selectedPaths.value.push(path)
  }
}

async function doScan() {
  const path = batchParentPath.value.trim()
  if (!path) return
  scanning.value = true
  scanResult.value = null
  scanError.value = ''
  selectedPaths.value = []
  try {
    const result = await store.scan(path, 'DIRECTORY')
    scanResult.value = result as ScanResultVO
    if (!result || result.total === 0) {
      scanError.value = ''
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    scanError.value = msg || '扫描目录失败'
  } finally {
    scanning.value = false
  }
}

async function doBatchImport() {
  const paths = [...selectedPaths.value]
  if (paths.length === 0) return
  batchCreating.value = true
  try {
    const result = await store.createBatch('DIRECTORY', paths)
    ElMessage.success(`批量导入已创建，共 ${paths.length} 个任务`)
    batchParentPath.value = ''
    scanResult.value = null
    selectedPaths.value = []
    router.push(`/manage/import/tasks?batchId=${result.batchId}`)
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '批量导入失败')
  } finally {
    batchCreating.value = false
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

/* ——— Tab switch ——— */
.import-tabs {
  display: flex;
  gap: 0;
  margin-bottom: var(--space-xl);
  border-bottom: 2px solid var(--border);
}

.import-tab {
  padding: 10px 24px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  transition: all 150ms ease;
}

.import-tab:hover {
  color: var(--text-primary);
}

.import-tab.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

/* ——— Batch panel ——— */
.batch-panel {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-xl);
  margin-bottom: var(--space-2xl);
}

.scan-input-row {
  display: flex;
  gap: var(--space-base);
}

.scan-input-row .path-input {
  flex: 1;
}

.scan-input-row .primary-btn {
  flex-shrink: 0;
}

/* Scan result area */
.scan-result-area {
  margin-top: var(--space-xl);
}

.scan-loading {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xl);
  color: var(--text-secondary);
  font-size: 14px;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

/* Scan results header */
.scan-results-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-base);
  padding-bottom: var(--space-sm);
  border-bottom: 1px solid var(--border);
}

.check-all-links {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
}

.link {
  color: var(--accent);
  cursor: pointer;
  text-decoration: none;
}

.link:hover {
  text-decoration: underline;
}

.link-sep {
  color: var(--text-muted);
  margin: 0 2px;
}

.scan-count {
  font-size: 13px;
  color: var(--text-muted);
}

/* Scan items list */
.scan-items-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 400px;
  overflow-y: auto;
}

.scan-item {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-base);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 150ms ease;
}

.scan-item:hover {
  border-color: var(--border-strong);
  background: var(--bg-primary);
}

.scan-item.selected {
  border-color: var(--accent);
  background: var(--accent-bg);
}

.scan-checkbox {
  flex-shrink: 0;
  pointer-events: auto;
}

.scan-item-info {
  flex: 1;
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-width: 0;
}

.scan-item-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scan-item-count {
  font-size: 12px;
  color: var(--text-muted);
  flex-shrink: 0;
  margin-left: var(--space-base);
}

/* Scan actions */
.scan-actions {
  margin-top: var(--space-lg);
  display: flex;
  gap: var(--space-lg);
  align-items: center;
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
