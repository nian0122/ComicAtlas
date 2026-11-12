<script setup lang="ts">
import { reactive } from 'vue'
import type { Task } from '@/entities/task'
import { recoveryLabels, recoveryErrors } from '@/entities/recovery'
import { ManagementDialog, ManagementIcon, formatDate, taskStatusLabels } from '@/shared/ui/management'
import { useRecovery } from '../model/use-recovery'
const props = defineProps<{ mode: 'scan' | 'repair'; comicId: string; taskId: string; writable: boolean }>()
const emit = defineEmits<{
  close: [changed: boolean]
  accepted: [task: Task]
  task: [id: string]
  repair: [comic: string]
  scan: [id: string]
  comic: [id: string]
}>()
const w = reactive(useRecovery(props, (task) => emit('accepted', task)))
</script>
<template>
  <ManagementDialog
    :open="true"
    :title="mode === 'scan' ? '扫盘恢复' : '恢复占位补全'"
    :busy="w.busy"
    placement="right"
    size="wide"
    @close="emit('close', !!w.task || w.uncertain)"
  >
    <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
    <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
    <template v-if="mode === 'scan'">
      <section class="recovery-intro">
        <h3>从当前媒体卷重建缺失登记</h3>
        <p>
          扫描当前媒体卷并直接登记缺失漫画，已有作品不会覆盖。缺少恢复资料但仍有文件时，会建立待补全占位；损坏来源计入失败。
        </p>
        <p>恢复资料不包含完整阅读记录、标记和设置，不能代替数据库备份。开始后直接处理，不提供逐项选择后再导入。</p>
        <details class="recovery-diagnostics">
          <summary>恢复要求与诊断</summary>
          <p>只读取服务端媒体根的 <code>hq</code> 与 <code>metadata</code>，不接受任意磁盘路径。</p>
          <p>只支持当前 Go v1 侧车；已有任意生命周期状态的同 ID 记录会跳过。不扫描 exports、trash 或 retired。</p>
          <p>提交响应不确定时，重试沿用原请求和同一幂等键。</p>
        </details>
      </section>
      <div v-if="!w.task" class="recovery-consent">
        <label
          ><input
            v-model="w.acknowledged"
            type="checkbox"
            :disabled="!writable || w.busy || w.loading || w.uncertain"
          />我理解将直接登记缺失漫画和待补全占位，已有记录不会覆盖</label
        >
        <p v-if="w.uncertain" class="mw-error">上次提交尚未确认。重试沿用同次恢复请求，不会重复创建任务。</p>
        <button class="mw-button mw-button-primary" :disabled="!writable || !w.canSubmit" @click="w.submit">
          {{ w.uncertain ? '重试原受理' : '开始扫盘恢复' }}
        </button>
      </div>
      <template v-if="w.results"
        ><p v-if="!w.results.sealed" class="recovery-state">
          正在发现目录，候选范围尚未封存。暂存发现 {{ w.results.candidateCount }} 项，最终总数仍未知。
        </p>
        <p v-else class="recovery-state">
          候选范围已封存 {{ w.results.candidateCount }} 项 · 忽略目录项
          {{ w.results.ignoredCount }}。已处理数量包含占位、失败和跳过。
        </p>
        <div v-if="w.results.summary" class="recovery-counts">
          <div
            v-for="item in [
              { label: '完整恢复', value: w.results.summary.restored },
              { label: '待补全占位', value: w.results.summary.placeholders },
              { label: '已有跳过', value: w.results.summary.skipped },
              { label: '空目录壳', value: w.results.summary.empty },
              { label: '失败', value: w.results.summary.failed },
              { label: '已处理 / 总数', value: w.results.summary.processed + ' / ' + w.results.summary.total },
            ]"
            :key="item.label"
          >
            <span>{{ item.label }}</span
            ><strong>{{ item.value }}</strong>
          </div>
        </div>
        <p v-if="w.task?.status === 'succeeded'" class="mw-muted">
          本轮封存范围已处理结束。请继续核对分类，不能把占位或跳过称为原结构恢复完成。
        </p>
        <p class="mw-muted">结果随执行增加；刷新从首批重读。跨请求分页不是冻结快照。</p>
        <div class="recovery-rows">
          <article v-for="item in w.rows" :key="item.comicId">
            <div>
              <strong>{{ recoveryLabels[item.status] }}</strong
              ><small>{{ item.comicId }}</small>
            </div>
            <p>执行轮次 {{ item.attempt }} · 实际处理 {{ item.tryCount }} 次 · {{ formatDate(item.updatedAt) }}</p>
            <p v-if="item.errorCode" class="mw-error">{{ recoveryErrors[item.errorCode] || item.errorCode }}</p>
            <button
              v-if="item.status === 'placeholder'"
              class="mw-button"
              :disabled="w.busy"
              @click="emit('repair', item.comicId)"
            >
              查看占位并补全</button
            ><button
              v-if="item.status === 'restored'"
              class="mw-button"
              :disabled="w.busy"
              @click="emit('comic', item.comicId)"
            >
              打开恢复作品
            </button>
          </article>
        </div>
        <button
          v-if="w.results.nextAfter"
          class="mw-button"
          :disabled="w.loading || w.moreLoading || w.busy"
          @click="w.more"
        >
          {{ w.moreLoading ? '正在读取后续结果…' : '加载后续恢复结果' }}
        </button>
        <p v-if="!w.rows.length" class="mw-muted">当前没有已保存的逐本结果。</p>
      </template>
    </template>
    <template v-else>
      <section class="recovery-intro">
        <h3>{{ w.comic?.title || '核对原漫画占位' }}</h3>
        <p>仅补全尚无内容结构的恢复占位，不会覆盖普通漫画。</p>
        <button class="mw-button" :disabled="w.sourceLoading || w.busy || w.loading" @click="w.source">
          <ManagementIcon name="refresh" />重新核对占位
        </button>
        <p v-if="w.sourceLoading" role="status">正在核对当前占位…</p>
        <template v-if="w.marker"
          ><p>
            待补全原因：{{ w.marker.reason === 'metadata_missing' ? '缺少恢复资料' : w.marker.reason }} ·
            {{ formatDate(w.marker.createdAt) }}
          </p>
          <p>
            请先在服务端补齐恢复资料及其对应的原件和轻量副本。本入口不上传或推测丢失结构；来源未补齐时，补全会失败并保留占位。
          </p>
          <details class="recovery-diagnostics">
            <summary>恢复要求与诊断</summary>
            <p>漫画标识：{{ comicId }}。补全要求当前占位没有活动或隐藏结构。</p>
            <p>提交响应不确定时，重试保留原漫画、资料和结构版本与同一幂等键，不会改用新版本。</p>
            <dl class="recovery-facts">
              <dt>普通媒体文件名</dt>
              <dd>{{ w.marker.presence.media }}</dd>
              <dt>其他普通文件</dt>
              <dd>{{ w.marker.presence.other }}</dd>
              <dt>目录</dt>
              <dd>{{ w.marker.presence.directories }}</dd>
              <dt>资料 / 结构版本</dt>
              <dd>{{ w.comic?.version }} / {{ w.comic?.structureVersion }}</dd>
            </dl>
            <p class="mw-muted">这些数量只表示文件名和存在事实，不表示媒体内容通过验证。</p>
            <p>
              请先在服务端补回 <code>metadata/{{ comicId }}/metadata.json</code> 的当前 Go v1 侧车及绑定的 HQ／当前 LQ。
            </p>
          </details>
          <p v-if="w.comic">
            {{
              w.comic.version > 1
                ? '当前资料已经编辑：保留当前全部资料和词条关系，只补结构、媒体与隐藏路径。'
                : '当前资料尚未编辑：采用来源侧车的标题、作者、简介和词条。'
            }}漫画身份与合法漫画级标记保留。
          </p>
          <p v-if="!w.canRepair && w.fresh" class="mw-error">
            当前作品已有结构或不满足空占位条件，禁止补全。
          </p></template
        >
        <p v-else-if="w.fresh" class="mw-muted">当前作品没有待补全占位标记；不会以此入口覆盖普通漫画。</p>
        <button v-if="w.marker?.taskId" class="mw-button" :disabled="w.busy" @click="emit('scan', w.marker.taskId)">
          查看来源恢复任务
        </button>
      </section>
      <div v-if="!w.task" class="recovery-consent">
        <label
          ><input
            v-model="w.acknowledged"
            type="checkbox"
            :disabled="!writable || !w.canRepair || w.busy || w.sourceLoading || w.uncertain"
          />我已补齐恢复资料和对应媒体，确认补全当前占位</label
        >
        <p v-if="w.uncertain" class="mw-error">
          上次提交尚未确认。重试沿用同次补全请求，保留原来的作品与核对结果，不会重复创建任务。
        </p>
        <button class="mw-button mw-button-primary" :disabled="!writable || !w.canSubmit" @click="w.submit">
          {{ w.uncertain ? '重试原补全受理' : '确认补全占位' }}
        </button>
      </div>
      <section v-if="w.repairResult" class="recovery-repair-result" aria-label="占位补全结果">
        <h3>{{ w.repairResult.completed ? '占位补全已完成' : '占位补全尚未完成' }}</h3>
        <details class="recovery-diagnostics">
          <summary>补全版本详情</summary>
          <p>
            绑定资料 / 结构版本 {{ w.repairResult.expectedVersion }} / {{ w.repairResult.expectedStructureVersion }}
          </p>
          <p v-if="w.repairResult.completed">
            完成后版本 {{ w.repairResult.version }} / {{ w.repairResult.structureVersion }}
          </p>
        </details>
        <template v-if="w.repairResult.completed"
          ><p>完成时间：{{ formatDate(w.repairResult.completedAt) }}</p>
          <p>
            {{
              w.repairResult.preservedMetadata === null
                ? '资料保留策略未确认'
                : w.repairResult.preservedMetadata
                  ? '已保留当前编辑资料和词条'
                  : '采用来源资料补全'
            }}
          </p>
          <button class="mw-button" @click="emit('comic', comicId)">打开补全作品</button></template
        >
        <p class="mw-muted">历史扫盘任务仍保留当时的“占位”结果，不会改写过去事实。</p>
      </section>
    </template>
    <section v-if="w.task" class="recovery-task">
      <div class="mw-section-tools">
        <h3>当前任务：{{ taskStatusLabels[w.task.status] }}</h3>
        <button class="mw-button" :disabled="w.busy || w.loading || w.moreLoading" @click="w.refresh">
          刷新恢复结果
        </button>
      </div>
      <p>
        阶段 {{ w.task.stage || '等待执行' }} · 第 {{ w.task.attempt }} 次 · 已处理 {{ w.task.completedCount }} /
        {{ w.task.totalCount === null ? '未知' : w.task.totalCount }}
      </p>
      <p v-if="w.task.failure" class="mw-error">{{ w.task.failure.message }} {{ w.task.failure.action }}</p>
      <p>取消不回滚已经提交的漫画或补全。失败重试沿用原任务；取消后若需继续，应明确建立新任务。</p>
      <div class="mw-inline-actions">
        <button class="mw-button" :disabled="w.busy" @click="emit('task', w.task.id)">查看任务详情</button
        ><button
          v-if="w.task.canCancel"
          class="mw-button"
          :disabled="!writable || w.busy || w.loading || w.moreLoading"
          @click="w.action('cancel')"
        >
          取消当前恢复任务</button
        ><button
          v-if="w.task.canRetry"
          class="mw-button mw-button-primary"
          :disabled="!writable || w.busy || w.loading || w.moreLoading"
          @click="w.action('retry')"
        >
          重试同一恢复任务</button
        ><button
          v-if="!w.running && (mode === 'scan' || w.marker)"
          class="mw-button"
          :disabled="w.busy || w.loading || w.sourceLoading || w.moreLoading"
          @click="w.startNew"
        >
          {{ mode === 'scan' ? '建立新一轮恢复' : '按当前版本重新补全' }}
        </button>
      </div>
    </section>
    <p v-if="w.loading" role="status">正在读取实际恢复结果…</p>
  </ManagementDialog>
