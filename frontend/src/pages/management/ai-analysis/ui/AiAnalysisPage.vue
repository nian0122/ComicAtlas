<template>
  <div class="ai-analysis-page">
    <PageHeader spaced title="AI 漫画分析" eyebrow="VISION / ENRICHMENT">
      <template #description>从挂载目录抽取代表页面，让视觉模型生成作品候选、标签和简介。</template>
    </PageHeader>

    <AiAnalysisHero
      title="把整本漫画交给分析任务"
      description="默认均匀抽取 10 页，生成作品名、作者候选、标签和抽样简介。"
      >异步任务</AiAnalysisHero
    >

    <section class="analysis-grid">
      <ManagementPanel class="analysis-panel request-panel">
        <PanelHeader
          class="analysis-panel-heading"
          title="选择漫画"
          description="从已导入的漫画库中搜索作品"
          level="h3"
        >
          <template #leading>01</template>
        </PanelHeader>
        <el-form @submit.prevent="submitTask">
          <el-form-item label="漫画" label-position="top">
            <el-select
              v-model="selectedComicId"
              class="comic-select"
              size="large"
              filterable
              remote
              clearable
              :remote-method="searchComics"
              :loading="searchingComics"
              placeholder="搜索漫画标题或作者"
              @focus="loadInitialComics"
            >
              <el-option
                v-for="comic in comics"
                :key="comic.id"
                :label="`${comic.title} · ${comic.author || '作者未知'}`"
                :value="comic.id"
              >
                <div class="comic-option">
                  <strong>{{ comic.title }}</strong
                  ><span>{{ comic.author || '作者未知' }} · {{ comic.pageCount }} 页</span>
                </div>
              </el-option>
            </el-select>
          </el-form-item>
          <p class="field-hint">显示所有已完成导入的漫画。任务会根据漫画 ID 定位 AI 服务的只读挂载目录。</p>
          <AppButton
            variant="primary"
            size="lg"
            :loading="submitting"
            :disabled="selectedComicId == null || isActive"
            @click="submitTask"
          >
            开始分析 <span aria-hidden="true">↗</span>
          </AppButton>
        </el-form>
      </ManagementPanel>

      <ManagementPanel class="analysis-panel task-panel">
        <PanelHeader class="analysis-panel-heading" title="任务状态" description="任务会在后台持续运行" level="h3">
          <template #leading>02</template>
        </PanelHeader>
        <ContentState v-if="!task" state="empty" message="提交目录后，这里会显示分析进度" />
        <template v-else>
          <div class="task-identity">
            <span>#{{ task.id }}</span
            ><strong>{{ selectedComic?.title || task.sourcePath }}</strong>
          </div>
          <el-progress :percentage="task.progress" :status="progressStatus" :stroke-width="8" />
          <div class="task-meta">
            <span>{{ statusLabel }}</span
            ><span>{{ task.attempts }} 次执行</span>
          </div>
          <p v-if="task.errorMessage" class="task-error">{{ task.errorMessage }}</p>
          <p v-if="resultError" class="task-error" role="alert">{{ resultError }}</p>
          <AppButton v-if="canCancel" variant="secondary" :loading="cancelling" @click="cancelTask">取消任务</AppButton>
        </template>
      </ManagementPanel>
    </section>

    <ManagementPanel v-if="result" class="analysis-panel result-panel">
      <PanelHeader
        class="analysis-panel-heading"
        title="分析结果"
        description="结果需要人工确认后再写回漫画资料"
        level="h3"
      >
        <template #leading>03</template>
      </PanelHeader>
      <AiAnalysisResult :result="result" show-identity />
    </ManagementPanel>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { AppButton } from '@/shared/ui/button'
import { ContentState } from '@/shared/ui/content-state'
import { PageHeader } from '@/shared/ui/page-header'
import { ManagementPanel, PanelHeader } from '@/shared/ui/management-panel'
import { AiAnalysisHero, AiAnalysisResult, useAiAnalysisTask } from '@/features/ai-analysis'
import { comicApi } from '@/entities/comic'
import type { ComicListVO } from '@/entities/comic'

const selectedComicId = ref<number | null>(null)
const comics = ref<ComicListVO[]>([])
const searchingComics = ref(false)
const selectedComic = computed(() => comics.value.find((comic) => comic.id === selectedComicId.value) ?? null)
const {
  task,
  result,
  resultError,
  submitting,
  cancelling,
  isActive,
  canCancel,
  statusLabel,
  progressStatus,
  startTask: createAnalysisTask,
  cancelTask: cancelAnalysisTask,
} = useAiAnalysisTask()

async function submitTask(): Promise<void> {
  if (selectedComicId.value == null) return
  try {
    const taskId = await createAnalysisTask(selectedComicId.value)
    if (taskId != null) ElMessage.success(`分析任务 #${taskId} 已提交`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 服务不可用')
  }
}
let lastSearchRequest = 0
async function searchComics(keyword: string): Promise<void> {
  const requestId = ++lastSearchRequest
  searchingComics.value = true
  try {
    const response = await comicApi.list({
      keyword: keyword.trim() || undefined,
      status: 'READY',
      page: 1,
      size: 20,
      sort: 'updatedAt',
      order: 'desc',
    })
    if (requestId === lastSearchRequest) comics.value = response.data.records
  } catch {
    if (requestId === lastSearchRequest) comics.value = []
  } finally {
    if (requestId === lastSearchRequest) searchingComics.value = false
  }
}
function loadInitialComics(): void {
  if (!comics.value.length) void searchComics('')
}
async function cancelTask(): Promise<void> {
  try {
    await cancelAnalysisTask()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消任务失败')
  }
}
</script>

<style scoped>
.ai-analysis-page {
  width: 100%;
  margin: 0;
  padding-bottom: var(--space-8);
}
.analysis-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.analysis-panel {
  display: grid;
  gap: var(--space-4);
}
.analysis-panel-heading {
  margin-bottom: var(--space-2);
}
.task-identity {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 24px;
}
.task-identity span {
  color: var(--text-muted);
  font-size: var(--text-xs);
}
.task-identity strong {
  overflow: hidden;
  color: var(--text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-meta {
  display: flex;
  justify-content: space-between;
  margin: 12px 0 22px;
  color: var(--text-muted);
  font-size: var(--text-xs);
}
.task-error {
  padding: 12px;
  color: var(--danger);
  background: var(--danger-bg);
  font-size: var(--text-sm);
}
.comic-select {
  width: 100%;
}
.comic-option {
  display: flex;
  flex-direction: column;
  gap: 3px;
  line-height: 1.35;
}
.comic-option span {
  color: var(--text-muted);
  font-size: var(--text-xs);
}
.result-panel {
  margin-top: 20px;
}
@media (max-width: 800px) {
  .analysis-grid {
    grid-template-columns: 1fr;
  }
}
</style>
