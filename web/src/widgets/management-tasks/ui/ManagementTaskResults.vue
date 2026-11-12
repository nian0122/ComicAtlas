<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { comicCoverUrl } from '@/entities/comic'
import type { Task, TaskItem } from '@/entities/task'
import { ManagementDialog, ManagementImage, ManagementIcon } from '@/shared/ui/management'
import { reportLabel, reportValue } from '../model/report'
const props = defineProps<{ task: Task; items: TaskItem[] }>()
const preview = ref<{ id: string; kind: 'image' | 'video'; title: string } | null>(null),
  failed = ref(false),
  version = ref(0)
function retryPreview() {
  failed.value = false
  version.value++
}
function text(value: unknown) {
  return typeof value === 'string' ? value : ''
}
function kind(item: TaskItem): 'image' | 'video' | null {
  if (!text(item.mediaId)) return null
  if (['image_lq', 'hq_release'].includes(props.task.type)) return 'image'
  if (props.task.type === 'video_transcode') return 'video'
  return null
}
function outcome(item: TaskItem) {
  if (props.task.type === 'lifecycle') return reportValue(item.state)
  if (props.task.type === 'lifecycle_audit')
    return item.consistent === true ? '核对一致' : item.consistent === false ? '发现不一致' : '核对结果未完整返回'
  if (props.task.type === 'metadata_sync')
    return item.published === true ? '已发布' : item.published === false ? '尚未发布' : '发布结果未完整返回'
  return item.status === undefined ? '逐项状态未完整返回' : reportValue(item.status)
}
function title(item: TaskItem, index: number) {
  if (props.task.type === 'metadata_sync') return '侧车发布结果'
  return (
    text(item.fileName) ||
    text(item.title) ||
    `第 ${item.position ?? index + 1} 项${kind(item) === 'image' ? '图片' : kind(item) === 'video' ? '视频' : ''}`
  )
}
function open(item: TaskItem, index: number) {
  const type = kind(item)
  if (!type) return
  failed.value = false
  preview.value = { id: text(item.mediaId), kind: type, title: title(item, index) }
}
function location(item: TaskItem) {
  const comic = text(item.comicId) || props.task.comicId
  if (!comic) return null
  const chapter = text(item.chapterId) || (props.task.targetType === 'chapter' ? props.task.targetId : null)
  return { path: '/manage', query: { comic, ...(chapter ? { chapter } : {}) } }
}
</script>
<template>
  <p v-if="task.status === 'cancelled'" class="mw-muted">
    任务已取消，下方保留取消前的逐项记录，未完成项不会继续执行。
  </p>
  <ol class="mw-result-list task-result-cards">
    <li v-for="(item, index) in items" :key="index">
      <div class="task-result-main">
        <button
          v-if="kind(item)"
          class="task-result-visual"
          :aria-label="'预览' + title(item, index)"
          @click="open(item, index)"
        >
          <ManagementImage
            v-if="kind(item) === 'image'"
            :src="`/api/v1/media/${encodeURIComponent(text(item.mediaId))}/content?quality=auto`"
            :alt="title(item, index)"
          />
          <ManagementIcon v-else name="video" :size="28" />
        </button>
        <ManagementImage
          v-else-if="text(item.comicId) && task.type === 'metadata_batch'"
          class="task-result-visual"
          :src="comicCoverUrl(text(item.comicId))"
          :alt="title(item, index)"
        />
        <div class="task-result-description">
          <strong>{{ title(item, index) }}</strong>
          <p>
            {{ outcome(item) }}<template v-if="item.errorCode"> · {{ reportValue(item.errorCode) }}</template>
          </p>
          <RouterLink v-if="location(item)" class="mw-button" :to="location(item)!">{{
            location(item)?.query.chapter ? '打开所在章节' : '打开所属作品'
          }}</RouterLink>
        </div>
      </div>
      <details>
        <summary>执行依据与标识</summary>
        <dl class="mw-report-fields">
          <template v-for="(value, name) in item" :key="name"
            ><dt>{{ reportLabel(name) }}</dt>
            <dd>
              {{ reportValue(value)
              }}<code v-if="['status', 'errorCode'].includes(name) && value">（{{ value }}）</code>
            </dd></template
          >
        </dl>
      </details>
    </li>
  </ol>
  <ManagementDialog
    v-if="preview"
    :open="true"
    :title="preview.title"
    class="task-result-preview"
    @close="preview = null"
  >
    <p v-if="failed" class="mw-error" role="alert">
      媒体预览当前不可用。<button class="mw-button" @click="retryPreview">重试预览</button>
    </p>
    <video
      v-else-if="preview.kind === 'video'"
      :key="version"
      :src="`/api/v1/media/${encodeURIComponent(preview.id)}/content?quality=auto`"
      controls
      playsinline
      @error="failed = true"
    />
    <img
      v-else
      :key="version"
      :src="`/api/v1/media/${encodeURIComponent(preview.id)}/content?quality=auto`"
      :alt="preview.title"
      @error="failed = true"
    />
  </ManagementDialog>
</template>
<style scoped>
.task-result-cards > li {
  display: block;
}
.task-result-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}
.task-result-visual {
  width: 64px;
  height: 86px;
  flex: 0 0 64px;
  object-fit: contain;
  background: var(--atlas-bg);
  border: 1px solid var(--atlas-line);
  border-radius: 6px;
  padding: 0;
  display: grid;
  place-items: center;
  overflow: hidden;
}
.task-result-visual :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.task-result-description {
  min-width: 0;
  overflow-wrap: anywhere;
}
.task-result-description p {
  margin: 6px 0;
}
.task-result-cards details {
  overflow-wrap: anywhere;
}
.task-result-preview {
  width: min(1100px, calc(100vw - 32px));
  max-width: min(1100px, calc(100vw - 32px));
}
.task-result-preview img,
.task-result-preview video {
  display: block;
  max-width: 100%;
  max-height: 75dvh;
  margin: auto;
  object-fit: contain;
}
</style>
