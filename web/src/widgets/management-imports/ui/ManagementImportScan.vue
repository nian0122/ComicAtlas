<script setup lang="ts">
import { reactive } from 'vue'
import type { ImportCandidate } from '@/entities/import-scan'
import type { Task } from '@/entities/task'
import { ManagementDialog, ManagementIcon, taskStatusLabels, taskStageLabel } from '@/shared/ui/management'
import { useImportScan } from '../model/use-import-scan'
const props = defineProps<{ open: boolean; taskId: string; workspaceBusy: boolean }>()
const emit = defineEmits<{ close: []; accepted: [task: Task]; inspect: [id: string] }>()
const w = reactive(useImportScan(props, (task) => emit('accepted', task)))
function toggle(candidate: ImportCandidate, event: Event) {
  w.toggle(candidate)
  ;(event.target as HTMLInputElement).checked = w.selected.some((c) => c.id === candidate.id)
}
</script>
<template>
  <ManagementDialog :open="open" title="扫描与批量导入" :busy="w.busy" placement="right" @close="emit('close')">
    <p class="mw-muted">
      多本目录先扫描父目录，再勾选漫画；ZIP / CBZ 可直接提交路径列表。每本独立导入，结果在任务中心查看。
    </p>
    <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
    <template v-if="w.batch">
      <div class="mw-notice" role="status">
        批量导入{{ taskStatusLabels[w.batch.status] }}，已处理 {{ w.batch.completed }} / {{ w.batch.total }} 本。 成功
        {{ w.batch.items.filter((i) => i.status === 'succeeded').length }} 本，失败
        {{ w.batch.items.filter((i) => i.status === 'failed').length }} 本，取消
        {{ w.batch.items.filter((i) => i.status === 'cancelled').length }} 本。
      </div>
      <div class="mw-inline-actions">
        <button class="mw-button" :disabled="w.busy || w.loading" @click="w.refresh">刷新结果</button>
        <button
          class="mw-button mw-button-primary"
          :disabled="w.busy || workspaceBusy"
          @click="emit('inspect', w.batch.taskId)"
        >
          查看逐本导入结果
        </button>
        <button
          class="mw-button"
          :disabled="w.busy || ['queued', 'running', 'cancelling'].includes(w.batch.status)"
          @click="w.returnCandidates"
        >
          {{ w.scan ? '返回扫描候选' : '导入其他压缩包' }}
        </button>
      </div>
      <p class="mw-muted">可以关闭面板，稍后从任务中心跟进同一批次。取消不会撤销已经成功导入的漫画。</p>
    </template>
    <p v-else-if="w.loading && !w.scan" class="mw-muted">正在恢复扫描任务…</p>
    <template v-else-if="!w.scan">
      <fieldset class="scan-source" :disabled="w.busy">
        <legend>导入来源</legend>
        <label><input v-model="w.mode" type="radio" value="directory" />漫画集目录</label>
        <label><input v-model="w.mode" type="radio" value="archives" />ZIP / CBZ 路径列表</label>
      </fieldset>
      <form v-if="w.mode === 'directory'" @submit.prevent="w.start">
        <label class="mw-field"
          >扫描绝对路径<input
            v-model="w.source"
            required
            :disabled="w.busy"
            placeholder="例如 D:\ComicAtlas-import\漫画集"
        /></label>
        <p class="mw-muted">
          填写宿主机导入根内的完整路径，Docker
          自动映射。只枚举直接子目录，每个子目录作为一本候选；扫描阶段不解析结构或统计媒体。
        </p>
        <footer class="mw-dialog-footer">
          <button class="mw-button mw-button-primary" :disabled="w.busy">
            {{ w.busy ? '正在提交…' : '开始扫描' }}
          </button>
        </footer>
      </form>
      <form v-else @submit.prevent="w.submitArchives">
        <label class="mw-field"
          >压缩包绝对路径列表<textarea
            v-model="w.archives"
            required
            :disabled="w.busy"
            rows="8"
            placeholder="D:\ComicAtlas-import\book-a.cbz&#10;D:\ComicAtlas-import\book-b.zip"
          />
        </label>
        <p class="mw-muted">
          每行一个 ZIP / CBZ，最多 1000 个，当前 {{ w.archiveCount }} 个。压缩包会解压后导入，原包保留。
        </p>
        <label class="mw-select-page"
          ><input
            v-model="w.confirm"
            type="checkbox"
            :disabled="w.busy"
          />我已核对压缩包列表，确认创建本次导入批次。</label
        >
        <footer class="mw-dialog-footer">
          <button class="mw-button mw-button-primary" :disabled="w.busy || !w.confirm || !w.archiveCount">
            {{ w.busy ? '正在提交…' : '导入压缩包列表' }}
          </button>
        </footer>
      </form>
    </template>
    <template v-else>
      <div class="scan-status">
        <ManagementIcon :name="w.active ? 'loading' : 'folder'" :class="{ 'mw-spin': w.active }" />
        <div>
          <strong>{{ taskStatusLabels[w.scan.status] }}</strong>
          <p class="mw-muted">{{ taskStageLabel(w.scan.stage) }} · 已处理 {{ w.scan.completedCount }} 项</p>
        </div>
      </div>
      <p v-if="w.scan.failure" class="mw-error">{{ w.scan.failure.message }} {{ w.scan.failure.action }}</p>
      <div class="mw-inline-actions">
        <button class="mw-button" :disabled="w.busy || w.loading" @click="w.refresh">刷新结果</button>
        <button v-if="w.scan.canCancel" class="mw-button" :disabled="w.busy" @click="w.cancel">取消扫描</button>
        <button v-if="w.scan.canRetry" class="mw-button" :disabled="w.busy" @click="w.retry">重试扫描</button>
        <button class="mw-button" :disabled="w.busy || workspaceBusy" @click="emit('inspect', w.scan.id)">
          查看扫描任务
        </button>
        <button v-if="!w.active" class="mw-button" :disabled="w.busy" @click="w.reset">扫描其他位置</button>
      </div>
      <template v-if="w.scan.status === 'succeeded'">
        <div class="scan-results-heading">
          <p class="mw-muted">{{ w.total }} 个候选 · 已选 {{ w.selected.length }} 本（跨页保留）</p>
          <button v-if="w.total" class="mw-button" :disabled="w.busy || w.loading" @click="w.togglePage">
            {{ w.pageSelected ? '取消本页' : '选择本页' }}
          </button>
        </div>
        <p v-if="w.loading" class="mw-muted">正在读取候选…</p>
        <div v-else class="scan-candidates">
          <article
            v-for="candidate in w.candidates"
            :key="candidate.id"
            class="scan-candidate"
            :class="{ 'scan-blocked': !candidate.selectable }"
          >
            <div class="scan-candidate-heading">
              <input
                type="checkbox"
                :aria-label="'选择候选' + candidate.title"
                :checked="w.selected.some((c) => c.id === candidate.id)"
                :disabled="w.busy || !candidate.selectable"
                @change="toggle(candidate, $event)"
              /><ManagementIcon name="folder" /><strong>{{ candidate.title }}</strong
              ><span class="mw-muted">{{ candidate.selectable ? '漫画目录' : '不可选择' }}</span>
            </div>
            <p class="mw-id">{{ candidate.sourcePath }}</p>
          </article>
        </div>
        <footer v-if="w.total > 20" class="mw-pagination">
          <button class="mw-button" :disabled="w.page <= 1 || w.loading || w.busy" @click="w.results(w.page - 1)">
            上一页</button
          ><span>第 {{ w.page }} / {{ Math.ceil(w.total / 20) }} 页</span
          ><button
            class="mw-button"
            :disabled="w.page * 20 >= w.total || w.loading || w.busy"
            @click="w.results(w.page + 1)"
          >
            下一页
          </button>
        </footer>
        <p v-if="!w.total && !w.loading" class="mw-muted">没有可导入的直接子目录，请检查位置后重新扫描。</p>
        <section v-if="w.selected.length" class="scan-selection">
          <h3>本次导入 {{ w.selected.length }} 本</h3>
          <details>
            <summary>核对完整选集</summary>
            <ul>
              <li v-for="candidate in w.selected" :key="candidate.id">
                {{ candidate.title }}<small>{{ candidate.sourcePath }}</small
                ><button class="mw-button" :disabled="w.busy" @click="w.toggle(candidate)">取消选择</button>
              </li>
            </ul>
          </details>
          <p class="mw-muted">
            导入会将所选目录中的媒体移动到媒体库，跨文件系统时核验复制后删除对应源文件。每本的结构与媒体在导入任务中解析。
          </p>
          <label class="mw-select-page"
            ><input
              v-model="w.confirm"
              type="checkbox"
              :disabled="w.busy"
            />我已核对导入范围，确认移动所选漫画的媒体。</label
          >
          <footer class="mw-dialog-footer">
            <button class="mw-button mw-button-primary" :disabled="w.busy || !w.confirm" @click="w.submit">
              {{ w.busy ? '正在提交…' : '导入所选作品' }}
            </button>
          </footer>
        </section>
      </template>
      <p v-else-if="w.active" class="mw-muted">
        扫描在后台执行，可以关闭面板，稍后从任务中心重新打开。仅成功扫描提供完整候选。
      </p>
    </template>
  </ManagementDialog>
