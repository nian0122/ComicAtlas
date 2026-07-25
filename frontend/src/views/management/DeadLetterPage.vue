<template>
  <div class="dlq-page">
    <header class="page-header">
      <div>
        <span class="eyebrow">MESSAGE RECOVERY</span>
        <h1>死信队列</h1>
        <p>检查失败消息，并在发布确认保护下重放到原始业务路由。</p>
      </div>
      <div v-if="connected" class="connection-state">
        <span aria-hidden="true" />
        已安全连接
        <el-button text @click="disconnect">断开</el-button>
      </div>
    </header>

    <DlqAccessPanel
      v-if="!connected"
      :error="accessError"
      :loading="loading"
      @connect="connect"
    />

    <template v-else>
      <section class="summary-grid" aria-label="死信队列摘要">
        <article>
          <span>受监控队列</span>
          <strong>{{ queues.length }}</strong>
        </article>
        <article>
          <span>待处理死信</span>
          <strong>{{ totalMessages }}</strong>
        </article>
        <article>
          <span>受影响队列</span>
          <strong>{{ affectedQueues }}</strong>
        </article>
      </section>

      <section v-loading="loading" class="queue-panel">
        <header class="panel-header">
          <div>
            <h2>队列账册</h2>
            <p>查看操作为只读预览；重放每批最多处理 100 条。</p>
          </div>
          <el-button :loading="loading" @click="loadQueues">刷新</el-button>
        </header>

        <div class="table-scroll">
          <el-table :data="queues" class="queue-table" empty-text="没有可用的死信队列">
            <el-table-column label="队列" min-width="220">
              <template #default="{ row }">
                <div class="queue-name">
                  <strong>{{ row.name }}</strong>
                  <span>→ {{ row.originalQueue }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="目标路由" min-width="230">
              <template #default="{ row }">
                <div class="route-cell">
                  <span>{{ row.exchange }}</span>
                  <code>{{ row.routingKey }}</code>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="消息" prop="messages" width="92" align="right">
              <template #default="{ row }">
                <span :class="['message-count', { active: row.messages > 0 }]">
                  {{ row.messages }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" align="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button :disabled="row.messages === 0" text @click="showMessages(row)">
                    预览
                  </el-button>
                  <el-button
                    :disabled="row.messages === 0"
                    :loading="replaying === row.name"
                    type="primary"
                    @click="replayQueue(row)"
                  >
                    重放
                  </el-button>
                  <el-button
                    :disabled="row.messages === 0"
                    :loading="purging === row.name"
                    type="danger"
                    plain
                    @click="purgeQueue(row)"
                  >
                    清空
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="queue-cards">
          <article v-for="queue in queues" :key="queue.name" class="queue-card">
            <header>
              <strong>{{ queue.name }}</strong>
              <span :class="['message-count', { active: queue.messages > 0 }]">
                {{ queue.messages }} 条
              </span>
            </header>
            <span>→ {{ queue.originalQueue }}</span>
            <div class="card-route">
              <span>{{ queue.exchange }}</span>
              <code>{{ queue.routingKey }}</code>
            </div>
            <div class="card-actions">
              <el-button :disabled="queue.messages === 0" text @click="showMessages(queue)">
                预览
              </el-button>
              <el-button
                :disabled="queue.messages === 0"
                :loading="replaying === queue.name"
                type="primary"
                @click="replayQueue(queue)"
              >
                重放
              </el-button>
              <el-button
                :disabled="queue.messages === 0"
                :loading="purging === queue.name"
                type="danger"
                plain
                @click="purgeQueue(queue)"
              >
                清空
              </el-button>
            </div>
          </article>
        </div>
      </section>
    </template>

    <DlqMessageDialog
      v-model:visible="dialogVisible"
      :loading="dialogLoading"
      :messages="messages"
      :queue-name="selectedQueue"
    />
  </div>
</template>

<script setup lang="ts">
import axios from 'axios'
import { computed, ref, shallowRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  adminApi,
  type DlqCredentials,
  type DlqMessageVO,
  type DlqQueueVO,
} from '@/services/api'
import DlqAccessPanel from './dlq/DlqAccessPanel.vue'
import DlqMessageDialog from './dlq/DlqMessageDialog.vue'

const credentials = shallowRef<DlqCredentials>()
const queues = ref<readonly DlqQueueVO[]>([])
const messages = ref<readonly DlqMessageVO[]>([])
const loading = ref(false)
const accessError = ref('')
const replaying = ref<string>()
const purging = ref<string>()
const dialogVisible = ref(false)
const dialogLoading = ref(false)
const selectedQueue = ref('')

const connected = computed(() => credentials.value !== undefined)
const totalMessages = computed(() =>
  queues.value.reduce((total, queue) => total + queue.messages, 0),
)
const affectedQueues = computed(
  () => queues.value.filter((queue) => queue.messages > 0).length,
)

async function connect(nextCredentials: DlqCredentials) {
  credentials.value = nextCredentials
  accessError.value = ''
  const loaded = await loadQueues()
  if (!loaded) credentials.value = undefined
}

function disconnect() {
  credentials.value = undefined
  queues.value = []
  messages.value = []
  dialogVisible.value = false
}

async function loadQueues(): Promise<boolean> {
  const auth = credentials.value
  if (!auth) return false
  loading.value = true
  try {
    const response = await adminApi.dlqQueues(auth)
    queues.value = response.data ?? []
    return true
  } catch (error: unknown) {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      accessError.value = '凭据无效，请检查用户名和密码。'
    } else {
      accessError.value = '暂时无法连接管理接口，请检查 API 服务。'
      ElMessage.error(accessError.value)
    }
    return false
  } finally {
    loading.value = false
  }
}

async function showMessages(row: DlqQueueVO) {
  const auth = credentials.value
  if (!auth) return
  selectedQueue.value = row.name
  messages.value = []
  dialogVisible.value = true
  dialogLoading.value = true
  try {
    const response = await adminApi.dlqMessages(row.name, auth)
    messages.value = response.data ?? []
  } catch {
    ElMessage.error('消息预览失败，队列内容未被修改。')
  } finally {
    dialogLoading.value = false
  }
}

async function replayQueue(row: DlqQueueVO) {
  const auth = credentials.value
  if (!auth || !(await confirmReplay(row))) return
  replaying.value = row.name
  try {
    const response = await adminApi.dlqReplay(row.name, auth)
    const result = response.data
    if (result.error) {
      ElMessage.warning(`${result.error}（已重放 ${result.replayed} 条）`)
    } else if (result.completed) {
      ElMessage.success(`重放完成：${result.replayed} 条`)
    } else {
      ElMessage.info(`本批已重放 ${result.replayed} 条，队列仍有 ${result.remaining} 条`)
    }
    await loadQueues()
  } catch {
    ElMessage.error('重放请求失败；未确认的原消息会保留在死信队列。')
  } finally {
    replaying.value = undefined
  }
}

async function purgeQueue(row: DlqQueueVO) {
  const auth = credentials.value
  if (!auth || !(await confirmPurge(row))) return
  purging.value = row.name
  try {
    const response = await adminApi.dlqPurge(row.name, auth)
    ElMessage.success(`已清空 ${response.data.purged} 条死信`)
    await loadQueues()
  } catch {
    ElMessage.error('清空失败，队列内容未确认变更。')
  } finally {
    purging.value = undefined
  }
}

async function confirmReplay(row: DlqQueueVO): Promise<boolean> {
  return confirmAction(
    `将 ${row.name} 中最多 100 条消息重放到 ${row.exchange} / ${row.routingKey}？`,
    '确认重放',
  )
}

async function confirmPurge(row: DlqQueueVO): Promise<boolean> {
  return confirmAction(
    `这会永久删除 ${row.name} 中的 ${row.messages} 条消息，且无法恢复。`,
    '永久清空死信',
    'error',
  )
}

async function confirmAction(
  message: string,
  title: string,
  type: 'warning' | 'error' = 'warning',
): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, {
      type,
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    return true
  } catch (reason: unknown) {
    if (reason === 'cancel' || reason === 'close') return false
    ElMessage.error('确认窗口异常关闭，请重试。')
    return false
  }
}
</script>

