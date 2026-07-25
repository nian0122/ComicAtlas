<template>
  <el-dialog
    :model-value="visible"
    :title="`${queueName} · 只读预览`"
    destroy-on-close
    width="min(820px, calc(100vw - 32px))"
    @update:model-value="emit('update:visible', $event)"
  >
    <p class="preview-note">
      预览不会确认或删除消息；关闭窗口后消息仍保留在原死信队列。
    </p>
    <div v-loading="loading" class="message-list">
      <el-empty v-if="!loading && messages.length === 0" description="队列中没有消息" />
      <article
        v-for="(message, index) in messages"
        :key="`${message.messagesRemaining}-${index}`"
        class="message-card"
      >
        <header>
          <span>消息 {{ index + 1 }}</span>
          <span>{{ message.payloadEncoding }} · 后续 {{ message.messagesRemaining }} 条</span>
        </header>
        <pre>{{ formatPayload(message) }}</pre>
        <details v-if="Object.keys(message.properties).length > 0">
          <summary>AMQP 属性</summary>
          <pre>{{ JSON.stringify(message.properties, null, 2) }}</pre>
        </details>
      </article>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import type { DlqMessageVO } from '@/services/api'

defineProps<{
  readonly visible: boolean
  readonly queueName: string
  readonly loading: boolean
  readonly messages: readonly DlqMessageVO[]
}>()

const emit = defineEmits<{
  'update:visible': [visible: boolean]
}>()

function formatPayload(message: DlqMessageVO): string {
  if (message.payloadEncoding === 'base64') {
    return message.payload
  }
  try {
    return JSON.stringify(JSON.parse(message.payload), null, 2)
  } catch {
    return message.payload
  }
}
</script>

<style scoped>
.preview-note {
  margin: 0 0 var(--space-4);
  padding: var(--space-3) var(--space-4);
  border-left: 2px solid var(--info);
  background: var(--bg-surface);
  color: var(--text-secondary);
  font-size: var(--text-sm);
}

.message-list {
  display: grid;
  gap: var(--space-4);
  max-height: 62vh;
  overflow: auto;
}

.message-card {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
}

.message-card header {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: var(--text-xs);
}

.message-card header span:first-child {
  color: var(--text-primary);
  font-weight: 700;
}

pre {
  max-height: 240px;
  margin: 0;
  padding: var(--space-4);
  overflow: auto;
  color: var(--text-primary);
  font-family: var(--mono);
  font-size: var(--text-xs);
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

details {
  border-top: 1px solid var(--border);
}

summary {
  min-height: 44px;
  padding: var(--space-3) var(--space-4);
  color: var(--text-secondary);
  cursor: pointer;
  font-size: var(--text-xs);
}
</style>