</template>
<style scoped>
.scan-source {
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  padding: 14px;
  margin: 18px 0;
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
}
.scan-source legend {
  font-size: 13px;
  color: var(--atlas-muted);
  padding: 0 6px;
}
.scan-source label {
  display: flex;
  align-items: center;
  gap: 8px;
}
.scan-status,
.scan-candidate-heading,
.scan-results-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 18px 0;
}
.scan-status p {
  margin: 5px 0 0;
}
.scan-results-heading {
  justify-content: space-between;
}
.scan-candidates {
  display: grid;
  gap: 12px;
  margin-top: 18px;
}
.scan-candidate {
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  padding: 14px;
  background: var(--atlas-surface);
}
.scan-candidate .mw-id {
  overflow-wrap: anywhere;
}
.scan-candidate-heading {
  margin: 0;
}
.scan-candidate-heading strong {
  flex: 1;
  overflow-wrap: anywhere;
}
.scan-blocked {
  border-style: dashed;
}
.scan-selection {
  position: sticky;
  bottom: 0;
  background: var(--atlas-raised);
  border-top: 1px solid var(--atlas-control-border);
  padding-top: 12px;
}
.scan-selection h3 {
  font-size: 15px;
}
.scan-selection details ul {
  max-height: 180px;
  overflow: auto;
  padding: 0;
  list-style: none;
}
.scan-selection details li {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  padding: 8px 0;
}
.scan-selection small {
  color: var(--atlas-muted);
  overflow-wrap: anywhere;
}
</style>
