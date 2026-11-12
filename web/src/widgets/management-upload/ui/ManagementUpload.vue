<script setup lang="ts">
import { computed, reactive, ref, watch, onBeforeUnmount } from 'vue'
import { RouterLink } from 'vue-router'
import type { Task } from '@/entities/task'
import { uploadAccept } from '@/features/upload-media'
import {
  ManagementImage,
  ManagementDialog,
  ManagementIcon,
  formatBytes,
  formatDate,
  taskStatusLabels,
} from '@/shared/ui/management'
import { useMediaUpload, type UploadTarget } from '../model/use-media-upload'
const props = defineProps<{ open: boolean; target: UploadTarget | null; sessionId: string; workspaceBusy: boolean }>()
const emit = defineEmits<{ close: []; accepted: [task: Task]; inspect: [id: string] }>()
const w = reactive(useMediaUpload(props, (task) => emit('accepted', task)))
const previews = ref<Record<string, string>>({})
const selectedBytes = computed(() => w.files.reduce((total, file) => total + file.size, 0))
function revoke() {
  Object.values(previews.value).forEach((url) => URL.revokeObjectURL(url))
}
watch(
  () => w.visibleFiles,
  (files) => {
    revoke()
    previews.value = Object.fromEntries(
      files.filter((f) => /\.(jpe?g|png|gif|webp)$/i.test(f.name)).map((f) => [f.name, URL.createObjectURL(f)]),
    )
  },
)
onBeforeUnmount(revoke)

