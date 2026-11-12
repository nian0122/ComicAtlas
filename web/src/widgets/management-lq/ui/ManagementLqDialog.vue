<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { Task } from '@/entities/task'
import type { StorageObject } from '@/entities/storage'
import type { BatchPreview } from '@/features/manage-items'
import {
  BatchPreviewList,
  BatchPreviewSummary,
  ManagementDialog,
  ManagementIcon,
  ManagementImage,
} from '@/shared/ui/management'
import { useLqDialog, type LqTarget } from '../model/use-lq-dialog'
const props = defineProps<{
  open: boolean
  target: LqTarget | null
  objects: StorageObject[]
  workspaceBusy: boolean
}>()
const emit = defineEmits<{ close: []; accepted: [task: Task, batch: BatchPreview | null] }>()
const w = reactive(useLqDialog(props, (task, batch) => emit('accepted', task, batch)))
const batchRows = computed(() =>
  w.items.map((item) => ({
    key: item.position,
    title: item.title,
    cover: w.itemCover(item),
    meta: w.itemStats(item) || undefined,
    status: w.itemLabel(item),
    reason: item.status === 'blocked' ? w.itemReason(item) : undefined,
    errorCode: item.errorCode ?? undefined,
  })),
)
</script>

<template>
  <ManagementDialog :open="open" title="生成轻量副本" size="form" :busy="w.submitting" @close="emit('close')">
    <p class="mw-muted">
      轻量副本是阅读用的 WebP 副本（长边不超过 3840、质量
      70），不会修改原件，也不会改变阅读进度。确认后提交后台任务，逐张结果在任务中心查看。
    </p>
    <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
    <button
      v-if="!w.loading && w.error && ((w.single && !w.scope) || (!w.single && !w.preview))"
      type="button"
      class="mw-button lq-reload"
      :disabled="w.submitting"
      @click="w.reload"
    >
      重新读取范围
    </button>

    <p v-if="w.loading" class="mw-loading" role="status">正在读取范围与登记状态…</p>
    <template v-else>
      <div class="lq-scope">
        <ManagementImage v-if="w.single" :src="w.target?.cover ?? null" :alt="w.target?.title ?? ''" />
        <div class="lq-scope-copy">
          <h3>{{ w.single ? w.target?.title : `已选 ${w.selectedCount} 部作品` }}</h3>
          <p class="mw-muted">
            {{
              w.single
                ? w.target?.kind === 'comics'
                  ? '整部漫画'
                  : '指定章节'
                : '批量生成；每部作品的逐项结果在任务中心查看'
            }}
            · {{ w.selectedImages }} 张图片
          </p>
        </div>
      </div>

      <BatchPreviewSummary
        v-if="!w.single && w.preview"
        :selected="w.selectedCount"
        :eligible="w.eligibleCount"
        :blocked="w.blockedCount"
        unit="部"
      />

      <p v-if="w.preview && w.eligibleCount === 0" class="mw-error">所选作品都没有可执行的图片，无法提交。</p>
      <dl v-else-if="w.known" class="lq-stats">
        <div>
          <dt>图片</dt>
          <dd>{{ w.totals.images }} 张</dd>
        </div>
        <div>
          <dt>已有有效副本</dt>
          <dd>{{ w.totals.ready }} 张</dd>
        </div>
        <div>
          <dt>待补齐</dt>
          <dd>{{ w.totals.missing }} 张</dd>
        </div>
        <div>
          <dt>原件已释放</dt>
          <dd>{{ w.totals.released }} 张</dd>
        </div>
      </dl>
      <p v-else class="mw-muted">登记统计不完整，只能显示图片总数；提交后以任务中心的逐项结果为准。</p>

      <p v-if="w.singleBlockers" class="mw-error">{{ w.singleBlockers }}</p>
      <p v-if="!w.single && w.blockedCount" class="mw-muted">被阻止的作品不会提交，原因在下方清单中列出。</p>

      <fieldset class="lq-modes" :disabled="w.loading || w.submitting || w.locked">
        <legend>生成方式</legend>
        <label class="lq-mode" :class="{ active: w.mode === 'missing' }">
          <input
            type="radio"
            name="lq-mode"
            value="missing"
            aria-label="补齐缺失副本"
            aria-describedby="lq-missing-copy"
            :checked="w.mode === 'missing'"
            @change="w.setMode('missing')"
          />
          <span id="lq-missing-copy" class="lq-mode-copy">
            <strong>补齐缺失副本 <span class="lq-badge">推荐</span></strong>
            <small>保留已有有效副本，只补齐缺失或失效的副本。生成时按需补算原件摘要。</small>
            <em v-if="w.known && w.mode === 'missing' && w.prediction">
              将生成 {{ w.prediction.generate }} 张，跳过 {{ w.prediction.skip }} 张<template
                v-if="w.prediction.unavailable"
                >；{{ w.prediction.unavailable }} 张没有可用原件或有效副本</template
              >。
            </em>
          </span>
        </label>
        <label class="lq-mode" :class="{ active: w.mode === 'all' }">
          <input
            type="radio"
            name="lq-mode"
            value="all"
            aria-label="全部重新生成"
            aria-describedby="lq-all-copy"
            :checked="w.mode === 'all'"
            @change="w.setMode('all')"
          />
          <span id="lq-all-copy" class="lq-mode-copy">
            <strong>全部重新生成</strong>
            <small>对仍有原件的每张图片重新编码并覆盖现有副本；不删除原件，也不能修复缺失或已释放的原件。</small>
            <em v-if="w.known && w.mode === 'all' && w.prediction">
              将重新生成 {{ w.prediction.generate }} 张<template v-if="w.prediction.unavailable"
                >；{{ w.prediction.unavailable }} 张原件已释放或异常，预计失败，现有副本保留</template
              >。
            </em>
          </span>
        </label>
      </fieldset>
      <p class="mw-muted">数量按登记状态估算，实际逐张结果在任务中心查看。</p>
      <details class="lq-advanced">
        <summary>什么时候使用全部重新生成</summary>
        <p>
          更换编码器、替换导入副本或怀疑副本异常时。规格不变，不会提升画质；已有副本不会被删除，原件已释放的图片会失败。
        </p>
      </details>
      <p v-if="w.locked" class="mw-notice">
        上次提交结果尚未确认；生成方式已锁定。请重试同次提交，或在任务中心核对后再操作。
      </p>

      <template v-if="!w.single">
        <BatchPreviewList
          v-if="w.items.length || w.total"
          :rows="batchRows"
          :total="w.total"
          :loading-more="w.loadingMore"
          @more="w.more"
        />
        <p v-else-if="!w.loading && w.preview" class="mw-muted">当前范围没有作品清单。</p>
      </template>
    </template>
    <footer class="mw-dialog-footer">
      <button type="button" class="mw-button" :disabled="w.submitting" @click="emit('close')">取消</button>
      <button type="button" class="mw-button mw-button-primary" :disabled="!w.canSubmit" @click="w.submit">
        <ManagementIcon v-if="w.submitting" name="loading" class="mw-spin" :size="14" />{{ w.submitLabel }}
      </button>
    </footer>
  </ManagementDialog>
