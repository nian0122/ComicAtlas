<script setup lang="ts">
import { AppSearch } from '@/shared/ui/search'
import { reactive } from 'vue'
import { entryStates, lifecycleOperations, fileFacts, fileRoles, auditIssues } from '@/entities/lifecycle'
import type { Task } from '@/entities/task'
import type { TrashEntry } from '@/entities/trash'
import { ManagementDialog, ManagementIcon, ManagementImage, formatDate, taskStatusLabels } from '@/shared/ui/management'
import { useLifecycle } from '../model/use-lifecycle'
const props = defineProps<{
  open: boolean
  entryId: string
  initialState?: TrashEntry['state']
  writable: boolean
}>()
const emit = defineEmits<{ close: []; accepted: [task: Task]; task: [id: string] }>()
const w = reactive(useLifecycle(props, (task) => emit('accepted', task)))
function historyPage(next: number) {
  w.page = next
  void w.list()
}
const types = { comic: '漫画', chapter: '章节', media: '媒体' }
// 已永久清理的条目不再保留文件清单，不能发起新的核对。
const auditStates = Object.fromEntries(Object.entries(entryStates).filter(([value]) => value !== 'purged'))
</script>
<template>
  <ManagementDialog
    :open="open"
    title="回收文件核对"
    :busy="w.busy"
    placement="right"
    size="results"
    @close="emit('close')"
  >
    <p class="mw-muted">按回收历史核对已登记文件，不扫描未知目录。检查不会修改文件或内容状态；继续原操作需另行确认。</p>
    <div class="lifecycle-layout">
      <section class="lifecycle-history" aria-label="回收历史选择">
        <form @submit.prevent="w.filters">
          <label class="mw-field"
            >历史状态<select
              v-model="w.state"
              aria-label="核对历史状态"
              :disabled="w.busy || w.listLoading"
              @change="w.filters"
            >
              <option v-for="(label, value) in auditStates" :key="value" :value="value">{{ label }}</option>
            </select></label
          >
          <label class="mw-field"
            >名称<AppSearch
              v-model="w.keyword"
              label="搜索核对历史"
              placeholder="搜索回收对象"
              :disabled="w.busy"
              @search="w.filters"
          /></label>
          <button class="mw-button" :disabled="w.busy || w.listLoading">查询历史</button>
        </form>
        <p v-if="w.listError" class="mw-error" role="alert">{{ w.listError }}</p>
        <p v-if="w.listLoading">正在读取历史…</p>
        <p v-else-if="!w.entries.length && !w.listError" class="mw-muted">该状态暂无历史，可切换已回收或已恢复查看。</p>
        <div v-else class="lifecycle-entries">
          <button
            v-for="item in w.entries"
            :key="item.id"
            class="lifecycle-entry"
            :class="{ active: w.entry?.id === item.id }"
            :disabled="w.busy"
            :aria-label="'核对历史' + item.target.title"
            @click="w.select(item)"
          >
            <ManagementImage :src="item.coverUrl ?? null" :alt="item.target.title" />
            <span
              ><strong>{{ item.target.title }}</strong
              ><small>{{ types[item.target.targetType] }} · {{ entryStates[item.state] }}</small
              ><small>{{ formatDate(item.updatedAt) }}</small></span
            >
          </button>
        </div>
        <footer class="mw-pagination">
          <span>{{ w.total }} 条 · 第 {{ w.page }} 页</span
          ><button
            class="mw-button"
            :disabled="w.busy || w.listLoading || w.page <= 1"
            @click="historyPage(w.page - 1)"
          >
            上一页</button
          ><button
            class="mw-button"
            :disabled="w.busy || w.listLoading || w.page * 20 >= w.total"
            @click="historyPage(w.page + 1)"
          >
            下一页
          </button>
        </footer>
      </section>
      <section class="lifecycle-report" aria-label="回收核对报告">
        <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
        <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
        <p v-if="w.loading" role="status">正在读取报告和当前任务…</p>
        <div v-if="w.entry">
          <div class="lifecycle-target">
            <ManagementImage :src="w.entry.coverUrl ?? null" :alt="w.entry.target.title" />
            <div>
              <h3>{{ w.entry.target.title }}</h3>
              <p>{{ types[w.entry.target.targetType] }} · {{ entryStates[w.entry.state] }}</p>
              <small class="mw-muted">回收历史 {{ w.entry.id }}</small>
            </div>
          </div>
          <p v-if="w.entry.state === 'purged'" class="mw-muted">
            该对象已永久清理，文件清单和回收目录已删除；不能再发起新的核对。
          </p>
          <div class="mw-inline-actions">
            <button class="mw-button" :disabled="w.loading || w.busy" @click="w.refresh">
              <ManagementIcon name="refresh" />刷新报告
            </button>
            <button
              class="mw-button mw-button-primary"
              :disabled="!writable || w.loading || w.busy || w.running || w.entry.state === 'purged'"
              @click="w.reconcile"
            >
              {{ w.pendingKey ? '重试受理核对' : '开始新核对' }}
            </button>
            <button v-if="w.entry.taskId" class="mw-button" :disabled="w.busy" @click="emit('task', w.entry.taskId)">
              查看历史任务
            </button>
          </div>
          <p v-if="!w.report && !w.loading && w.fresh" class="mw-muted">尚无核对报告，可开始只读检查。</p>
          <div v-if="w.auditTask" class="lifecycle-task">
            <strong>核对任务：{{ taskStatusLabels[w.auditTask.status] }}</strong>
            <button class="mw-button" :disabled="w.busy" @click="emit('task', w.auditTask.id)">查看核对任务</button>
            <button v-if="w.auditTask.canCancel" class="mw-button" :disabled="!writable || w.busy" @click="w.cancel">
              取消核对任务
            </button>
          </div>
          <template v-if="w.report">
            <div class="lifecycle-verdict" :class="{ 'lifecycle-issue': !w.report.consistent }">
              <h3>
                {{
                  !w.report.complete
                    ? '检查尚未完整'
                    : w.report.consistent
                      ? '登记范围与观察一致'
                      : '检查完整，仍有不一致'
                }}
              </h3>
              <p>
                已核对 {{ w.report.checkedFiles }} / {{ w.report.totalFiles }} 个文件 · 文件问题
                {{ w.report.issueFiles }} · 条目问题 {{ w.report.entryIssues }}
              </p>
              <p v-if="w.report.superseded">此历史已被新的回收记录取代，不能据此继续原操作。</p>
              <small
                >观察时间 {{ formatDate(w.report.observedAt) }}。报告只代表当时的登记范围，不是文件系统原子快照。</small
              >
            </div>
            <p v-if="w.report.ownerTaskId" class="mw-muted">
              原操作：{{ lifecycleOperations[w.report.operation] || w.report.operation || '检查中' }} ·
              {{ w.owner ? taskStatusLabels[w.owner.status] : w.report.ownerState }}
            </p>
            <p v-if="!w.fresh && !w.loading" class="mw-error">报告未能刷新，下面保留旧观察，已禁止修复。</p>
            <h4>逐文件观察</h4>
            <div v-if="w.observations" class="lifecycle-files">
              <article v-for="item in w.observations.items" :key="item.position" class="lifecycle-file">
                <strong>{{ item.position }} · {{ item.fileName }}</strong
                ><small
                  >{{ fileRoles[item.role] || item.role }} ·
                  {{ item.consistent ? '观察一致' : item.resumable ? '可继续原操作' : '需人工处理' }}</small
                >
                <p>
                  原位置：{{ fileFacts[item.sourceState] || item.sourceState }} · 保留位置：{{
                    fileFacts[item.retainedState] || item.retainedState
                  }}
                </p>
                <p v-if="item.errorCode" class="mw-error">{{ auditIssues[item.errorCode] || item.errorCode }}</p>
                <details>
                  <summary>查看清单事实</summary>
                  <p>
                    清单{{ item.prepared ? '已准备' : '未准备' }} ·
                    {{ fileFacts[item.journalState] || item.journalState }} · 阶段
                    {{ fileFacts[item.journalPhase] || item.journalPhase || '未记录' }}
                  </p>
                  <p>
                    原本缺失：{{ item.initiallyMissing ? '是' : '否' }} · 两端为同一文件：{{
                      item.sameFile ? '是' : '否'
                    }}
                  </p>
                  <small>所属回收历史 {{ item.entryId }}</small>
                </details>
              </article>
              <p v-if="!w.observations.items.length" class="mw-muted">尚无已持久化的文件观察。条目问题仍按摘要显示。</p>
              <footer class="mw-pagination">
                <span>观察 {{ w.observations.total }} 项 · 第 {{ w.observationPage }} 页</span
                ><button
                  class="mw-button"
                  :disabled="w.loading || w.busy || w.observationPage <= 1"
                  @click="w.paginate('observations', w.observationPage - 1)"
                >
                  上一页观察</button
                ><button
                  class="mw-button"
                  :disabled="w.loading || w.busy || w.observationPage * 20 >= w.observations.total"
                  @click="w.paginate('observations', w.observationPage + 1)"
                >
                  下一页观察
                </button>
              </footer>
            </div>
            <section class="lifecycle-repair">
              <h4>继续原失败操作</h4>
              <p v-if="!w.canRepair" class="mw-muted">
                当前报告或任务状态不允许继续。外部冲突处理后可重新核对；不会自动覆盖文件或直接补写成功状态。
              </p>
              <template v-else
                ><p>
                  将继续“{{ lifecycleOperations[w.report.operation] || w.report.operation }}”，沿用原任务
                  {{ w.report.ownerTaskId }}。{{
                    w.report.operation === 'purge'
                      ? '这会继续永久删除原已确认范围，已删除文件不能撤回。'
                      : '文件会按原持久清单继续搬移并再次核验。'
                  }}修复不保证成功，也不等同于恢复回收内容。
                </p>
                <p v-if="w.report.operation === 'restore'">
                  单个媒体恢复后追加到原章节末尾，章节追加到原目录末尾；阅读进度跟随媒体身份。
                </p>
                <p>如果原任务包含多个独立条目，将继续整个原范围，不能只处理当前缩略图。</p>
                <label class="lifecycle-ack"
                  ><input
                    v-model="w.acknowledged"
                    type="checkbox"
                    :disabled="!writable || w.busy || w.loading"
                  />我已核对原操作与完整范围，继续执行</label
                ></template
              >
              <button
                class="mw-button mw-button-danger"
                :disabled="!writable || w.busy || w.loading || !w.canRepair || !w.acknowledged"
                @click="w.repair"
              >
                继续原失败操作
              </button>
            </section>
            <section v-if="w.results" class="lifecycle-execution">
              <h4>原操作执行结果</h4>
              <p v-if="!w.results.recorded && w.results.operation === 'purge'" class="mw-muted">
                永久清理按目录删除，不生成逐文件结果；以任务状态和条目状态判断完成。
              </p>
              <p v-else-if="!w.results.recorded" class="mw-muted">暂无已登记的执行报告，不据此判断任务失败。</p>
              <template v-else
                ><p>
                  {{ w.results.complete ? '执行与业务收尾已完整提交' : '执行结果尚未完整提交' }} · 已达目标
                  {{ w.results.completedFiles }} / {{ w.results.totalFiles ?? '未知' }} · 明确失败
                  {{ w.results.failedFiles }}
                </p>
                <p v-if="w.results.attempt !== w.results.taskAttempt" class="mw-error">
                  以下为第 {{ w.results.attempt }} 次旧执行记录，当前任务为第
                  {{ w.results.taskAttempt }} 次，不能代表当前尝试。
                </p>
                <article v-for="item in w.results.items" :key="item.position" class="lifecycle-file">
                  <strong>{{ item.position }} · {{ item.fileName }}</strong>
                  <p>
                    {{ fileRoles[item.role] || item.role }} · {{ fileFacts[item.phase] || item.phase }} ·
                    {{ fileFacts[item.state] || item.state }}
                  </p>
                  <p v-if="item.errorCode" class="mw-error">{{ auditIssues[item.errorCode] || item.errorCode }}</p>
                  <small>{{ formatDate(item.observedAt) }}</small>
                </article>
                <footer class="mw-pagination">
                  <span>记录 {{ w.results.total }} 项 · 第 {{ w.resultPage }} 页</span
                  ><button
                    class="mw-button"
                    :disabled="w.loading || w.busy || w.resultPage <= 1"
                    @click="w.paginate('results', w.resultPage - 1)"
                  >
                    上一页执行记录</button
                  ><button
                    class="mw-button"
                    :disabled="w.loading || w.busy || w.resultPage * 20 >= w.results.total"
                    @click="w.paginate('results', w.resultPage + 1)"
                  >
                    下一页执行记录
                  </button>
                </footer></template
              >
            </section>
          </template>
        </div>
        <div v-else-if="!w.loading && !w.error" class="mw-empty">
          <ManagementIcon name="scan" :size="36" />
          <h3>选择一条回收历史</h3>
          <p>核对围绕历史登记范围进行，不以当前封面判断文件健康。</p>
        </div>
      </section>
    </div>
  </ManagementDialog>