</template>
<style scoped>
.recovery-intro {
  margin: 24px 0;
}
.recovery-intro h3 {
  font-size: 18px;
  margin: 0 0 12px;
}
.recovery-intro p,
.recovery-task p {
  font-size: 14px;
  line-height: 1.8;
}
.recovery-intro code {
  overflow-wrap: anywhere;
}
.recovery-diagnostics {
  margin: 16px 0;
  color: var(--atlas-muted);
  font-size: 14px;
  overflow-wrap: anywhere;
}
.recovery-diagnostics summary {
  cursor: pointer;
  color: var(--atlas-text);
}
.recovery-consent {
  padding: 18px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-raised);
  border-radius: var(--atlas-radius-control);
  margin: 20px 0;
}
.recovery-consent label {
  display: flex;
  gap: 10px;
  align-items: start;
  margin-bottom: 16px;
  font-size: 13px;
  line-height: 1.7;
}
.recovery-consent input {
  accent-color: var(--atlas-accent);
  flex-shrink: 0;
}
.recovery-state {
  font-size: 13px;
  margin-top: 24px;
}
.recovery-counts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 18px 0;
}
.recovery-counts div {
  display: grid;
  gap: 12px;
  background: var(--atlas-surface);
  border: 1px solid var(--atlas-line);
  padding: 16px;
  border-radius: var(--atlas-radius-control);
}
.recovery-counts span {
  color: var(--atlas-muted);
  font-size: 12px;
}
.recovery-counts strong {
  font-size: 23px;
  font-variant-numeric: tabular-nums;
}
.recovery-rows {
  display: grid;
  gap: 12px;
  margin: 20px 0;
}
.recovery-rows article {
  padding: 16px;
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.recovery-rows article > div {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}
.recovery-rows small {
  font-size: 10px;
  color: var(--atlas-muted);
}
.recovery-facts {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr);
  gap: 12px;
  font-size: 12px;
}
.recovery-facts dt {
  color: var(--atlas-muted);
}
.recovery-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.recovery-repair-result,
.recovery-task {
  margin-top: 24px;
  border-top: 1px solid var(--atlas-line);
  padding-top: 18px;
}
@media (max-width: 600px) {
  .recovery-counts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .recovery-rows article > div {
    display: grid;
  }
  .recovery-facts {
    grid-template-columns: 120px minmax(0, 1fr);
  }
}
</style>
