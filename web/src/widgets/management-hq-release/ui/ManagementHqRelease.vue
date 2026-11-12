<script setup lang="ts">
import { computed, reactive } from 'vue'
import { comicCoverUrl } from '@/entities/comic'
import type { Task } from '@/entities/task'
import { operationBlockers } from '@/entities/storage'
import type { BatchPreview } from '@/features/manage-items'
import {
  BatchPreviewList,
  BatchPreviewSummary,
  ManagementDialog,
  ManagementImage,
  formatBytes,
} from '@/shared/ui/management'
import { useHqRelease, type HqTarget } from '../model/use-hq-release'
const props = defineProps<{ open: boolean; target: HqTarget | null; ids: string[]; workspaceBusy: boolean }>()
const emit = defineEmits<{ close: []; accepted: [task: Task, batch: BatchPreview | null] }>()
const w = reactive(useHqRelease(props, (task, batch) => emit('accepted', task, batch)))
const batchRows = computed(() =>
  w.items.map((item) => {
    const hq = item.hq
    return {
      key: item.position,
      title: item.title,
      cover: comicCoverUrl(item.targetId),
      meta: hq && hq.allowed ? `${hq.available} 张可释放 · ${formatBytes(hq.originalBytes)}` : undefined,
      status: hq?.allowed ? '可释放' : '已阻止',
      reason:
        hq && hq.allowed
          ? undefined
          : hq?.blockers.length
            ? operationBlockers(hq.blockers)
            : item.errorCode || item.status,
      errorCode: item.errorCode ?? undefined,
    }
  }),
)
</script>
<template>
  <ManagementDialog :open="open" title="释放图片原件" :busy="w.busy" @close="emit('close')">
    <div class="mw-error">
      <strong>永久删除当前受管图片原件，保留已验证的轻量副本。</strong>
      <p>
        不进入回收站，无法撤销。释放后只能通过轻量副本阅读图片，作品、章节、阅读位置和视频保留。已受理的释放任务不可取消，失败也不会恢复已经删除的原件。
      </p>
    </div>
    <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
    <p v-if="w.busy && !w.ready" class="mw-muted">正在核对登记范围与保护条件…</p>
    <template v-if="w.preview && w.target">
      <div class="hq-target">
        <ManagementImage :src="w.target.cover" :alt="w.target.title" />
        <div>
          <h3>{{ w.target.title }}</h3>
          <p class="mw-muted">{{ w.target.kind === 'comics' ? '整部漫画' : '指定章节' }}的当前图片范围</p>
        </div>
      </div>
      <dl class="mw-properties">
        <dt>图片总数</dt>
        <dd>{{ w.preview.images }}</dd>
        <dt>有原件</dt>
        <dd>{{ w.preview.available }}</dd>
        <dt>已释放</dt>
        <dd>{{ w.preview.released }}</dd>
        <dt>可释放原件登记量</dt>
        <dd>{{ formatBytes(w.preview.originalBytes) }}</dd>
      </dl>
      <p v-if="w.preview.truncated" class="mw-error">预览被截断，数量和字节仅为部分观察，不能释放。</p>
      <p v-if="w.preview.blockers.length" class="mw-error">{{ operationBlockers(w.preview.blockers) }}</p>
    </template>
    <template v-if="w.batch">
      <BatchPreviewSummary
        :selected="w.batch.selectedCount"
        :eligible="w.batch.eligibleCount"
        :blocked="w.batch.blockedCount"
        unit="部"
      />
      <p>可释放 {{ w.batch.hq?.images }} 张图片原件 · 登记量 {{ formatBytes(w.batch.hq?.originalBytes) }}</p>
      <p>
        仅释放可执行作品，被阻止的作品不会处理。请在十分钟内确认，范围变化后需重新预览。取消批次只停止尚未分派的作品；已受理的释放任务仍会继续，已删除原件不会恢复。
      </p>
      <BatchPreviewList :rows="batchRows" :total="w.total" :loading-more="w.busy" @more="w.more" />
      <p v-if="w.expired && !w.attempted" class="mw-error">范围预览已过期，请重新预览后确认。</p>
    </template>
    <p class="mw-muted">显示大小为登记量，实际释放空间可能不同。缺失原件时，可能是唯一可读内容的轻量副本不会删除。</p>
    <details class="hq-diagnostics">
      <summary>释放保护与技术详情</summary>
      <p>
        单范围预览只反映当前登记，首次提交前会重新核对；执行时仍验证原件和副本。图片 HQ
        读取在释放后不可用，自动／轻量阅读使用已验证的副本，漫画、章节及媒体标识保留。
      </p>
      <p v-if="w.batch">批量范围由预览令牌固定，令牌有效期十分钟；范围变化会被服务端拒绝。</p>
      <p>提交响应不确定时，重试沿用原幂等键与范围，只核对同一次释放请求。</p>
    </details>
    <p v-if="w.attempted" class="mw-error">
      上次提交尚未确认。重试继续确认同一次释放，不会重复创建任务；也可在任务中心查看。
    </p>
    <label class="mw-select-page"
      ><input
        v-model="w.acknowledged"
        type="checkbox"
        :disabled="w.busy || !w.ready"
      />我已核对范围，理解原件永久删除、无法撤销且已受理的释放任务不可取消</label
    >
    <footer class="mw-dialog-footer">
      <button class="mw-button" :disabled="w.busy || w.attempted" @click="w.load">重新预览</button
      ><button class="mw-button mw-button-danger" :disabled="!w.canSubmit || workspaceBusy" @click="w.submit">
        {{ w.attempted ? '重试确认同次提交' : '永久释放图片原件' }}
      </button>
    </footer>
  </ManagementDialog>
</template>
<style scoped>
.hq-target {
  display: flex;
  align-items: center;
  gap: var(--atlas-space-4);
  margin: var(--atlas-space-6) 0;
}
.hq-diagnostics {
  margin: var(--atlas-space-4) 0;
  color: var(--atlas-muted);
  font-size: 14px;
  line-height: 1.8;
}
.hq-diagnostics summary {
  cursor: pointer;
  color: var(--atlas-text);
}
.hq-target :deep(img),
.hq-target :deep(.mw-image-empty) {
  width: 60px;
  height: 80px;
  object-fit: contain;
}
.hq-target h3 {
  margin: 0;
  font-size: 16px;
}
</style>