</template>
<style scoped>
.lifecycle-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 28px;
  margin-top: 24px;
  align-items: start;
}
.lifecycle-history {
  min-width: 0;
  border-right: 1px solid var(--atlas-line);
  padding-right: 20px;
}
.lifecycle-entries {
  display: grid;
  gap: 8px;
  margin-top: 16px;
}
.lifecycle-entry {
  display: flex;
  gap: 12px;
  padding: 10px;
  width: 100%;
  text-align: left;
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  background: var(--atlas-surface);
  color: var(--atlas-text);
  cursor: pointer;
}
.lifecycle-entry.active {
  border-color: var(--atlas-accent);
  background: var(--atlas-raised);
}
.lifecycle-entry :deep(img),
.lifecycle-entry :deep(.mw-image-empty) {
  width: 44px;
  height: 60px;
  object-fit: contain;
  flex-shrink: 0;
}
.lifecycle-entry span {
  min-width: 0;
  display: grid;
  gap: 5px;
}
.lifecycle-entry strong {
  font-size: 12px;
  overflow-wrap: anywhere;
}
.lifecycle-entry small {
  font-size: 10px;
  color: var(--atlas-muted);
}
.lifecycle-report {
  min-width: 0;
}
.lifecycle-target {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}
.lifecycle-target :deep(img),
.lifecycle-target :deep(.mw-image-empty) {
  width: 64px;
  height: 88px;
  object-fit: contain;
  flex-shrink: 0;
}
.lifecycle-target div {
  min-width: 0;
  overflow-wrap: anywhere;
}
.lifecycle-target h3 {
  margin: 0 0 8px;
}
.lifecycle-target small {
  font-size: 10px;
}
.lifecycle-task {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 20px;
  font-size: 12px;
}
.lifecycle-verdict {
  padding: 16px;
  background: var(--atlas-raised);
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  margin-top: 20px;
}
.lifecycle-verdict h3 {
  margin: 0 0 10px;
  font-size: 16px;
}
.lifecycle-verdict small {
  color: var(--atlas-muted);
}
.lifecycle-issue {
  border-left: 3px solid var(--atlas-accent);
}
.lifecycle-file {
  padding: 12px 0;
  border-bottom: 1px solid var(--atlas-line);
  overflow-wrap: anywhere;
  font-size: 12px;
}
.lifecycle-file strong,
.lifecycle-file small {
  display: block;
}
.lifecycle-file small {
  color: var(--atlas-muted);
  margin-top: 6px;
}
.lifecycle-file details {
  margin-top: 12px;
  color: var(--atlas-muted);
}
.lifecycle-repair {
  margin-top: 22px;
  padding: 16px;
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
}
.lifecycle-ack {
  display: flex;
  gap: 8px;
  align-items: start;
  margin: 14px 0;
  font-size: 12px;
}
.lifecycle-ack input {
  accent-color: var(--atlas-accent);
  flex-shrink: 0;
}
.lifecycle-execution {
  margin-top: 24px;
}
@media (max-width: 760px) {
  .lifecycle-layout {
    grid-template-columns: 1fr;
    gap: 24px;
  }
  .lifecycle-history {
    border-right: 0;
    border-bottom: 1px solid var(--atlas-line);
    padding: 0 0 16px;
  }
  .lifecycle-entries {
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    max-height: 240px;
    overflow: auto;
  }
}
</style>
