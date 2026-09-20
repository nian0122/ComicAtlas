<template>
  <div class="ai-analysis-page">
    <PageHeader spaced title="AI 漫画分析" eyebrow="VISION / ENRICHMENT">
      <template #description>从挂载目录抽取代表页面，让视觉模型生成作品候选、标签和简介。</template>
    </PageHeader>

    <section class="analysis-hero">
      <div class="hero-mark" aria-hidden="true">AI</div>
      <div class="hero-copy">
        <p class="eyebrow">SAMPLED READING</p>
        <h2>把整本漫画交给分析任务</h2>
        <p>服务会读取 Docker 只读挂载目录，默认均匀抽取 10 页。作品名和作者是候选，简介只描述抽样页面。</p>
      </div>
      <div class="hero-status"><span class="status-dot" />异步任务</div>
    </section>

    <section class="analysis-grid">
      <div class="analysis-panel request-panel">
        <div class="panel-heading"><span>01</span><div><h3>提交目录</h3><p>填写挂载根目录下的相对路径</p></div></div>
        <el-form @submit.prevent="submitTask">
          <el-form-item label="漫画目录" label-position="top">
            <el-input v-model="sourcePath" size="large" placeholder="例如：作者 / 作品名 / 第一卷" clearable @keyup.enter="submitTask" />
          </el-form-item>
          <p class="field-hint">不要填写宿主机绝对路径。路径必须位于 AI 服务的 <code>/manga</code> 根目录内。</p>
          <AppButton variant="primary" size="lg" :loading="submitting" :disabled="!sourcePath.trim()" @click="submitTask">
            开始分析 <span aria-hidden="true">↗</span>
          </AppButton>
        </el-form>
      </div>

      <div class="analysis-panel task-panel">
        <div class="panel-heading"><span>02</span><div><h3>任务状态</h3><p>任务会在后台持续运行</p></div></div>
        <ContentState v-if="!task" state="empty" message="提交目录后，这里会显示分析进度" />
        <template v-else>
          <div class="task-identity"><span>#{{ task.id }}</span><strong>{{ task.sourcePath }}</strong></div>
          <el-progress :percentage="task.progress" :status="progressStatus" :stroke-width="8" />
          <div class="task-meta"><span>{{ statusLabel }}</span><span>{{ task.attempts }} 次执行</span></div>
          <p v-if="task.errorMessage" class="task-error">{{ task.errorMessage }}</p>
          <AppButton v-if="canCancel" variant="secondary" :loading="cancelling" @click="cancelTask">取消任务</AppButton>
        </template>
      </div>
    </section>

    <section v-if="result" class="analysis-panel result-panel">
      <div class="panel-heading"><span>03</span><div><h3>分析结果</h3><p>结果需要人工确认后再写回漫画资料</p></div></div>
      <div class="result-layout">
        <div class="identity-card">
          <span class="result-label">作品候选</span>
          <strong>{{ result.titleCandidate || '暂未识别' }}</strong>
          <small>{{ result.authorCandidate || '作者未知' }}</small>
        </div>
        <div class="description-card"><span class="result-label">抽样简介</span><p>{{ result.description || '模型没有返回简介。' }}</p></div>
      </div>
      <div class="tags-row"><span class="result-label">自由标签</span><div><el-tag v-for="tag in result.tags || []" :key="tag" effect="plain">{{ tag }}</el-tag><span v-if="!result.tags?.length" class="muted">暂无标签</span></div></div>
      <el-alert v-if="result.warnings?.length" :title="result.warnings.join('；')" type="info" :closable="false" show-icon />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { AppButton } from '@/shared/ui/button'
import { ContentState } from '@/shared/ui/content-state'
import { PageHeader } from '@/shared/ui/page-header'
import { aiAnalysisApi, type AiAnalysisTask } from '@/features/ai-analysis/api'

interface AnalysisResult { titleCandidate?: string | null; authorCandidate?: string | null; tags?: string[]; description?: string; warnings?: string[] }
const sourcePath = ref('')
const task = ref<AiAnalysisTask | null>(null)
const result = ref<AnalysisResult | null>(null)
const submitting = ref(false)
const cancelling = ref(false)
let pollTimer: number | undefined

const canCancel = computed(() => task.value && ['QUEUED', 'RUNNING'].includes(task.value.status))
const statusLabel = computed(() => ({ QUEUED: '排队中', RUNNING: '分析中', SUCCEEDED: '已完成', FAILED: '失败', CANCEL_REQUESTED: '取消中', CANCELLED: '已取消' })[task.value?.status ?? 'QUEUED'])
const progressStatus = computed(() => task.value?.status === 'FAILED' ? 'exception' : task.value?.status === 'SUCCEEDED' ? 'success' : undefined)