const statuses: Record<string, string> = {
  active: '接收中',
  submitted: '等待发布',
  activated: '内容已生效，正在收尾',
  completed: '发布完成',
  cancelled: '已放弃',
  expired: '已过期',
}
function close() {
  w.pause()
  emit('close')
}
function choose(event: Event) {
  w.choose((event.target as HTMLInputElement).files)
  ;(event.target as HTMLInputElement).value = ''
}
</script>
<template>
  <ManagementDialog :open="open" title="上传媒体" :busy="w.phase === 'request'" size="compact" @close="close">
    <p v-if="w.error" class="mw-error" role="alert">{{ w.error }}</p>
    <p v-if="w.notice" class="mw-notice" role="status">{{ w.notice }}</p>
    <p v-if="w.storageWarning" class="mw-muted">{{ w.storageWarning }}</p>
    <template v-if="!w.session">
      <h3 v-if="w.target">选择待上传文件</h3>
      <p v-if="w.target">
        {{ w.target.replaceMediaId ? '替换媒体' : '追加到章节' }}：{{ w.target.title
        }}<span v-if="w.target.replaceMediaId"> / {{ w.target.replaceTitle }}</span>
      </p>
      <p v-else class="mw-muted">在章节内开始上传，或通过会话编号恢复已有上传。</p>
    </template>
    <template v-else>
      <div class="upload-state">
        <ManagementIcon name="image" /><strong>{{ statuses[w.session.status] }}</strong>
      </div>
      <p class="mw-muted">
        {{ w.session.replaceMediaId ? '替换指定媒体' : '追加媒体' }} · 过期 {{ formatDate(w.session.expiresAt) }}
      </p>
      <p v-if="w.session.cleanupPending" class="mw-error">
        暂存清理尚未完成，后台将继续处理；历史接收字节不代表仍占用空间。
      </p>
      <progress aria-label="文件接收进度" :value="w.received" :max="w.session.totalBytes" class="upload-progress" />
      <p class="mw-muted">
        已确认接收 {{ formatBytes(w.received) }} / {{ formatBytes(w.session.totalBytes) }}；接收完成后仍需核对并发布。
      </p>
      <details class="upload-record">
        <summary>上传记录与传输详情</summary>
        <p class="mw-id">会话编号：{{ w.session.id }}</p>
        <p class="mw-id">章节：{{ w.session.chapterId }}</p>
        <p class="mw-muted">创建 {{ formatDate(w.session.createdAt) }} · 分片 {{ formatBytes(w.session.chunkSize) }}</p>
      </details>
      <button class="mw-button" :disabled="w.processing" @click="w.refresh">核对服务端状态</button
      ><RouterLink
        v-if="['activated', 'completed'].includes(w.session.status)"
        class="mw-button"
        :to="{ path: '/manage', query: { comic: w.session.comicId, chapter: w.session.chapterId } }"
        >返回章节查看内容</RouterLink
      >
      <template v-if="w.task"
        ><p>发布任务：{{ taskStatusLabels[w.task.status] }}</p>
        <p v-if="w.task.failure" class="mw-error">{{ w.task.failure.message }} {{ w.task.failure.action }}</p>
        <p v-if="w.session.status === 'activated'" class="mw-error">
          媒体已经生效，失败也不表示已回滚。请通过任务重试完成收尾，不能放弃已生效内容。
        </p>
        <button class="mw-button" :disabled="w.processing || workspaceBusy" @click="emit('inspect', w.task.id)">
          查看发布逐项结果</button
        ><button v-if="w.task.canRetry" class="mw-button" :disabled="w.processing" @click="w.retry">
          重试发布任务
        </button></template
      >
    </template>
    <template v-if="!w.session || (w.session.status === 'active' && !w.completeBytes)">
      <label v-if="w.target || w.session" class="upload-picker" :class="{ 'is-disabled': w.processing }">
        <input
          type="file"
          :aria-label="w.session ? '重新选择未完成的原文件' : '选择媒体文件'"
          aria-describedby="upload-file-help"
          :accept="uploadAccept"
          :multiple="!(w.session?.replaceMediaId || w.target?.replaceMediaId)"
          :disabled="w.processing"
          @change="choose"
        />
        <ManagementIcon name="upload" :size="26" />
        <span>
          <strong>{{
            w.session ? '重新选择未完成的原文件' : w.files.length ? '重新选择媒体文件' : '选择媒体文件'
          }}</strong>
          <small>{{
            w.session?.replaceMediaId || w.target?.replaceMediaId
              ? '选择一个文件，用于替换当前媒体'
              : '点击选择图片或视频，可一次选择多个文件'
          }}</small>
        </span>
      </label>
      <details class="upload-limits">
        <summary>支持格式与上传限制</summary>
        <p id="upload-file-help" class="mw-muted">
          支持常用图片及 MP4 / WebM / MOV / MKV / AVI。单文件不超过 20 GiB，单次不超过 100 GiB、10000
          个文件；媒体内容由后端再次识别。
        </p>
      </details>
      <p v-if="w.phase === 'hashing'" role="status">
        正在核对 {{ w.hashName }} 的 SHA-256：{{ formatBytes(w.hashBytes) }}
      </p>
      <p v-if="w.phase === 'sending'" role="status">正在传输 {{ w.hashName }}</p>
      <div v-if="!w.session && w.files.length" class="upload-selection" role="status">
        <strong>已选 {{ w.files.length }} 个文件</strong>
        <span>{{ formatBytes(selectedBytes) }} · 尚未上传</span>
      </div>
      <ol v-if="!w.session && w.files.length" class="upload-files" aria-label="待上传文件清单">
        <li v-for="(file, index) in w.visibleFiles" :key="file.name">
          <ManagementImage
            v-if="previews[file.name]"
            :src="previews[file.name]"
            :alt="file.name"
            class="upload-thumb"
          /><ManagementIcon v-else name="video" /> <span>{{ (w.page - 1) * 20 + index + 1 }}. {{ file.name }}</span
          ><small>{{ formatBytes(file.size) }}</small
          ><button
            class="mw-icon-button"
            :aria-label="'上移' + file.name"
            :disabled="w.processing || (w.page - 1) * 20 + index === 0"
            @click="w.reorder(index, -1)"
          >
            <ManagementIcon name="back" :size="14" /></button
          ><button
            class="mw-icon-button"
            :aria-label="'下移' + file.name"
            :disabled="w.processing || (w.page - 1) * 20 + index === w.files.length - 1"
            @click="w.reorder(index, 1)"
          >
            <ManagementIcon name="right" :size="14" />
          </button>
        </li>
      </ol>
      <p v-if="!w.session && w.files.length && !w.target?.replaceMediaId" class="mw-muted">
        按上方清单顺序追加，可先调整顺序再创建会话。
      </p>
      <p v-if="!w.session && !w.files.length && w.target" class="mw-muted upload-next">
        选择文件后核对清单，再开始上传。接收完成后还需确认发布。
      </p>
      <div v-if="w.session || w.files.length" class="mw-inline-actions upload-actions">
        <button v-if="w.phase === 'hashing' || w.phase === 'sending'" class="mw-button" @click="w.pause">
          暂停传输</button
        ><button
          v-else-if="w.target || w.session"
          class="mw-button mw-button-primary"
          :disabled="w.processing || (!w.session && !w.files.length)"
          @click="w.transfer"
        >
          {{ w.session ? '继续传输未接收分片' : '开始上传' }}
        </button>
      </div>
    </template>
    <ol v-if="w.session" class="upload-files">
      <li v-for="file in w.visibleRemote" :key="file.id">
        <span>{{ file.name }}</span
        ><small>{{ formatBytes(file.receivedBytes) }} / {{ formatBytes(file.size) }}</small>
      </li>
    </ol>
    <footer v-if="w.totalFiles > 20" class="mw-pagination">
      <button class="mw-button" :disabled="w.page <= 1 || w.processing" @click="w.page--">上一页</button
      ><span
        >{{ (w.page - 1) * 20 + 1 }}–{{ Math.min(w.page * 20, w.totalFiles) }} / {{ w.totalFiles }} 个文件 · 第
        {{ w.page }} / {{ Math.ceil(w.totalFiles / 20) }} 页</span
      ><button class="mw-button" :disabled="w.page * 20 >= w.totalFiles || w.processing" @click="w.page++">
        下一页
      </button>
    </footer>
    <section v-if="w.completeBytes" class="upload-confirm">
      <h3>确认发布</h3>
      <p v-if="w.session?.replaceMediaId" class="mw-muted">
        替换保留媒体标识、页码和阅读位置，更新内容并使旧轻量副本失效，旧文件由后端保留。发布受理后不可取消。
      </p>
      <p v-else class="mw-muted">所选文件将按清单顺序追加到章节末尾，不替换已有媒体。发布受理后不可取消。</p>
      <label class="mw-select-page"
        ><input v-model="w.acknowledged" type="checkbox" :disabled="w.processing" />已核对目标与文件，确认{{
          w.session?.replaceMediaId ? '替换' : '追加'
        }}并提交发布</label
      ><button
        class="mw-button mw-button-primary"
        :disabled="w.processing || !w.acknowledged || workspaceBusy"
        @click="w.publish"
      >
        确认发布媒体
      </button>
    </section>
    <section v-if="w.canAbandon" class="upload-confirm">
      <details>
        <summary>放弃此次上传</summary>
        <p class="mw-muted">取消未生效会话并清理暂存文件，不删除章节现有媒体。已生效会话不能放弃。</p>
        <label class="mw-select-page"
          ><input v-model="w.cancelConfirm" type="checkbox" :disabled="w.processing" />确认放弃并清理此次暂存</label
        ><button class="mw-button mw-button-danger" :disabled="w.processing || !w.cancelConfirm" @click="w.abandon">
          放弃上传会话
        </button>
      </details>
    </section>
    <details v-if="!w.processing" class="upload-restore" :open="!w.target && !w.session">
      <summary>{{ w.session ? '恢复其他上传' : '恢复已有上传' }}</summary>
      <form @submit.prevent="w.restore()">
        <label class="mw-field">会话编号<input v-model="w.restoreId" placeholder="us_…" required /></label
        ><button class="mw-button">读取会话</button>
      </form>
      <details v-if="w.recent.length">
        <summary>本机最近的会话</summary>
        <button v-for="id in w.recent" :key="id" class="mw-button upload-session-id" @click="w.restore(id)">
          {{ id }}
        </button>
      </details>
      <p class="mw-muted">
        这里只在本机记住会话编号；刷新后需要重新选择原文件，文件大小和完整摘要一致才可续传。关闭面板会暂停浏览器传输，已提交的发布任务继续执行。
      </p>
    </details>
    <button v-if="w.session && !w.processing && target" class="mw-button upload-fresh" @click="w.fresh">
      返回当前章节的新上传
    </button>
  </ManagementDialog>