</template>

<style scoped>
.lq-reload {
  margin-bottom: 8px;
}
.mw-dialog-footer {
  position: sticky;
  bottom: calc(var(--atlas-space-6) * -1);
  z-index: 2;
  margin: var(--atlas-space-4) calc(var(--atlas-space-6) * -1) calc(var(--atlas-space-6) * -1);
  padding: var(--atlas-space-4) var(--atlas-space-6);
  background: var(--atlas-raised);
  border-top: 1px solid var(--atlas-line);
}
.lq-scope {
  display: flex;
  align-items: center;
  gap: var(--atlas-space-4);
  margin: var(--atlas-space-4) 0;
}
.lq-scope :deep(img),
.lq-scope :deep(.mw-image-empty) {
  width: 60px;
  height: 80px;
  object-fit: contain;
  border-radius: 4px;
}
.lq-scope-copy {
  min-width: 0;
}
.lq-scope-copy h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.lq-scope-copy p {
  margin: var(--atlas-space-1) 0 0;
}
.lq-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(104px, 1fr));
  gap: var(--atlas-space-3) var(--atlas-space-4);
  margin: var(--atlas-space-2) 0 var(--atlas-space-4);
  padding: var(--atlas-space-4);
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  background: var(--atlas-raised);
}
.lq-stats dt {
  color: var(--atlas-muted);
  font-size: 11px;
}
.lq-stats dd {
  margin: var(--atlas-space-1) 0 0;
  color: var(--atlas-text);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}
.lq-advanced {
  margin: var(--atlas-space-1) 0 var(--atlas-space-4);
  color: var(--atlas-muted);
  font-size: 12px;
  line-height: 1.7;
}
.lq-advanced summary {
  cursor: pointer;
  color: var(--atlas-muted);
}
.lq-advanced p {
  margin: var(--atlas-space-2) 0 0;
}
.lq-modes {
  display: grid;
  gap: var(--atlas-space-2);
  margin: var(--atlas-space-4) 0 var(--atlas-space-2);
  padding: 0;
  border: 0;
}
.lq-modes legend {
  margin-bottom: var(--atlas-space-2);
  color: var(--atlas-muted);
  font-size: 12px;
}
.lq-mode {
  display: flex;
  gap: var(--atlas-space-3);
  align-items: flex-start;
  padding: var(--atlas-space-3) var(--atlas-space-4);
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  background: var(--atlas-surface);
  cursor: pointer;
}
.lq-mode:hover {
  border-color: var(--atlas-control-border-hover);
}
.lq-mode.active {
  border-color: var(--atlas-accent);
  background: var(--atlas-accent-soft);
}
.lq-mode input {
  margin-top: var(--atlas-space-1);
}
.lq-mode-copy {
  display: grid;
  gap: var(--atlas-space-1);
  min-width: 0;
}
.lq-mode-copy strong {
  font-size: 13px;
  font-weight: 500;
}
.lq-mode-copy small {
  color: var(--atlas-muted);
  font-size: 12px;
  line-height: 1.6;
}
.lq-mode-copy em {
  color: var(--atlas-text);
  font-size: 12px;
  font-style: normal;
  line-height: 1.6;
}
.lq-badge {
  margin-left: var(--atlas-space-2);
  padding: 2px 8px;
  border: 1px solid var(--atlas-line);
  border-radius: 999px;
  color: var(--atlas-muted);
  font-size: 10px;
  font-weight: 400;
}
</style>
