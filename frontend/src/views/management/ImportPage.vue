<template>
  <div class="import-page">
    <ManagementPageHeader spaced title="导入漫画" description="选择来源类型并输入路径，开始你的导入流程" />

    <!-- 导入模式切换 -->
    <div class="import-tabs">
      <div class="import-tab" :class="{ active: activeTab === 'single' }" @click="activeTab = 'single'">单个导入</div>
      <div class="import-tab" :class="{ active: activeTab === 'batch' }" @click="activeTab = 'batch'">批量导入</div>
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
            <input v-model="sourceType" type="radio" :value="opt.value" class="radio-input" />
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
        <button class="primary-btn large" :disabled="!canSubmit || creating" @click="doImport">
          <span v-if="creating" class="spinner-sm" />
          <span>{{ creating ? '创建中...' : '开始导入' }}</span>
        </button>
        <router-link to="/manage/tasks" class="ghost-link">查看任务中心 →</router-link>
      </div>
    </section>

    <!-- 批量导入 -->
    <section v-if="activeTab === 'batch'" class="batch-panel">
      <!-- 漫画集根目录输入 -->
      <div class="form-group">
        <label class="form-label">漫画集根目录</label>
        <div class="scan-input-row">
          <input
            v-model="batchParentPath"
            type="text"
            class="path-input"
            placeholder="F:/games/comics/..."
            @keyup.enter="doScan"
          />
          <button class="primary-btn" :disabled="!batchParentPath.trim() || scanning" @click="doScan">
            <span v-if="scanning" class="spinner-sm" />
            <span>{{ scanning ? '扫描中...' : '扫描' }}</span>
          </button>
        </div>
        <p class="form-hint">输入漫画集根目录路径，其直接子目录各是一本候选漫画，自动批量发现并递归预览媒体与警告</p>
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
        <el-empty
          v-else-if="!scanResult || scanResult.items.length === 0"
          description="此漫画集根目录下未发现候选漫画（直接子目录）"
        />

        <!-- 成功 -->
        <div v-else class="scan-results">
          <div class="scan-results-header">
            <span class="check-all-links">
              <a class="link" @click="selectAll">全选</a>
              <span class="link-sep">/</span>
              <a class="link" @click="deselectAll">取消全选</a>
            </span>
            <span class="scan-count">已选 {{ selectedPaths.length }} / {{ importableCount }} 个可导入</span>
          </div>

          <!-- 规范化统计与扫描级警告 -->
          <div class="scan-summary">
            <div v-if="hasPreview" class="scan-stats" aria-label="扫描统计">
              <span class="stat-item"
                >候选 <strong>{{ scanResult.total }}</strong></span
              >
              <span class="stat-item"
                >图片 <strong>{{ totalImageCount }}</strong></span
              >
              <span class="stat-item"
                >视频 <strong>{{ totalVideoCount }}</strong></span
              >
              <span class="stat-item"
                >媒体 <strong>{{ totalMediaCount }}</strong></span
              >
            </div>
            <div v-if="(scanResult.warnings ?? []).length > 0" class="scan-warnings" aria-label="扫描警告">
              <span
                v-for="w in scanResult.warnings"
                :key="`${w.code}-${w.relativePath}`"
                class="warn-chip severity-warning"
              >
                {{ w.message }}
              </span>
            </div>
          </div>

          <div class="scan-items-list">
            <div
              v-for="row in scanItemRows"
              :key="row.item.path"
              class="scan-item"
              :class="{
                selected: selectedPaths.includes(row.item.path),
                blocked: !isImportable(row.item),
              }"
              @click="togglePath(row.item.path)"
            >
              <el-checkbox
                v-model="selectedPaths"
                :value="row.item.path"
                :disabled="!isImportable(row.item)"
                class="scan-checkbox"
                @click.stop
              />
              <div class="scan-item-info">
                <div class="scan-item-header">
                  <span class="scan-item-name">{{ row.item.name }}</span>
                  <span class="scan-item-count">{{ itemStats(row.item, row.preview) }}</span>
                </div>
                <div v-if="nonBlockingWarnings(row.item).length > 0" class="scan-item-warnings">
                  <span
                    v-for="w in nonBlockingWarnings(row.item)"
                    :key="`${w.code}-${w.relativePath}`"
                    class="warn-chip"
                    :class="`severity-${w.severity.toLowerCase()}`"
                  >
                    {{ w.message }}
                  </span>
                </div>
                <div v-if="blockingReason(row.item)" class="blocked-reason">
                  不可导入：{{ blockingReason(row.item) }}
                </div>
                <div v-if="row.preview" class="scan-item-preview">
                  <button
                    class="preview-toggle"
                    :aria-expanded="previewExpanded.has(row.item.path)"
                    @click.stop="togglePreview(row.item.path)"
                  >
                    {{ previewExpanded.has(row.item.path) ? '收起' : '展开' }}规范化预览
                  </button>
                  <div
                    v-if="previewExpanded.has(row.item.path)"
                    class="preview-tree"
                    :aria-label="`${row.item.name} 目录预览`"
                  >
                    <PreviewNode :node="row.preview" />
                  </div>
                </div>
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
              <span>{{ batchCreating ? '导入中...' : `确认导入 ${selectedPaths.length} 项` }}</span>
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
      <router-link v-if="store.activeTasks.length > 3" to="/manage/tasks" class="more-link">
        查看全部 {{ store.activeTasks.length }} 个任务 →
      </router-link>
    </section>
  </div>