</template>
<style scoped>
.upload-picker {
  position: relative;
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 24px;
  margin-top: 22px;
  background: var(--atlas-control-fill);
  border: 1px solid var(--atlas-control-border);
  border-radius: var(--atlas-radius-control);
  cursor: pointer;
  transition:
    background var(--atlas-control-transition),
    border-color var(--atlas-control-transition);
}
.upload-picker:not(.is-disabled):hover {
  background: var(--atlas-control-fill-hover);
  border-color: var(--atlas-control-border-hover);
}
.upload-picker:focus-within {
  outline: 2px solid var(--atlas-control-focus);
  outline-offset: 3px;
}
.upload-picker.is-disabled {
  opacity: var(--atlas-control-disabled-opacity);
  cursor: default;
}
.upload-picker input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: inherit;
}
.upload-picker > svg {
  flex: none;
}
.upload-picker strong,
.upload-picker small {
  display: block;
}
.upload-picker strong {
  font-size: 16px;
  font-weight: 600;
}
.upload-picker small {
  margin-top: 6px;
  color: var(--atlas-muted);
  font-size: 13px;
  line-height: 1.6;
}
.upload-limits {
  margin-top: 12px;
  font-size: 12px;
  color: var(--atlas-muted);
}
.upload-limits summary {
  cursor: pointer;
}
.upload-limits p {
  margin-bottom: 0;
}
.upload-next {
  margin-top: 24px;
  line-height: 1.7;
}
.upload-selection {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 24px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.upload-selection span {
  color: var(--atlas-muted);
}
.upload-actions {
  justify-content: flex-end;
  margin-top: 20px;
}
.upload-thumb {
  width: 44px;
  height: 58px;
  flex: 0 0 44px;
}
.upload-state {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 18px 0;
}
.upload-progress {
  width: 100%;
  accent-color: var(--atlas-accent);
}
.upload-files {
  list-style: none;
  padding: 0;
  margin: 18px 0;
}
.upload-files li {
  display: flex;
  gap: 8px;
  align-items: center;
  border-bottom: 1px solid var(--atlas-line);
  padding: 10px 0;
}
.upload-files span {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.upload-files small {
  color: var(--atlas-muted);
}
.upload-confirm,
.upload-restore {
  border-top: 1px solid var(--atlas-line);
  padding-top: 18px;
  margin-top: 22px;
}
.upload-confirm h3 {
  font-size: 15px;
}
.upload-restore > summary {
  cursor: pointer;
}
.upload-restore form,
.upload-record,
.upload-fresh {
  margin-top: 16px;
}
.upload-session-id {
  font-family: monospace;
  font-size: 11px;
  margin: 8px 0;
  max-width: 100%;
  word-break: break-all;
}
.upload-restore form {
  display: flex;
  align-items: end;
  gap: 10px;
}
.upload-restore .mw-field {
  flex: 1;
  min-width: 0;
}
.upload-restore form button {
  margin-bottom: 15px;
}
</style>
