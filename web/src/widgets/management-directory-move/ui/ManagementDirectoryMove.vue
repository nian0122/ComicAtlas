<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { DirectoryTarget } from '@/entities/directory-move'
import type { Task } from '@/entities/task'
import { ManagementImage, formatBytes, taskStatusLabels } from '@/shared/ui/management'
import { AppDialog } from '@/shared/ui/dialog'
import { AppPagination } from '@/shared/ui/pagination'
import { useDirectoryMove } from '../model/use-directory-move'
const props = defineProps<{ targets: DirectoryTarget[]; taskId: string; writable: boolean }>()
const emit = defineEmits<{ close: [changed: boolean]; accepted: [task: Task]; task: [id: string] }>()
const w = reactive(useDirectoryMove(props, (task) => emit('accepted', task)))
const copied = ref('')
const states: Record<string, string> = {
  moved: '原目录项已移除',
  initially_missing: '确认原本缺失',
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    copied.value = '已复制输出目录'
  } catch {
    copied.value = '复制失败，请手动选择目录文本'
  }
}
</script>
<template>
  <AppDialog :open="true" title="移出到文件夹" :busy="w.busy" size="form" @close="emit('close', !!w.task)">
    <p class="mw-muted">
      将所选漫画的文件移到输出文件夹，整批成功后从漫画库移除。若只想保留库内内容，请改用 ZIP／CBZ 导出。
    </p>
    <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
    <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
    <section aria-label="移出作品范围" class="directory-scope">
      <div class="mw-section-tools">
        <h3>本次选择 {{ w.targets.length || '未知' }} 部</h3>
        <span class="mw-muted">整批成功后才从漫画库移除</span>
      </div>
      <div v-if="w.targets.length" class="directory-books">
        <article v-for="target in w.targets" :key="target.id">
          <ManagementImage :src="target.cover" :alt="target.title" />
          <div>
            <strong>{{ target.title }}</strong>
          </div>
        </article>
      </div>
      <p v-else class="mw-muted">当前标签页未保留原作选择；可查看任务状态和执行结果。</p>
    </section>
    <section class="directory-step">
      <h3>{{ w.task ? '执行状态' : '移出影响' }}</h3>
      <dl class="directory-effects" aria-label="移出后的影响">
        <dt>实际文件</dt>
        <dd>移动到输出文件夹，不会保留一份仍可在库内阅读的原件。</dd>
        <dt>输出资料</dt>
        <dd>保留作品资料、个人记录和隐藏内容。</dd>
        <dt>漫画库</dt>
        <dd>整批成功后移除作品、章节、媒体及相关个人记录，回收历史标为已移出。</dd>
      </dl>
      <template v-if="!w.task">
        <p>受理后不可取消。失败可能已搬移部分原件，必须保留现场并重试同一任务，不能当作普通删除清理。</p>
        <details>
          <summary>输出条件</summary>
          <p>输出需要与来源位于同一媒体卷，并支持完整目录发布；不支持时会保留现场并失败，不会覆盖或复制原件。</p>
        </details>
        <label class="directory-ack"
          ><input
            v-model="w.acknowledged"
            type="checkbox"
            :disabled="!writable || w.busy || w.loading"
          />我已核对所选作品，理解原件搬移、整批脱管及受理后不可取消</label
        >
        <button class="mw-button mw-button-danger" :disabled="!writable || !w.canSubmit" @click="w.create">
          移出到文件夹
        </button>
      </template>
      <template v-else>
        <dl class="directory-facts">
          <dt>任务状态</dt>
          <dd>{{ taskStatusLabels[w.task.status] }} · 第 {{ w.task.attempt }} 次</dd>
          <dt v-if="w.progress">文件进度</dt>
          <dd>{{ w.progress }}</dd>
        </dl>
        <p v-if="w.task.failure" class="mw-error">{{ w.task.failure.message }} {{ w.task.failure.action }}</p>
        <div class="mw-inline-actions">
          <button class="mw-button" :disabled="w.busy" @click="emit('task', w.task.id)">在任务中心查看</button>
          <button
            v-if="w.task.canRetry"
            class="mw-button mw-button-primary"
            :disabled="!writable || w.busy || w.loading"
            @click="w.retry"
          >
            重试原任务
          </button>
          <button
            v-if="w.succeeded && w.results?.directory"
            class="mw-button"
            :disabled="w.busy"
            @click="copy(w.results.directory)"
          >
            复制输出目录
          </button>
        </div>
        <p v-if="copied" role="status">{{ copied }}</p>
        <p v-if="w.succeeded && w.results?.directory" class="directory-published">
          输出目录：<code>{{ w.results.directory }}</code>
        </p>
        <template v-if="w.results">
          <p class="mw-muted">
            {{ w.succeeded ? '整批输出已发布，数据库脱管已提交。' : '逐项文件可能已搬移，但整批尚未发布和脱管。' }}
          </p>
          <details>
            <summary>逐文件记录 · {{ w.results.total }} 项</summary>
            <article v-for="item in w.results.items" :key="item.position" class="directory-file">
              <strong>{{ item.position }} · {{ states[item.state] || item.state }}</strong>
              <p>{{ formatBytes(item.size) }} · 作品 {{ item.comicId }}</p>
              <details>
                <summary>查看来源和输出位置</summary>
                <dl class="directory-facts">
                  <dt>媒体根内来源</dt>
                  <dd>
                    <code>{{ item.source }}</code>
                  </dd>
                  <dt>输出内位置</dt>
                  <dd>
                    <code>{{ item.target }}</code>
                  </dd>
                </dl>
              </details>
            </article>
            <p v-if="!w.results.items.length" class="mw-muted">尚无已持久化的逐文件记录。</p>
            <footer class="mw-pagination">
              <AppPagination
                :page="w.page"
                :pages="Math.max(1, Math.ceil(w.results.total / 20))"
                :disabled="w.loading || w.busy"
                label="移出记录分页"
                @change="w.paginate"
              />
            </footer>
          </details>
        </template>
        <p v-else-if="w.loading" role="status">正在读取任务状态…</p>
      </template>
    </section>
  </AppDialog>
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
.directory-published {
  padding: 16px;
  background: var(--atlas-raised);
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  margin-top: 18px;
  overflow-wrap: anywhere;
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
