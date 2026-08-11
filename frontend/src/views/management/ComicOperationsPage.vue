<template>
  <div class="comic-operations-page">
    <header class="page-header">
      <div><h1>漫画操作台</h1><p>触发存储与生命周期操作，并实时观察漫画和任务状态变化。</p></div>
      <div class="target-input"><el-input-number v-model="comicId" :min="1" :controls="false" /><el-button type="primary" @click="selectComic">加载漫画</el-button></div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon />
    <section v-if="comic" class="current-state">
      <div><span>漫画</span><strong>{{ comic.title }}</strong><small>ID {{ comic.id }}</small></div>
      <div><span>当前生命周期</span><ComicStatusTag :status="comic.status" /><small>{{ statusMeta.description }}</small></div>
      <div><span>页数</span><strong>{{ comic.pageCount }}</strong><small>{{ comic.categoryName || '未分类' }}</small></div>
      <div><span>自动观察</span><strong>{{ polling ? '开启' : '关闭' }}</strong><el-switch v-model="polling" /></div>
    </section>

    <el-tabs v-if="comic" v-model="activeTab">
      <el-tab-pane label="可执行操作" name="operations">
        <section class="panel">
          <h2>存储与媒体</h2>
          <div class="actions">
            <el-button :disabled="!isAllowed('LQ_GENERATE')" @click="generateLq(false)">生成 LQ</el-button>
            <el-button :disabled="!isAllowed('LQ_REGENERATE')" @click="generateLq(true)">重新生成 LQ</el-button>
            <el-button :disabled="!isAllowed('HQ_DELETE')" type="danger" @click="deleteHq">删除 HQ</el-button>
            <el-button :disabled="!isAllowed('TRANSCODE')" @click="transcode">视频转码</el-button>
            <el-button :disabled="!isAllowed('METADATA_REFRESH')" @click="refreshMetadata">刷新元数据</el-button>
            <el-button @click="createExport">导出漫画</el-button>
          </div>
          <el-table :data="blockedRows" empty-text="当前没有被阻止的操作">
            <el-table-column prop="operation" label="被阻止操作" width="180" />
            <el-table-column prop="reason" label="后端判定原因" />
          </el-table>
        </section>
        <section class="panel danger-panel">
          <h2>回收站生命周期</h2>
          <div class="actions">
            <el-button v-if="comic.status === 'READY'" type="danger" @click="trashComic">移入回收站</el-button>
            <el-button v-if="comic.status === 'TRASHED'" type="primary" @click="restoreComic">恢复漫画</el-button>
            <el-input v-if="comic.status === 'TRASHED'" v-model="purgeToken" placeholder="永久清理确认 token" />
            <el-button v-if="comic.status === 'TRASHED'" type="danger" :disabled="!purgeToken.trim()" @click="purgeComic">永久清理</el-button>
            <el-button @click="reconcile(false)">只读对账</el-button>
            <el-button @click="reconcile(true)">对账并修复</el-button>
          </div>
          <el-descriptions v-if="reconcileResult" :column="3" border>
            <el-descriptions-item label="数据库状态">{{ reconcileResult.dbStatus || '—' }}</el-descriptions-item>
            <el-descriptions-item label="清单状态">{{ reconcileResult.manifestStatus || '—' }}</el-descriptions-item>
            <el-descriptions-item label="一致性">{{ reconcileResult.consistent ? '一致' : '存在差异' }}</el-descriptions-item>
          </el-descriptions>
        </section>
      </el-tab-pane>

      <el-tab-pane label="状态变化" name="history">
        <section class="panel">
          <h2>本次观察记录</h2>
          <el-timeline>
            <el-timeline-item v-for="event in statusEvents" :key="`${event.at}-${event.status}`" :timestamp="event.at">
              <ComicStatusTag :status="event.status" />
            </el-timeline-item>
          </el-timeline>
        </section>
      </el-tab-pane>

      <el-tab-pane label="相关任务与统计" name="tasks">
        <section class="summary-grid">
          <article><span>相关任务</span><strong>{{ relatedTaskTotal }}</strong><small>该漫画全部任务</small></article>
          <article><span>当前页运行中</span><strong>{{ relatedActive }}</strong><small>排队、执行、取消中</small></article>
          <article><span>Outbox 待发送</span><strong>{{ outbox?.pending ?? '—' }}</strong><small>总计 {{ outbox?.total ?? '—' }}</small></article>
          <article><span>Outbox 失败</span><strong>{{ outbox?.failed ?? '—' }}</strong><small>应及时检查</small></article>
        </section>
        <el-table :data="relatedTasks" row-key="id">
          <el-table-column prop="id" label="任务 ID" width="90" />
          <el-table-column label="类型"><template #default="{ row }">{{ managementTaskTypeLabel(row.taskType) }}</template></el-table-column>
          <el-table-column label="状态"><template #default="{ row }">{{ managementTaskStatusLabel(row.status) }}</template></el-table-column>
          <el-table-column prop="progress" label="进度" />
          <el-table-column prop="updatedAt" label="最后变化" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, comicApi, exportApi, hqApi, mediaOperationApi, outboxApi, trashApi } from '@/services/api'
import { storageService } from '@/services/storage'
import { lqOperationApi, trackedTaskApi } from '@/services/management-capabilities'
import ComicStatusTag from '@/components/management/ComicStatusTag.vue'
import { comicStatusMeta } from '@/utils/comic-status'
import { managementTaskStatusLabel, managementTaskTypeLabel } from '@/utils/management-task'
import type { ComicDetailVO, ComicStatus, ManagementTaskVO, MediaOperationResult, OutboxStats, ReconcileResult } from '@/types'

