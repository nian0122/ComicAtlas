<template>
  <section class="optimization-tab" data-testid="optimization-tab">
    <h2 class="opt-title">优化</h2>
    <p class="opt-subtitle">LQ 生成、HQ 删除与视频转码操作按当前状态可用性执行。</p>

    <div class="opt-actions">
      <button
        class="action-btn action-btn--primary"
        type="button"
        data-testid="opt-lq"
        :disabled="!canLq"
        title="生成 LQ 缩略图"
        @click="runLq"
      >
        {{ lqBusy ? '提交中…' : '生成 LQ' }}
      </button>
      <button
        class="action-btn action-btn--danger-ghost"
        type="button"
        data-testid="opt-delete-hq"
        :disabled="!canDeleteHq"
        title="删除 HQ 原图（保留 LQ）"
        @click="runDeleteHq"
      >
        {{ hqBusy ? '提交中…' : '删除 HQ' }}
      </button>
      <button
        class="action-btn action-btn--secondary"
        type="button"
        data-testid="opt-transcode"
        :disabled="!canTranscode"
        title="视频转码"
        @click="runTranscode"
      >
        {{ transcodeBusy ? '提交中…' : '视频转码' }}
      </button>
    </div>

    <div v-if="!canLq || !canDeleteHq || !canTranscode" class="opt-blocked">
      <p v-if="!canLq" class="blocked-line" data-testid="opt-blocked-LQ_GENERATE">
        LQ 生成不可用：{{ blockedLq }}
      </p>
      <p v-if="!canDeleteHq" class="blocked-line" data-testid="opt-blocked-HQ_DELETE">
        HQ 删除不可用：{{ blockedHq }}
      </p>
      <p v-if="!canTranscode" class="blocked-line" data-testid="opt-blocked-TRANSCODE">
        转码不可用：{{ blockedTranscode }}
      </p>
    </div>

    <p v-if="message" class="opt-message" :class="{ ok: messageKind === 'ok', err: messageKind === 'err' }" data-testid="opt-message" role="status">
      {{ message }}
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useComicWorkspaceStore } from '@/stores/management/workspace'
import { OperationName } from '@/types/management/enums'
import { storageService } from '@/services/storage'

const store = useComicWorkspaceStore()
const comicId = computed(() => store.detail?.id ?? 0)

const lqBusy = ref(false)
const hqBusy = ref(false)
const transcodeBusy = ref(false)
const message = ref('')
const messageKind = ref<'ok' | 'err'>('ok')

const canLq = computed(() => store.can(OperationName.LQ_GENERATE))
const canDeleteHq = computed(() => store.can(OperationName.HQ_DELETE))
const canTranscode = computed(() => store.can(OperationName.TRANSCODE))

const blockedLq = computed(() => store.blockedReason(OperationName.LQ_GENERATE) ?? '当前状态不允许')
const blockedHq = computed(() => store.blockedReason(OperationName.HQ_DELETE) ?? '当前状态不允许')
const blockedTranscode = computed(() => store.blockedReason(OperationName.TRANSCODE) ?? '当前状态不允许')

function setMessage(text: string, kind: 'ok' | 'err'): void {
  message.value = text
  messageKind.value = kind
}

async function runLq(): Promise<void> {
  if (!comicId.value) return
  lqBusy.value = true
  try {
    await storageService.executeOperation({ type: 'GENERATE_LQ', comicId: comicId.value })
    setMessage('LQ 生成任务已提交', 'ok')
  } catch (err: unknown) {
    setMessage(err instanceof Error ? err.message : '提交失败', 'err')
  } finally {
    lqBusy.value = false
  }
}

async function runDeleteHq(): Promise<void> {
  if (!comicId.value) return
  hqBusy.value = true
  try {
    await storageService.executeOperation({ type: 'DELETE_HQ', comicId: comicId.value })
    setMessage('HQ 删除任务已提交', 'ok')
  } catch (err: unknown) {
    setMessage(err instanceof Error ? err.message : '提交失败', 'err')
  } finally {
    hqBusy.value = false
  }
}

async function runTranscode(): Promise<void> {
  if (!comicId.value) return
  transcodeBusy.value = true
  try {
    await storageService.transcodeVideos(comicId.value)
    setMessage('转码任务已提交', 'ok')
  } catch (err: unknown) {
    setMessage(err instanceof Error ? err.message : '提交失败', 'err')
  } finally {
    transcodeBusy.value = false
  }
}
</script>

<style scoped>
.optimization-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 560px;
  min-width: 0;
}

.opt-title {
  margin: 0;
  font-size: var(--text-section);
  font-weight: 700;
  color: var(--text-primary);
}

.opt-subtitle {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-muted);
}

.opt-actions {
  display: flex;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.opt-blocked {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.blocked-line {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--warning);
}

.opt-message {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
}

.opt-message.ok { color: var(--success); }
.opt-message.err { color: var(--danger); }
</style>