</template>

<script setup lang="ts">
import ManagementPageHeader from '@/components/management/ManagementPageHeader.vue'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getApiErrorMessage } from '@/services/http'
import { useImportStore } from '@/features/import/store'
import { useImportScan } from '@/features/import/composables/useImportScan'
import PreviewNode from '@/features/import/components/PreviewNode.vue'
import { useImportPageForm } from './composables/useImportPageForm'

const router = useRouter()
const store = useImportStore()

const { activeTab, sourceType, sourcePath, pathPlaceholder, pathHint, canSubmit, taskName } = useImportPageForm()
const creating = ref(false)

// ——— 批量导入 ———
const batchCreating = ref(false)
const {
  batchParentPath,
  scanning,
  scanResult,
  scanError,
  selectedPaths,
  previewExpanded,
  hasPreview,
  importableCount,
  totalImageCount,
  totalMediaCount,
  totalVideoCount,
  scanItemRows,
  isImportable,
  nonBlockingWarnings,
  blockingReason,
  itemStats,
  togglePreview,
  selectAll,
  deselectAll,
  togglePath,
  doScan,
  resetScan,
} = useImportScan((path) => store.scan(path))

const sourceTypeOptions = [
  { value: 'ZIP' as const, label: 'ZIP 文件', desc: '压缩包，自动解压并解析目录结构' },
  { value: 'CBZ' as const, label: 'CBZ 漫画', desc: '漫画压缩包，自动读取 ComicInfo.xml 元数据' },
  { value: 'DIRECTORY' as const, label: '本地目录', desc: '已存在的漫画目录，原样解析' },
  { value: 'EHENTAI' as const, label: 'E-Hentai 画廊', desc: '输入画廊 URL，后台下载后自动导入' },
]

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
    router.push('/manage/tasks')
  } catch (err: unknown) {
    ElMessage.error(errorMessage(err) || '创建导入任务失败')
  } finally {
    creating.value = false
  }
}

async function doBatchImport() {
  const paths = [...selectedPaths.value]
  if (paths.length === 0) return
  batchCreating.value = true
  try {
    const result = await store.createBatch('DIRECTORY', paths)
    ElMessage.success(`批量导入已创建，共 ${paths.length} 个任务`)
    resetScan()
    router.push(`/manage/tasks?batchId=${result.batchId}`)
  } catch (err: unknown) {
    ElMessage.error(errorMessage(err) || '批量导入失败')
  } finally {
    batchCreating.value = false
  }
}

function errorMessage(error: unknown): string {
  return getApiErrorMessage(error, '')
}
</script>

<style scoped src="@/styles/pages/management/import/form.css"></style>
<style scoped src="@/styles/pages/management/import/scan.css"></style>