<style scoped>
.dlq-page {
  display: grid;
  gap: var(--space-6);
  width: min(100%, var(--content-max));
}

.dlq-page :deep(.el-button) {
  min-width: var(--control-min-size);
  min-height: var(--control-min-size);
}

.page-header,
.panel-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-6);
}

.eyebrow {
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 800;
  letter-spacing: var(--tracking-kicker);
}

.page-header h1 {
  margin: var(--space-2) 0;
  color: var(--text-primary);
  font-family: var(--font-editorial);
  font-size: var(--text-page);
  line-height: 1.15;
}

.page-header p,
.panel-header p {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--text-sm);
}

.connection-state {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-secondary);
  font-size: var(--text-xs);
  white-space: nowrap;
}

.connection-state > span {
  width: var(--status-dot-size);
  height: var(--status-dot-size);
  border-radius: var(--radius-pill);
  background: var(--success);
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
}

.summary-grid article {
  display: grid;
  gap: var(--space-3);
  padding: var(--space-5) var(--space-6);
}

.summary-grid article + article {
  border-left: 1px solid var(--border);
}

.summary-grid span {
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-weight: 700;
}

.summary-grid strong {
  color: var(--text-primary);
  font-size: var(--text-section);
  font-variant-numeric: tabular-nums;
}

