<script setup lang="ts">
import { comicCoverUrl } from '@/entities/comic'
import type { TaskItem } from '@/entities/task'
import { ManagementImage } from '@/shared/ui/management'
import { reportLabel, reportValue } from '../model/report'

defineProps<{ items: TaskItem[]; operation: string | null; busy: boolean }>()
defineEmits<{ related: [id: string, position: number, relation: 'child' | 'covered'] }>()
function taskId(value: unknown) {
  return typeof value === 'string' && value.length > 0 ? value : ''
}
function position(item: TaskItem) {
  return Number.isSafeInteger(item.position) && Number(item.position) > 0 ? Number(item.position) : null
}
function activeCover(item: TaskItem, operation: string | null) {
  return ['image_lq', 'metadata_refresh', 'video_transcode', 'hq_release'].includes(operation ?? '') &&
    taskId(item.comicId)
    ? comicCoverUrl(String(item.comicId))
    : ''
}
</script>
<template>
  <section aria-label="资源批次逐项结果">
    <p class="mw-footnote">
      这里保留批次观察到的结果。子任务之后的重试不会改写已结束批次；进入关联任务查看当前状态与逐文件结果。取消批次不会撤销已受理的不可取消操作。
    </p>
    <ol class="mw-result-list command-results">
      <li v-for="(item, index) in items" :key="position(item) ?? index">
        <div class="command-result-heading">
          <ManagementImage
            v-if="activeCover(item, operation)"
            :src="activeCover(item, operation)"
            :alt="String(item.title || '作品')"
          />
          <div>
            <span class="mw-muted">第 {{ item.position ?? index + 1 }} 项 · {{ reportValue(item.targetType) }}</span>
            <h4>{{ item.title || '未返回对象名称' }}</h4>
            <p>
              {{ reportValue(item.status)
              }}<template v-if="item.errorCode"> · {{ reportValue(item.errorCode) }}</template>
            </p>
            <p v-if="item.waitReason" class="mw-muted">等待原因：{{ reportValue(item.waitReason) }}</p>
          </div>
        </div>
        <div class="mw-inline-actions">
          <button
            v-if="taskId(item.childTaskId) && position(item) !== null"
            class="mw-button"
            :disabled="busy"
            :data-task-relation="`${item.position}:child`"
            @click="$emit('related', taskId(item.childTaskId), position(item)!, 'child')"
          >
            查看子任务
          </button>
          <button
            v-if="taskId(item.coveredTaskId) && position(item) !== null"
            class="mw-button"
            :disabled="busy"
            :data-task-relation="`${item.position}:covered`"
            @click="$emit('related', taskId(item.coveredTaskId), position(item)!, 'covered')"
          >
            查看覆盖任务
          </button>
          <span v-if="!taskId(item.childTaskId) && !taskId(item.coveredTaskId)" class="mw-muted"
            >尚无关联执行任务；阻止或未分派的项目不会生成虚构任务。</span
          >
        </div>
        <details>
          <summary>对象与执行依据</summary>
          <dl class="mw-report-fields">
            <template v-for="(value, name) in item" :key="name"
              ><dt>{{ reportLabel(name) }}</dt>
              <dd>{{ reportValue(value) }}</dd></template
            >
          </dl>
        </details>
      </li>
    </ol>
  </section>
</template>
<style scoped>
.command-result-heading {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}
.command-result-heading > :first-child:not(div) {
  width: 52px;
  height: 72px;
  flex: 0 0 52px;
}
.command-result-heading > div {
  min-width: 0;
  overflow-wrap: anywhere;
}
.command-result-heading h4 {
  margin: 4px 0;
}
.command-results > li {
  display: block;
}
.command-results .mw-inline-actions {
  margin: 12px 0;
  flex-wrap: wrap;
}
.command-results details {
  overflow-wrap: anywhere;
}
</style>
