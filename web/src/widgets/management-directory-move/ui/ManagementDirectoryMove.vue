<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { DirectoryTarget } from '@/entities/directory-move'
import type { Task } from '@/entities/task'
import {
  ManagementDialog,
  ManagementIcon,
  ManagementImage,
  formatBytes,
  formatDate,
  taskStatusLabels,
} from '@/shared/ui/management'
import { useDirectoryMove } from '../model/use-directory-move'
const props = defineProps<{ targets: DirectoryTarget[]; previewId: string; writable: boolean }>()
const emit = defineEmits<{ close: [changed: boolean]; accepted: [task: Task]; task: [id: string] }>()
const w = reactive(useDirectoryMove(props, (task) => emit('accepted', task)))
const copied = ref('')
const states: Record<string, string> = {
  pending: '待处理',
  linked: '已建立输出链接，仍未脱管',
  moved: '原目录项已移除',
  initially_missing: '确认原本缺失',
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    copied.value = '已复制目录'
  } catch {
    copied.value = '复制失败，请手动选择目录文本'
  }
}
</script>
<template>
  <ManagementDialog
    :open="true"
    title="移出到文件夹"
    :busy="w.busy"
    size="form"
    @close="emit('close', !!w.move || !!w.preview?.moveTaskId || w.unresolved)"
  >
    <p class="mw-muted">将所选漫画的文件移到输出文件夹，成功后从漫画库移除。若要保留库内内容，请改用 ZIP／CBZ 导出。</p>
    <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
    <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
    <p v-if="w.storageError" class="mw-error" role="alert">{{ w.storageError }}</p>
    <section aria-label="移出作品范围" class="directory-scope">
      <div class="mw-section-tools">
        <h3>本次明确选择 {{ w.targets.length || w.preview?.bookCount || '未知' }} 部</h3>
        <span class="mw-muted">整批成功后才完成脱管</span>
      </div>
      <div v-if="w.targets.length" class="directory-books">
        <article v-for="target in w.targets" :key="target.id">
          <ManagementImage :src="w.move || w.preview?.moveTaskId ? null : target.cover" :alt="target.title" />
          <div>
            <strong>{{ target.title }}</strong>
          </div>
        </article>
      </div>
      <p v-else class="mw-muted">
        当前标签页未保存所选封面清单。可查看后台状态和执行结果；没有创建时的凭据不能确认移出。
      </p>
    </section>
    <section class="directory-step">
      <h3><span>01</span> 只读预检</h3>
      <p>先检查来源文件和输出位置是否满足移出条件，此步骤不会搬移文件。检查完成后有五分钟可核对并确认。</p>
      <div class="mw-inline-actions">
        <button class="mw-button mw-button-primary" :disabled="!writable || !w.canCreate" @click="w.create">
          {{ w.preview ? '重新预检当前作品' : '开始只读预检' }}</button
        ><button v-if="w.preview" class="mw-button" :disabled="w.loading || w.busy" @click="w.refresh">
          <ManagementIcon name="refresh" />刷新预检与结果
        </button>
      </div>
      <p v-if="w.loading" role="status">正在核对任务状态…</p>
      <template v-if="w.preview"
        ><dl class="directory-facts">
          <dt>预检状态</dt>
          <dd>{{ taskStatusLabels[w.preview.status] }}</dd>
          <dt>来源文件项</dt>
          <dd>{{ w.preview.fileCount === undefined ? '待测量' : w.preview.fileCount }}</dd>
          <dt>实测来源大小</dt>
          <dd>{{ w.preview.bytes === undefined ? '待测量' : formatBytes(w.preview.bytes) }}</dd>
          <dt>有效期至</dt>
          <dd>{{ w.preview.expiresAt ? formatDate(w.preview.expiresAt) : '完成后开始计时' }}</dd>
        </dl>
        <p v-if="w.expired && !w.unresolved && !w.move" class="mw-error">预检已过期，不能创建新的确认。请重新预检。</p>
        <p v-if="!w.receipt?.token && !w.move" class="mw-error">
          确认凭据未保留。状态查询不会再次返回凭据；可取消进行中的旧预检，或等待有效期结束后从漫画库重新预检。
        </p>
        <p v-if="w.receipt?.rejected" class="mw-error">
          原确认已被明确拒绝，不能改用新幂等键消费旧凭据。请重新预检。
        </p></template
      >
      <div v-if="w.preflight" class="directory-task">
        <button class="mw-button" :disabled="w.busy" @click="emit('task', w.preflight.id)">查看预检任务</button
        ><button
          v-if="w.preflight.canCancel && !w.move"
          class="mw-button"
          :disabled="!writable || w.busy || w.loading"
          @click="w.action('cancel')"
        >
          取消只读预检</button
        ><button
          v-if="w.preflight.canRetry && !w.move"
          class="mw-button"
          :disabled="!writable || w.busy || w.loading"
          @click="w.action('retry')"
        >
          重试同一预检任务
        </button>
        <p v-if="w.preflight.failure" class="mw-error">
          {{ w.preflight.failure.message }} {{ w.preflight.failure.action }}
        </p>
      </div>
    </section>
    <section
      v-if="w.ready || w.unresolved || w.move || w.preview?.status === 'succeeded'"
      class="directory-step directory-consent"
    >
      <h3><span>02</span> 确认搬移与脱管</h3>
      <dl class="directory-effects" aria-label="移出后的影响">
        <dt>实际文件</dt>
        <dd>移动到输出文件夹，不会保留一份仍可在库内阅读的原件。</dd>
        <dt>输出资料</dt>
        <dd>保留作品资料、个人记录和隐藏内容。</dd>
        <dt>漫画库</dt>
        <dd>整批成功后移除作品、章节、媒体及相关个人记录，回收历史标为已移出。</dd>
      </dl>
      <p>
        受理后从排队开始不可取消。失败可能已搬移部分原件，必须保留现场并重试同一任务，不能另开范围或当作普通删除清理。
      </p>
      <details>
        <summary>输出条件与失败处理</summary>
        <p>
          输出需要与来源位于同一媒体卷，并支持完整目录发布；不支持时会保留现场并失败，不会覆盖或复制原件。失败后请保留来源和输出目录，继续同一任务。
        </p>
      </details>
      <label v-if="!w.move && !w.unresolved" class="directory-ack"
        ><input
          v-model="w.acknowledged"
          type="checkbox"
          :disabled="!writable || !w.ready || w.busy || w.loading"
        />我已核对所选作品，理解原件搬移、整批脱管及受理后不可取消</label
      >
      <p v-if="w.unresolved" class="mw-error">
        上次确认结果尚未收到。请重试原确认，即使检查已过期或部分文件已搬走，也不会另建移出任务。
      </p>
      <button
        v-if="!w.move"
        class="mw-button mw-button-danger"
        :disabled="!writable || !w.canConfirm"
        @click="w.confirm"
      >
        {{ w.unresolved ? '重试原确认' : '确认移出并脱管' }}
      </button>
    </section>
    <section v-if="w.move" class="directory-step" aria-label="文件夹移出结果">
      <h3><span>03</span> 执行与发布</h3>
      <p>移出任务：{{ taskStatusLabels[w.move.status] }} · 第 {{ w.move.attempt }} 次</p>
      <p v-if="w.move.failure" class="mw-error">{{ w.move.failure.message }} {{ w.move.failure.action }}</p>
      <div class="mw-inline-actions">
        <button class="mw-button" :disabled="w.busy" @click="emit('task', w.move.id)">查看移出任务</button
        ><button
          v-if="w.move.canRetry"
          class="mw-button mw-button-primary"
          :disabled="!writable || w.busy || w.loading"
          @click="w.action('retry')"
        >
          保护重试同一移出任务
        </button>
      </div>
      <template v-if="w.results"
        ><div
          v-if="w.results.complete && w.results.status === 'succeeded' && w.results.directory"
          class="directory-published"
        >
          <strong>整批输出已发布，数据库脱管已提交</strong>
          <p>相对于服务端媒体根目录的位置：</p>
          <code>{{ w.results.directory }}</code
          ><button class="mw-button" @click="copy(w.results.directory)">复制输出目录</button>
          <p v-if="copied" role="status">{{ copied }}</p>
        </div>
        <p v-else class="mw-muted">整批尚未完成发布与脱管。逐项搬移不代表整批成功，暂不提供输出目录。</p>
        <h4>逐文件记录 · {{ w.results.total }} 项</h4>
        <article v-for="item in w.results.items" :key="item.position" class="directory-file">
          <strong>{{ item.position }} · {{ states[item.state] || item.state }}</strong>
          <p>{{ formatBytes(item.size) }} · 作品 {{ item.comicId }}</p>
          <details>
            <summary>查看来源、输出映射和校验值</summary>
            <dl class="directory-facts">
              <dt>媒体根内来源</dt>
              <dd>
                <code>{{ item.source }}</code>
              </dd>
              <dt>输出内位置</dt>
              <dd>
                <code>{{ item.target }}</code>
              </dd>
              <dt>SHA-256</dt>
              <dd>
                <code>{{ item.sha256 || '原本缺失，未记录摘要' }}</code>
              </dd>
            </dl>
          </details>
        </article>
        <p v-if="!w.results.items.length" class="mw-muted">尚无已持久化的逐文件记录。</p>
        <footer class="mw-pagination">
          <span>第 {{ w.page }} / {{ Math.max(1, Math.ceil(w.results.total / 20)) }} 页</span
          ><button class="mw-button" :disabled="w.loading || w.busy || w.page <= 1" @click="w.paginate(w.page - 1)">
            上一页移出记录</button
          ><button
            class="mw-button"
            :disabled="w.loading || w.busy || w.page * 20 >= w.results.total"
            @click="w.paginate(w.page + 1)"
          >
            下一页移出记录
          </button>
        </footer>
      </template>
    </section>
  </ManagementDialog>