type StatusEvent = { readonly status: ComicStatus; readonly at: string }
type BlockedRow = { readonly operation: string; readonly reason: string }
const route = useRoute()
const router = useRouter()
const initialId = Number(route.query['comicId'])
const comicId = ref(Number.isSafeInteger(initialId) && initialId > 0 ? initialId : 1)
const comic = ref<ComicDetailVO | null>(null)
const eligibility = ref<MediaOperationResult | null>(null)
const relatedTasks = ref<readonly ManagementTaskVO[]>([])
const relatedTaskTotal = ref(0)
const outbox = ref<OutboxStats | null>(null)
const reconcileResult = ref<ReconcileResult | null>(null)
const statusEvents = ref<StatusEvent[]>([])
const purgeToken = ref('')
const polling = ref(true)
const loading = ref(false)
const error = ref('')
const activeTab = ref('operations')
let timer: ReturnType<typeof setInterval> | undefined

const statusMeta = computed(() => comic.value ? comicStatusMeta(comic.value.status) : comicStatusMeta('DRAFT'))
const blockedRows = computed<readonly BlockedRow[]>(() => Object.entries(eligibility.value?.blockedReasons ?? {}).map(([operation, reason]) => ({ operation, reason })))
const relatedActive = computed(() => relatedTasks.value.filter((task) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(task.status)).length)
function errorMessage(reason: unknown): string { if (axios.isAxiosError<{ message?: string }>(reason)) return reason.response?.data?.message ?? reason.message; return reason instanceof Error ? reason.message : '未知错误' }
function isAllowed(operation: string): boolean { return eligibility.value?.allowed.includes(operation) ?? false }
function recordStatus(status: ComicStatus): void { if (statusEvents.value.at(-1)?.status !== status) statusEvents.value.push({ status, at: new Date().toLocaleString() }) }
async function loadState(silent = false): Promise<void> { if (!silent) loading.value = true; error.value = ''; try { const [detail, operations, tasks, stats] = await Promise.all([comicApi.detail(comicId.value), mediaOperationApi.forComic(comicId.value), trackedTaskApi.list({ page: 1, size: 20, targetId: comicId.value }), outboxApi.stats()]); comic.value = detail.data; eligibility.value = operations.data; relatedTasks.value = tasks.data.records; relatedTaskTotal.value = tasks.data.total; outbox.value = stats.data; recordStatus(detail.data.status) } catch (reason: unknown) { error.value = errorMessage(reason) } finally { loading.value = false } }
async function selectComic(): Promise<void> { await router.replace({ query: { comicId: String(comicId.value) } }); statusEvents.value = []; await loadState() }
async function runAction(label: string, action: () => Promise<unknown>): Promise<void> { loading.value = true; try { await action(); ElMessage.success(`${label}已提交`); activeTab.value = 'history'; await loadState(true) } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } finally { loading.value = false } }
function generateLq(regenerate: boolean): void { void runAction(regenerate ? '重新生成 LQ' : '生成 LQ', () => lqOperationApi.generateComic(comicId.value, regenerate)) }
function deleteHq(): void { void runAction('删除 HQ', () => hqApi.deleteComic(comicId.value)) }
function transcode(): void { void runAction('视频转码', () => adminApi.transcodeVideos(comicId.value)) }
function refreshMetadata(): void { void runAction('刷新元数据', () => storageService.requestMetadataRefresh(comicId.value)) }
function createExport(): void { void runAction('导出', () => exportApi.createExport(comicId.value)) }
async function trashComic(): Promise<void> { await ElMessageBox.confirm('漫画将移入回收站，可在保留期内恢复。', '确认回收', { type: 'warning' }); await runAction('回收漫画', () => comicApi.delete(comicId.value)) }
function restoreComic(): void { void runAction('恢复漫画', () => trashApi.restoreComic(comicId.value)) }
async function purgeComic(): Promise<void> { await ElMessageBox.confirm('永久清理不可恢复，并受 7 天保留期限制。', '确认永久清理', { type: 'error' }); await runAction('永久清理', () => trashApi.purgeComic(comicId.value, purgeToken.value.trim())) }
async function reconcile(repair: boolean): Promise<void> { try { const response = repair ? await trashApi.reconcileAndRepair('COMIC', comicId.value) : await trashApi.reconcile('COMIC', comicId.value); reconcileResult.value = response.data; ElMessage.success(repair ? '对账修复完成' : '对账完成') } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }

onMounted(() => { void loadState(); timer = setInterval(() => { if (polling.value && !loading.value) void loadState(true) }, 2500) })
onBeforeUnmount(() => { if (timer !== undefined) clearInterval(timer) })
</script>

<style scoped>
.comic-operations-page { display: grid; gap: var(--space-6); }
.page-header, .target-input, .actions { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; }
.page-header h1, .panel h2 { margin: 0; color: var(--text-primary); }
.page-header p, .current-state span, .current-state small, .summary-grid span, .summary-grid small { color: var(--text-muted); }
.current-state, .summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-4); }
.current-state > div, .summary-grid article, .panel { display: grid; gap: var(--space-3); padding: var(--space-5); border: 1px solid var(--border); background: var(--bg-surface); }
.current-state strong, .summary-grid strong { color: var(--text-primary); font-size: 1.65rem; }
.panel { margin-bottom: var(--space-5); }
.actions { justify-content: flex-start; }
.actions :deep(.el-input) { max-width: 320px; }
.danger-panel { border-color: var(--color-danger); }
@media (max-width: 900px) { .current-state, .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 480px) { .current-state, .summary-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