async function submitTask(): Promise<void> {
  if (!sourcePath.value.trim()) return
  submitting.value = true; result.value = null
  try {
    const created = await aiAnalysisApi.create(sourcePath.value.trim())
    task.value = await aiAnalysisApi.get(created.taskId)
    schedulePoll()
    ElMessage.success(`分析任务 #${created.taskId} 已提交`)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : 'AI 服务不可用')
  } finally { submitting.value = false }
}
function schedulePoll(): void { window.clearTimeout(pollTimer); pollTimer = window.setTimeout(refreshTask, 1500) }
async function refreshTask(): Promise<void> {
  if (!task.value) return
  try {
    task.value = await aiAnalysisApi.get(task.value.id)
    if (task.value.status === 'SUCCEEDED') { result.value = task.value.resultJson ? JSON.parse(task.value.resultJson) : null; return }
    if (['FAILED', 'CANCELLED'].includes(task.value.status)) return
    schedulePoll()
  } catch { schedulePoll() }
}
async function cancelTask(): Promise<void> {
  if (!task.value) return
  cancelling.value = true
  try { await aiAnalysisApi.cancel(task.value.id); await refreshTask() } catch { ElMessage.error('取消任务失败') } finally { cancelling.value = false }
}
onBeforeUnmount(() => window.clearTimeout(pollTimer))
</script>

<style scoped>
.ai-analysis-page { max-width: 1180px; margin: 0 auto; padding-bottom: 64px; }
.analysis-hero { display:flex; align-items:center; gap:24px; margin: 12px 0 28px; padding:28px 32px; border:1px solid var(--color-border-faint); background:linear-gradient(120deg, #111a1a 0%, #17312d 100%); color:#f2f5e9; box-shadow: 12px 12px 0 #d6e6d2; }
.hero-mark { display:grid; place-items:center; width:68px; height:68px; border:1px solid #a6d9b0; color:#b8efc2; font:900 20px/1 var(--font-ui); letter-spacing:.08em; }
.hero-copy { flex:1; } .hero-copy h2 { margin:4px 0 8px; font:700 26px/1.2 var(--font-display); } .hero-copy p { max-width:680px; margin:0; color:#bbcbc0; line-height:1.65; }
.eyebrow,.result-label { color:#91cba0; font-size:11px; font-weight:800; letter-spacing:.14em; text-transform:uppercase; } .hero-status { align-self:flex-start; color:#b8efc2; font-size:12px; white-space:nowrap; } .status-dot { display:inline-block; width:7px;height:7px;margin-right:7px;border-radius:50%;background:#9ce4aa;box-shadow:0 0 0 4px #2d5d46; }
.analysis-grid { display:grid; grid-template-columns:1fr 1fr; gap:20px; } .analysis-panel { padding:26px; border:1px solid var(--color-border-faint); background:var(--color-canvas); }
.panel-heading { display:flex; gap:14px; align-items:flex-start; margin-bottom:24px; } .panel-heading > span { color:#67a47a; font:800 12px/1 var(--font-ui); } .panel-heading h3 { margin:0; color:var(--text-primary); font-size:18px; } .panel-heading p { margin:5px 0 0; color:var(--text-muted); font-size:13px; }
.field-hint { margin: -8px 0 22px; color:var(--text-muted); font-size:12px; line-height:1.6; } code { color:#3c8150; } .task-identity { display:flex; flex-direction:column; gap:8px; margin-bottom:24px; } .task-identity span { color:var(--text-muted); font-size:12px; } .task-identity strong { overflow:hidden; color:var(--text-primary); text-overflow:ellipsis; white-space:nowrap; } .task-meta { display:flex; justify-content:space-between; margin:12px 0 22px; color:var(--text-muted); font-size:12px; } .task-error { padding:12px; color:#a34137; background:#fff2ef; font-size:13px; }
.result-panel { margin-top:20px; } .result-layout { display:grid; grid-template-columns:260px 1fr; gap:20px; } .identity-card,.description-card { padding:20px; background:#f3f7ef; } .identity-card strong,.identity-card small { display:block; margin-top:12px; } .identity-card strong { font-size:22px; } .identity-card small { color:var(--text-muted); } .description-card p { margin:14px 0 0; line-height:1.8; color:var(--text-secondary); } .tags-row { display:flex; gap:18px; align-items:flex-start; margin-top:24px; } .tags-row > div { display:flex; flex-wrap:wrap; gap:8px; } .muted { color:var(--text-muted); font-size:13px; }
@media (max-width: 800px) { .analysis-grid,.result-layout { grid-template-columns:1fr; } .analysis-hero { align-items:flex-start; flex-wrap:wrap; } .hero-status { width:100%; } }
</style>