</template>
<style scoped>
.directory-scope {
  margin-top: 24px;
}
.directory-books {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  max-height: 280px;
  overflow: auto;
}
.directory-books article {
  display: flex;
  gap: 12px;
  align-items: center;
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  padding: 12px;
  min-width: 0;
}
.directory-books :deep(img),
.directory-books :deep(.mw-image-empty) {
  width: 48px;
  height: 66px;
  object-fit: contain;
  flex-shrink: 0;
}
.directory-books div {
  min-width: 0;
  overflow-wrap: anywhere;
}
.directory-books strong {
  display: block;
  font-size: 13px;
}
.directory-step {
  margin-top: 24px;
  border-top: 1px solid var(--atlas-line);
  padding-top: 18px;
}
.directory-step h3 {
  font-size: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.directory-step h3 span {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--atlas-accent);
}
.directory-step p {
  font-size: 12px;
  line-height: 1.8;
}
.directory-effects {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 12px 16px;
  font-size: 13px;
  line-height: 1.7;
  margin: 20px 0;
}
.directory-effects dt {
  color: var(--atlas-muted);
}
.directory-effects dd {
  margin: 0;
}
.directory-facts {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 10px;
  font-size: 12px;
  margin: 18px 0;
}
.directory-facts dt {
  color: var(--atlas-muted);
}
.directory-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.directory-ack {
  display: flex;
  align-items: start;
  gap: 8px;
  font-size: 12px;
  line-height: 1.7;
  margin: 16px 0;
}
.directory-ack input {
  accent-color: var(--atlas-accent);
  flex-shrink: 0;
}
.directory-task {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.directory-published {
  padding: 16px;
  background: var(--atlas-raised);
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  margin-top: 18px;
  display: grid;
  gap: 10px;
}
.directory-published code {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  font-size: 12px;
}
.directory-file {
  border-bottom: 1px solid var(--atlas-line);
  padding: 14px 0;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.directory-file details {
  margin-top: 10px;
}
@media (max-width: 600px) {
  .directory-books {
    grid-template-columns: 1fr;
  }
  .directory-facts {
    grid-template-columns: 90px minmax(0, 1fr);
  }
}
</style>
