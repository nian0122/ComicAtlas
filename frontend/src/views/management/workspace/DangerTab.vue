<template>
  <section class="danger-tab" data-testid="danger-tab">
    <h2 class="danger-title">危险操作</h2>

    <div class="danger-card">
      <div class="danger-row">
        <div class="danger-info">
          <p class="danger-name">回收漫画</p>
          <p class="danger-desc">将漫画移入回收站，可后续恢复。</p>
        </div>
        <button
          class="action-btn action-btn--danger-ghost"
          type="button"
          data-testid="danger-trash"
          :disabled="!canDelete"
          @click="confirmTrash"
        >
          回收
        </button>
      </div>
    </div>

    <p v-if="!canDelete" class="danger-blocked" data-testid="danger-blocked-DELETE">
      {{ deleteBlockedReason }}
    </p>

    <p v-if="message" class="danger-message" :class="{ err: messageKind === 'err' }" data-testid="danger-message" role="status">
      {{ message }}
    </p>

    <!-- 确认对话框 -->
    <Teleport to="body">
      <div v-if="showConfirm" class="danger-dialog-overlay" @click.self="showConfirm = false">
        <div class="danger-dialog" role="alertdialog" aria-labelledby="danger-dialog-title" aria-describedby="danger-dialog-desc">
          <h3 id="danger-dialog-title" class="danger-dialog-title">回收漫画确认</h3>
          <p id="danger-dialog-desc" class="danger-dialog-desc">
            确定回收「<strong>{{ store.detail?.title ?? '' }}</strong>」？回收后可在回收站恢复。
          </p>
          <div class="danger-dialog-actions">
            <button class="action-btn action-btn--ghost" type="button" @click="showConfirm = false">取消</button>
            <button
              class="action-btn action-btn--danger-filled"
              type="button"
              :disabled="trashBusy"
              @click="runTrash"
            >
              <template v-if="trashBusy">
                <span class="action-btn-spinner" aria-hidden="true" />
                <span>回收中</span>
              </template>
              <template v-else>确认回收</template>
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useComicWorkspaceStore } from '@/stores/management/workspace'
import { workspaceApi, workspaceErrorMessage } from '@/services/management/workspace'
import { OperationName } from '@/types/management/enums'

const store = useComicWorkspaceStore()
const router = useRouter()

const showConfirm = ref(false)
const trashBusy = ref(false)
const message = ref('')
const messageKind = ref<'ok' | 'err'>('ok')

const canDelete = computed(() => store.can(OperationName.DELETE))
const deleteBlockedReason = computed(() => store.blockedReason(OperationName.DELETE) ?? '当前状态不允许删除')

function confirmTrash(): void {
  showConfirm.value = true
}

async function runTrash(): Promise<void> {
  const id = store.detail?.id
  if (!id) return
  trashBusy.value = true
  try {
    await workspaceApi.trashComic(id)
    message.value = '已提交回收任务'
    messageKind.value = 'ok'
    showConfirm.value = false
    router.push('/manage/comics')
  } catch (err: unknown) {
    message.value = workspaceErrorMessage(err, '回收失败')
    messageKind.value = 'err'
    showConfirm.value = false
  } finally {
    trashBusy.value = false
  }
}
</script>

<style scoped>
.danger-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 560px;
  min-width: 0;
}

.danger-title {
  margin: 0;
  font-size: var(--text-section);
  font-weight: 700;
  color: var(--text-primary);
}

.danger-card {
  padding: var(--space-5);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px solid var(--danger);
}

.danger-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.danger-info {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
}

.danger-name {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 700;
  color: var(--text-primary);
}

.danger-desc {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.danger-blocked {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--warning);
}

.danger-message {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
}

.danger-message.err { color: var(--danger); }
</style>
