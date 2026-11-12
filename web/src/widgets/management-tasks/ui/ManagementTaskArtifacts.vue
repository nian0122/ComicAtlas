<script setup lang="ts">
import { ref } from 'vue'
import type { TaskArtifacts } from '@/entities/task'
import { formatBytes, ManagementIcon } from '@/shared/ui/management'
defineProps<{ view: TaskArtifacts | null; loading: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
const copied = ref('')
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    copied.value = '已复制'
  } catch {
    copied.value = '复制失败，请手动选择下方文本'
  }
}
</script>
<template>
  <section class="mw-artifacts" aria-label="归档导出结果">
    <div class="mw-section-tools">
      <h3 class="mw-task-results-heading">归档文件</h3>
      <button class="mw-button" :disabled="loading" @click="emit('refresh')">
        <ManagementIcon name="refresh" :size="14" />刷新结果
      </button>
    </div>
    <p v-if="loading" role="status">正在读取产物登记…</p>
    <p v-else-if="!view" class="mw-muted">尚未取得导出结果，请重新读取。</p>
    <p v-else-if="!view.published" class="mw-muted">归档尚未完成发布，发布成功后显示文件位置。</p>
    <template v-else>
      <p class="mw-muted">文件已保存到服务端媒体目录，可按以下位置取得文件。</p>
      <div v-if="view.directory" class="mw-artifact-path">
        <code>{{ view.directory }}</code
        ><button class="mw-button" @click="copy(view.directory)">复制目录</button>
      </div>
      <article v-for="item in view.items" :key="item.fileName" class="mw-artifact-file">
        <strong>{{ item.fileName }}</strong
        ><span>{{ formatBytes(item.size) }}{{ item.lastSegment ? ' · 最后一卷' : '' }}</span>
        <div v-if="item.path" class="mw-artifact-path">
          <code>{{ item.path }}</code
          ><button class="mw-button" :aria-label="`复制${item.fileName}位置`" @click="copy(item.path)">复制位置</button>
        </div>
        <details>
          <summary>SHA-256 校验值</summary>
          <code>{{ item.sha256 }}</code
          ><button class="mw-button" :aria-label="`复制${item.fileName}校验值`" @click="copy(item.sha256)">
            复制校验值
          </button>
        </details>
      </article>
    </template>
    <p v-if="copied" role="status">{{ copied }}</p>
  </section>
</template>
