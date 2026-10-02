<template>
  <div class="comic-ai-page">
    <PageHeader title="AI 漫画分析" description="为当前漫画生成标签和简介，结果会自动写回资料。">
      <span class="comic-reference">COMIC / {{ comicId }}</span>
    </PageHeader>

    <AiAnalysisHero :title="comic?.title || '当前漫画'" description="抽取代表页面，异步生成标签和简介。" />

    <section class="ai-grid">
      <ManagementPanel class="ai-panel task-panel">
        <PanelHeader class="ai-panel-heading" title="分析任务" description="任务在后台运行，可以随时取消。" level="h3">
          <template #leading>01</template>
        </PanelHeader>
        <div v-if="!task" class="empty-copy">还没有分析任务</div>
        <template v-else>
          <div class="task-meta">
            <span>#{{ task.id }}</span
            ><strong>{{ statusLabel }}</strong>
          </div>
          <el-progress :percentage="task.progress" :status="progressStatus" :stroke-width="9" />
          <p v-if="task.errorMessage" class="task-error">{{ task.errorMessage }}</p>
          <p v-if="resultError" class="task-error" role="alert">{{ resultError }}</p>
          <div class="task-actions">
            <AppButton v-if="canCancel" variant="secondary" :loading="cancelling" @click="cancelTask"
              >取消分析</AppButton
            >
            <AppButton v-else variant="primary" :loading="submitting" :disabled="isActive" @click="startTask">
              {{ task.status === 'SUCCEEDED' ? '重新分析' : '开始 AI 分析' }}
            </AppButton>
          </div>
        </template>
        <AppButton v-if="!task" variant="primary" :loading="submitting" @click="startTask">开始 AI 分析</AppButton>
      </ManagementPanel>

      <ManagementPanel class="ai-panel result-panel">
        <PanelHeader class="ai-panel-heading" title="分析结果" description="标签和简介会同步写入漫画资料。" level="h3">
          <template #leading>02</template>
        </PanelHeader>
        <div v-if="!result" class="empty-copy">完成分析后，这里会显示结果</div>
        <AiAnalysisResult v-else :result="result" />
      </ManagementPanel>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { AppButton } from '@/shared/ui/button'
import { PageHeader } from '@/shared/ui/page-header'
import { ManagementPanel, PanelHeader } from '@/shared/ui/management-panel'
import { managementComicApi } from '@/entities/comic'
import { AiAnalysisHero, AiAnalysisResult, useAiAnalysisTask } from '@/features/ai-analysis'
import type { ComicDetailVO } from '@/entities/comic'

const props = defineProps<{ comicId: number }>()
const comic = ref<ComicDetailVO | null>(null)
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

async function startTask(): Promise<void> {
  try {
    const taskId = await createAnalysisTask(props.comicId)
    if (taskId != null) ElMessage.success(`AI 分析任务 #${taskId} 已提交`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 分析提交失败')
  }
}
async function cancelTask(): Promise<void> {
  try {
    await cancelAnalysisTask()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消任务失败')
  }
}
onMounted(async () => {
  try {
    comic.value = (await managementComicApi.detail(props.comicId)).data
  } catch {
    /* 页面仍可提交任务 */
  }
})
</script>

<style scoped>
.comic-ai-page {
  display: grid;
  gap: var(--space-5);
  padding-bottom: 48px;
}
.comic-reference {
  padding: 6px 9px;
  border: 1px solid var(--border);
  color: var(--text-muted);
  font: 700 11px var(--mono);
}
.ai-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  gap: var(--space-4);
}
.ai-panel {
  display: grid;
  gap: var(--space-4);
}
.ai-panel-heading {
  margin-bottom: var(--space-2);
}
.empty-copy {
  padding: 28px 0;
  color: var(--text-muted);
  font-size: 13px;
}
.task-meta {
  display: flex;
  justify-content: space-between;
  color: var(--text-muted);
  font-size: 13px;
}
.task-meta strong {
  color: var(--text-primary);
}
.task-actions {
  display: flex;
  justify-content: flex-end;
}
.task-error {
  margin: 0;
  padding: 12px;
  color: var(--danger);
  background: var(--danger-bg);
  font-size: 13px;
}
@media (max-width: 800px) {
  .ai-grid {
    grid-template-columns: 1fr;
  }
}
</style>