.queue-panel {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
}

.panel-header {
  padding: var(--space-5) var(--space-6);
  border-bottom: 1px solid var(--border);
}

.panel-header h2 {
  margin: 0 0 var(--space-1);
  color: var(--text-primary);
  font-size: var(--text-lg);
}

.table-scroll {
  overflow-x: auto;
}

.queue-table {
  width: 100%;
}

.queue-name,
.route-cell {
  display: grid;
  gap: var(--space-1);
}

.queue-name strong,
.route-cell span {
  color: var(--text-primary);
  font-family: var(--mono);
  font-size: var(--text-xs);
}

.queue-name span,
.route-cell code {
  color: var(--text-muted);
  font-family: var(--mono);
  font-size: var(--text-xs);
}

.message-count {
  color: var(--text-muted);
  font-family: var(--mono);
  font-weight: 700;
}

.message-count.active {
  color: var(--warning);
}

.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-1);
}

.queue-cards {
  display: none;
}

.row-actions :deep(.el-button--danger.is-plain) {
  --el-button-bg-color: transparent;
  --el-button-border-color: var(--danger);
  --el-button-text-color: var(--danger);
  --el-button-hover-bg-color: var(--danger);
  --el-button-hover-border-color: var(--danger);
  --el-button-hover-text-color: var(--color-on-brand);
  --el-button-disabled-bg-color: transparent;
  --el-button-disabled-border-color: var(--border);
  --el-button-disabled-text-color: var(--text-muted);
}

.row-actions :deep(.el-button--danger.is-plain.is-disabled),
.card-actions :deep(.el-button--danger.is-plain.is-disabled) {
  --el-button-disabled-bg-color: transparent;
  --el-button-disabled-border-color: var(--border);
  --el-button-disabled-text-color: var(--text-muted);
  border-color: var(--border) !important;
  background-color: transparent !important;
  color: var(--text-muted) !important;
  opacity: var(--disabled-opacity);
}

@media (max-width: 768px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .summary-grid {
    grid-template-columns: 1fr;
  }

  .summary-grid article + article {
    border-top: 1px solid var(--border);
    border-left: 0;
  }

  .table-scroll {
    display: none;
  }

  .queue-cards {
    display: grid;
  }

  .queue-card {
    display: grid;
    gap: var(--space-3);
    padding: var(--space-5);
    border-bottom: 1px solid var(--border);
  }

  .queue-card:last-child {
    border-bottom: 0;
  }

  .queue-card header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
  }

  .queue-card strong,
  .queue-card > span,
  .card-route {
    font-family: var(--mono);
    font-size: var(--text-xs);
  }

  .queue-card strong {
    color: var(--text-primary);
  }

  .queue-card > span,
  .card-route code {
    color: var(--text-muted);
  }

  .card-route {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: var(--space-3);
    color: var(--text-secondary);
  }

  .card-actions {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: var(--space-2);
  }

  .card-actions :deep(.el-button) {
    width: 100%;
    margin: 0;
  }

}
</style>
