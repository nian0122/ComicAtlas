<template>
  <div class="dlq-page">
    <header class="page-header">
      <div>
        <span class="eyebrow">MESSAGE RECOVERY</span>
        <h1>死信队列</h1>
        <p>检查失败消息，并重放到原始业务路由。</p>
      </div>
      <el-button :loading="loading" @click="loadQueues">刷新</el-button>
    </header>

    <section class="summary-grid" aria-label="死信队列摘要" v-if="queues.length > 0">
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

      <div v-if="error" class="state error">{{ error }}</div>

      <div class="table-scroll" v-else-if="queues.length > 0">
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
                <el-button :disabled="row.messages === 0" text @click="showMessages(row)">预览</el-button>
                <el-button :disabled="row.messages === 0" :loading="replaying === row.name" type="primary" @click="replayQueue(row)">重放</el-button>
                <el-button :disabled="row.messages === 0" :loading="purging === row.name" type="danger" plain @click="purgeQueue(row)">清空</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else-if="!loading" class="state empty">没有可用的死信队列</div>
    </section>

    <DlqMessageDialog
      v-model:visible="dialogVisible"
      :loading="dialogLoading"
      :messages="messages"
      :queue-name="selectedQueue"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, type DlqMessageVO, type DlqQueueVO } from '@/services/api'
import DlqMessageDialog from './dlq/DlqMessageDialog.vue'

const queues = ref<readonly DlqQueueVO[]>([])
const messages = ref<readonly DlqMessageVO[]>([])
const loading = ref(false)
const error = ref('')
const replaying = ref<string>()
const purging = ref<string>()
const dialogVisible = ref(false)
const dialogLoading = ref(false)
const selectedQueue = ref('')

const totalMessages = computed(() =>
  queues.value.reduce((total, queue) => total + queue.messages, 0),
)
const affectedQueues = computed(
  () => queues.value.filter((queue) => queue.messages > 0).length,
)

async function loadQueues() {
  loading.value = true
  error.value = ''
  try {
    const response = await adminApi.dlqQueues()
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
    const response = await adminApi.dlqMessages(row.name)
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
    const response = await adminApi.dlqReplay(row.name)
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
    const response = await adminApi.dlqPurge(row.name)
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
  } catch { return false }
}

async function confirmPurge(row: DlqQueueVO): Promise<boolean> {
  try {
    await ElMessageBox.confirm(
      `永久删除 ${row.name} 中的 ${row.messages} 条消息，无法恢复。`,
      '清空死信',
      { type: 'error', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
    return true
  } catch { return false }
}

onMounted(loadQueues)
</script>

<style scoped>
.dlq-page {
  display: grid;
  gap: var(--space-6);
  width: min(100%, var(--content-max));
}

.page-header {
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

.page-header p {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--text-sm);
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
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding: var(--space-5) var(--space-6);
  border-bottom: 1px solid var(--border);
  gap: var(--space-6);
}

.panel-header h2 {
  margin: 0 0 var(--space-1);
  color: var(--text-primary);
  font-size: var(--text-lg);
}

.panel-header p {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--text-sm);
}

.table-scroll { overflow-x: auto; }

.queue-name, .route-cell { display: grid; gap: var(--space-1); }
.queue-name strong, .route-cell span { color: var(--text-primary); font-family: var(--mono); font-size: var(--text-xs); }
.queue-name span, .route-cell code { color: var(--text-muted); font-family: var(--mono); font-size: var(--text-xs); }

.message-count { color: var(--text-muted); font-family: var(--mono); font-weight: 700; }
.message-count.active { color: var(--warning); }

.row-actions { display: flex; justify-content: flex-end; gap: var(--space-1); }

.state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-3xl) var(--space-6);
  font-size: var(--text-sm);
}
.state.error { color: var(--danger); }
.state.empty { color: var(--text-muted); }
</style>
