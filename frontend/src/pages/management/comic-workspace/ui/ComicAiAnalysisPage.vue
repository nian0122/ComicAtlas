<template>
  <div class="comic-ai-page">
    <PageHeader title="AI 漫画分析" description="为当前漫画生成标签和简介，结果会自动写回资料。">
      <span class="comic-reference">COMIC / {{ comicId }}</span>
    </PageHeader>

    <section class="ai-hero">
      <div class="hero-symbol" aria-hidden="true">AI</div>
      <div>
        <span class="eyebrow">VISION / ENRICHMENT</span>
        <h2>{{ comic?.title || '当前漫画' }}</h2>
        <p>从只读漫画目录抽取代表页面，异步生成标签和自然简介。</p>
      </div>
    </section>

    <section class="ai-grid">
      <div class="ai-panel task-panel">
        <div class="panel-heading"><span>01</span><div><h3>分析任务</h3><p>任务在后台运行，可以随时取消。</p></div></div>
        <div v-if="!task" class="empty-copy">还没有分析任务</div>
        <template v-else>
          <div class="task-meta"><span>#{{ task.id }}</span><strong>{{ statusLabel }}</strong></div>
          <el-progress :percentage="task.progress" :status="progressStatus" :stroke-width="9" />
          <p v-if="task.errorMessage" class="task-error">{{ task.errorMessage }}</p>
          <div class="task-actions">
            <AppButton v-if="canCancel" variant="secondary" :loading="cancelling" @click="cancelTask">取消分析</AppButton>
            <AppButton v-else variant="primary" :loading="submitting" @click="startTask">
              {{ task.status === 'SUCCEEDED' ? '重新分析' : '开始 AI 分析' }}
            </AppButton>
          </div>
        </template>
        <AppButton v-if="!task" variant="primary" :loading="submitting" @click="startTask">开始 AI 分析</AppButton>
      </div>

      <div class="ai-panel result-panel">
        <div class="panel-heading"><span>02</span><div><h3>分析结果</h3><p>标签和简介会同步写入漫画资料。</p></div></div>
        <div v-if="!result" class="empty-copy">完成分析后，这里会显示结果</div>
        <template v-else>
          <div class="description"><span>简介</span><p>{{ result.description || '未生成简介' }}</p></div>
          <div class="tags"><span>标签</span><div><el-tag v-for="tag in result.tags || []" :key="tag" effect="plain">{{ tag }}</el-tag><small v-if="!result.tags?.length">暂无标签</small></div></div>
        </template>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { AppButton } from '@/shared/ui/button'
import { PageHeader } from '@/shared/ui/page-header'
import { managementComicApi } from '@/entities/comic'
import { aiAnalysisApi, type AiAnalysisTask } from '@/features/ai-analysis/api'
import type { ComicDetailVO } from '@/entities/comic'

const props = defineProps<{ comicId: number }>()
interface AnalysisResult { readonly tags?: readonly string[]; readonly description?: string }
const comic = ref<ComicDetailVO | null>(null)
const task = ref<AiAnalysisTask | null>(null)
const result = ref<AnalysisResult | null>(null)
const submitting = ref(false)
const cancelling = ref(false)
let pollTimer: number | undefined

const canCancel = computed(() => task.value != null && ['QUEUED', 'RUNNING'].includes(task.value.status))
const statusLabel = computed(() => ({ QUEUED: '排队中', RUNNING: '分析中', SUCCEEDED: '已完成', FAILED: '失败', CANCEL_REQUESTED: '取消中', CANCELLED: '已取消' })[task.value?.status ?? 'QUEUED'])
const progressStatus = computed(() => task.value?.status === 'FAILED' ? 'exception' : task.value?.status === 'SUCCEEDED' ? 'success' : undefined)

async function startTask(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  result.value = null
  try {
    const created = await aiAnalysisApi.create(props.comicId)
    task.value = await aiAnalysisApi.get(created.taskId)
    schedulePoll()
    ElMessage.success(`AI 分析任务 #${created.taskId} 已提交`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 分析提交失败')
  } finally {
    submitting.value = false
  }
}
function schedulePoll(): void {
  window.clearTimeout(pollTimer)
  pollTimer = window.setTimeout(refreshTask, 1500)
}
async function refreshTask(): Promise<void> {
  if (!task.value) return
  try {
    task.value = await aiAnalysisApi.get(task.value.id)
    if (task.value.status === 'SUCCEEDED') {
      result.value = task.value.resultJson ? JSON.parse(task.value.resultJson) : null
      return
    }
    if (!['FAILED', 'CANCELLED'].includes(task.value.status)) schedulePoll()
  } catch {
    schedulePoll()
  }
}
async function cancelTask(): Promise<void> {
  if (!task.value) return
  cancelling.value = true
  try {
    await aiAnalysisApi.cancel(task.value.id)
    await refreshTask()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消任务失败')
  } finally {
    cancelling.value = false
  }
}
onMounted(async () => {
  try { comic.value = (await managementComicApi.detail(props.comicId)).data } catch { /* 页面仍可提交任务 */ }
})
onBeforeUnmount(() => window.clearTimeout(pollTimer))
</script>

<style scoped>
.comic-ai-page { display: grid; gap: var(--space-5); padding-bottom: 48px; }
.comic-reference { padding: 6px 9px; border: 1px solid var(--border); color: var(--text-muted); font: 700 11px var(--mono); }
.ai-hero { display: flex; align-items: center; gap: var(--space-5); padding: 28px; border: 1px solid #aac9aa; background: linear-gradient(120deg, #eef7ed, #f8fbf4); }
.hero-symbol { display: grid; place-items: center; width: 68px; height: 68px; border: 1px solid #5d9569; color: #28633e; font: 900 20px/1 var(--font-ui); letter-spacing: .08em; }
.ai-hero h2 { margin: 6px 0; color: #17312d; font-size: clamp(1.2rem, 2vw, 1.7rem); }
.ai-hero p { margin: 0; color: #4d685a; font-size: 13px; }
.eyebrow { color: #438054; font-size: 11px; font-weight: 800; letter-spacing: .14em; }
.ai-grid { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: var(--space-4); }
.ai-panel { display: grid; gap: var(--space-4); padding: var(--space-5); border: 1px solid var(--border); background: var(--bg-surface); }
.panel-heading { display: flex; gap: 14px; align-items: flex-start; }
.panel-heading > span { color: var(--accent); font: 800 12px var(--mono); }
.panel-heading h3 { margin: 0; color: var(--text-primary); font-size: 18px; }
.panel-heading p { margin: 5px 0 0; color: var(--text-muted); font-size: 13px; }
.empty-copy { padding: 28px 0; color: var(--text-muted); font-size: 13px; }
.task-meta { display: flex; justify-content: space-between; color: var(--text-muted); font-size: 13px; }
.task-meta strong { color: var(--text-primary); }
.task-actions { display: flex; justify-content: flex-end; }
.task-error { margin: 0; padding: 12px; color: #a34137; background: #fff2ef; font-size: 13px; }
.description span, .tags > span { color: var(--text-muted); font-size: 12px; font-weight: 700; }
.description p { margin: 10px 0 0; color: var(--text-secondary); line-height: 1.8; }
.tags { display: grid; gap: 10px; padding-top: 16px; border-top: 1px solid var(--border); }
.tags > div { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.tags small { color: var(--text-muted); }
@media (max-width: 800px) { .ai-grid { grid-template-columns: 1fr; } .ai-hero { align-items: flex-start; } }
</style>
