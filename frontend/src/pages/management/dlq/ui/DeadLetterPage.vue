<template>
  <div class="dlq-page">
    <ManagementPageHeader
      title="死信队列"
      description="检查失败消息，并重放到原始业务路由。"
      eyebrow="MESSAGE RECOVERY"
    >
      <el-button :loading="loading" @click="loadQueues">刷新</el-button>
    </ManagementPageHeader>

    <StatGrid v-if="queues.length > 0" class="summary-grid" aria-label="死信队列摘要" :columns="3">
      <StatCard label="受监控队列" :value="queues.length" />
      <StatCard label="待处理死信" :value="totalMessages" />
      <StatCard label="受影响队列" :value="affectedQueues" />
    </StatGrid>

    <ManagementPanel v-loading="loading" flush class="queue-panel">
      <PanelHeader class="queue-heading" title="队列账册" description="查看操作为只读预览；重放每批最多处理 100 条。">
        <el-button :loading="loading" @click="loadQueues">刷新</el-button>
      </PanelHeader>

      <div v-if="error" class="state error">{{ error }}</div>

      <div v-else-if="queues.length > 0" class="table-scroll">
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
          <el-table-column label="消息" prop="messages" width="112" align="right">
            <template #default="{ row }">
              <span :class="['message-count', { active: row.messages > 0 }]">
                {{ row.messages }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" align="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button :disabled="row.messages === 0" text @click="showMessages(row)">预览</el-button>
                <el-button
                  :disabled="row.messages === 0"
                  :loading="replaying === row.name"
                  type="primary"
                  @click="replayQueue(row)"
                  >重放</el-button
                >
                <el-button
                  :disabled="row.messages === 0"
                  :loading="purging === row.name"
                  type="danger"
                  plain
                  @click="purgeQueue(row)"
                  >清空</el-button
                >
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else-if="!loading" class="state empty">没有可用的死信队列</div>
    </ManagementPanel>

    <DlqMessageDialog
      v-model:visible="dialogVisible"
      :loading="dialogLoading"
      :messages="messages"
      :queue-name="selectedQueue"
    />
  </div>
</template>

<script setup lang="ts">
import ManagementPanel from '@/shared/ui/management-panel/ManagementPanel.vue'
import StatGrid from '@/shared/ui/management-panel/StatGrid.vue'
import PanelHeader from '@/shared/ui/management-panel/PanelHeader.vue'
import StatCard from '@/shared/ui/management-panel/StatCard.vue'
import ManagementPageHeader from '@/shared/ui/management-panel/ManagementPageHeader.vue'
import { computed, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dlqApi } from '@/entities/dlq/api/dlq-api'
import type { DlqMessageVO, DlqQueueVO } from '@/entities/dlq/model-types'
import DlqMessageDialog from './DlqMessageDialog.vue'

const queues = ref<readonly DlqQueueVO[]>([])
const messages = ref<readonly DlqMessageVO[]>([])
const loading = ref(false)
const error = ref('')
const replaying = ref<string>()
const purging = ref<string>()
const dialogVisible = ref(false)
const dialogLoading = ref(false)
const selectedQueue = ref('')

const totalMessages = computed(() => queues.value.reduce((total, queue) => total + queue.messages, 0))
const affectedQueues = computed(() => queues.value.filter((queue) => queue.messages > 0).length)

async function loadQueues() {
  loading.value = true
  error.value = ''
  try {
    const response = await dlqApi.queues()
    queues.value = response.data ?? []
  } catch {
    error.value = '无法连接管理接口，请检查 API 服务。'
  } finally {
    loading.value = false
  }
}

async function showMessages(row: DlqQueueVO) {
  selectedQueue.value = row.name
  messages.value = []
  dialogVisible.value = true
  dialogLoading.value = true
  try {
    const response = await dlqApi.messages(row.name)
    messages.value = response.data ?? []
  } catch {
    ElMessage.error('消息预览失败')
  } finally {
    dialogLoading.value = false
  }
}

async function replayQueue(row: DlqQueueVO) {
  if (!(await confirmReplay(row))) return
  replaying.value = row.name
  try {
    const response = await dlqApi.replay(row.name)
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
    ElMessage.error('重放失败')
  } finally {
    replaying.value = undefined
  }
}

async function purgeQueue(row: DlqQueueVO) {
  if (!(await confirmPurge(row))) return
  purging.value = row.name
  try {
    const response = await dlqApi.purge(row.name)
    ElMessage.success(`已清空 ${response.data.purged} 条`)
    await loadQueues()
  } catch {
    ElMessage.error('清空失败')
  } finally {
    purging.value = undefined
  }
}

async function confirmReplay(row: DlqQueueVO): Promise<boolean> {
  try {
    await ElMessageBox.confirm(
      `将 ${row.name} 中最多 100 条消息重放到 ${row.exchange} / ${row.routingKey}？`,
      '确认重放',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
    return true
  } catch {
    return false
  }
}

async function confirmPurge(row: DlqQueueVO): Promise<boolean> {
  try {
    await ElMessageBox.confirm(`永久删除 ${row.name} 中的 ${row.messages} 条消息，无法恢复。`, '清空死信', {
      type: 'error',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    return true
  } catch {
    return false
  }
}

onMounted(loadQueues)
</script>

<style scoped>
.queue-heading {
  padding: var(--space-5);
  border-bottom: 1px solid var(--border);
}
.dlq-page {
  display: grid;
  gap: var(--space-6);
  width: 100%;
  max-width: none;
}

.table-scroll {
  overflow-x: auto;
}

.queue-table {
  min-width: 782px;
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
  min-width: 196px;
  white-space: nowrap;
}

.state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-3xl) var(--space-6);
  font-size: var(--text-sm);
}
.state.error {
  color: var(--danger);
}
.state.empty {
  color: var(--text-muted);
}
</style>
