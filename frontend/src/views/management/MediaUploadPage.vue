<template>
  <div class="media-upload-page">
    <header><h1>媒体上传</h1><p>向指定章节追加图片或视频；填写替换媒体 ID 时执行单文件替换。</p></header>
    <el-alert title="初版入口会先计算文件 SHA-256，再按后端返回的分片大小顺序上传。请保持页面打开。" type="info" show-icon />
    <el-form label-position="top" class="upload-form">
      <div class="id-row">
        <el-form-item label="漫画 ID"><el-input-number v-model="comicId" :min="1" :controls="false" /></el-form-item>
        <el-form-item label="章节 ID"><el-input-number v-model="chapterId" :min="1" :controls="false" /></el-form-item>
        <el-form-item label="替换媒体 ID（可选）"><el-input-number v-model="replaceMediaId" :min="1" :controls="false" clearable /></el-form-item>
      </div>
      <el-form-item label="媒体文件">
        <input type="file" multiple accept="image/*,video/*" @change="onFilesSelected">
      </el-form-item>
      <div class="table-scroll">
        <el-table :data="fileRows" empty-text="尚未选择文件">
          <el-table-column prop="file.name" label="文件" min-width="260" />
          <el-table-column label="大小" width="130"><template #default="{ row }">{{ formatBytes(row.file.size) }}</template></el-table-column>
          <el-table-column prop="status" label="状态" width="130" />
          <el-table-column label="进度" min-width="220"><template #default="{ row }"><el-progress :percentage="row.progress" /></template></el-table-column>
        </el-table>
      </div>
      <div class="actions">
        <el-button type="primary" :loading="running" :disabled="!canStart" @click="startUpload">开始上传</el-button>
        <el-button v-if="sessionId && running" type="danger" @click="cancelUpload">取消会话</el-button>
        <router-link v-if="completedTaskId" :to="`/manage/tasks?targetId=${comicId}`">查看任务 {{ completedTaskId }}</router-link>
      </div>
    </el-form>
    <el-descriptions v-if="sessionId" :column="3" border>
      <el-descriptions-item label="会话 ID">{{ sessionId }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ sessionStatus }}</el-descriptions-item>
      <el-descriptions-item label="任务 ID">{{ completedTaskId || '—' }}</el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { trackedUploadApi } from '@/services/management-capabilities'
import type { CreateUploadSessionRequest, UploadFileManifest } from '@/types'

type UploadRow = { readonly id: string; readonly file: File; status: string; progress: number; sha256: string }
const comicId = ref(1)
const chapterId = ref(1)
const replaceMediaId = ref<number | undefined>()
const fileRows = ref<UploadRow[]>([])
const sessionId = ref('')
const sessionStatus = ref('尚未创建')
const completedTaskId = ref<number | null>(null)
const running = ref(false)
let uploadAbortController: AbortController | undefined
const canStart = computed(() => fileRows.value.length > 0 && (!replaceMediaId.value || fileRows.value.length === 1) && !running.value)

function errorMessage(reason: unknown): string { if (axios.isAxiosError<{ message?: string }>(reason)) return reason.response?.data?.message ?? reason.message; return reason instanceof Error ? reason.message : '未知错误' }
function onFilesSelected(event: Event): void { const input = event.currentTarget; if (!(input instanceof HTMLInputElement)) return; fileRows.value = Array.from(input.files ?? []).map((file) => ({ id: crypto.randomUUID(), file, status: '等待', progress: 0, sha256: '' })); completedTaskId.value = null; sessionId.value = ''; sessionStatus.value = '尚未创建' }
function toHex(buffer: ArrayBuffer): string { return Array.from(new Uint8Array(buffer), (byte) => byte.toString(16).padStart(2, '0')).join('') }
async function hashFiles(): Promise<readonly UploadFileManifest[]> { const manifests: UploadFileManifest[] = []; for (const row of fileRows.value) { row.status = '计算校验值'; row.sha256 = toHex(await crypto.subtle.digest('SHA-256', await row.file.arrayBuffer())); manifests.push({ fileId: row.id, name: row.file.name, contentType: row.file.type || 'application/octet-stream', size: row.file.size, sha256: row.sha256 }) } return manifests }
async function uploadFile(row: UploadRow, chunkSize: number): Promise<void> { let offset = 0; row.status = '上传中'; while (offset < row.file.size) { const endExclusive = Math.min(offset + chunkSize, row.file.size); const chunk = row.file.slice(offset, endExclusive); await trackedUploadApi.uploadChunk({ sessionId: sessionId.value, fileId: row.id, chunk, contentRange: `bytes=${offset}-${endExclusive - 1}/${row.file.size}`, signal: uploadAbortController?.signal }); offset = endExclusive; row.progress = Math.round((offset / row.file.size) * 100) } row.status = '已上传' }
async function startUpload(): Promise<void> { running.value = true; completedTaskId.value = null; uploadAbortController = new AbortController(); try { const files = await hashFiles(); const request: CreateUploadSessionRequest = { comicId: comicId.value, chapterId: chapterId.value, ...(replaceMediaId.value ? { replaceMediaId: replaceMediaId.value } : {}), files }; const created = (await trackedUploadApi.createSession(request)).data; sessionId.value = created.sessionId; sessionStatus.value = 'ACTIVE'; for (const row of fileRows.value) await uploadFile(row, created.chunkSize); sessionStatus.value = '提交中'; const completed = (await trackedUploadApi.completeSession(created.sessionId)).data; completedTaskId.value = completed.taskId; sessionStatus.value = 'COMPLETED'; ElMessage.success('上传完成，已创建媒体管理任务') } catch (reason: unknown) { if (!axios.isCancel(reason)) { sessionStatus.value = 'FAILED'; ElMessage.error(errorMessage(reason)) } } finally { running.value = false; uploadAbortController = undefined } }
async function cancelUpload(): Promise<void> { uploadAbortController?.abort(); try { await trackedUploadApi.cancelSession(sessionId.value); sessionStatus.value = 'CANCELLED'; fileRows.value.forEach((row) => { if (row.status !== '已上传') row.status = '已取消' }); ElMessage.success('上传会话已取消') } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } finally { running.value = false } }
function formatBytes(bytes: number): string { if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`; if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`; return `${(bytes / 1024).toFixed(1)} KB` }
</script>

<style scoped>
.media-upload-page, .upload-form { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--space-5); width: 100%; min-width: 0; max-width: 100%; box-sizing: border-box; }
header h1 { margin: 0; color: var(--text-primary); font-size: var(--text-page); }
header p { color: var(--text-muted); }
.upload-form { padding: var(--space-6); border: 1px solid var(--border); background: var(--bg-surface); }
.table-scroll { width: 100%; min-width: 0; max-width: 100%; box-sizing: border-box; overflow-x: auto; }
.table-scroll :deep(.el-table) { min-width: 740px; }
input[type='file'] { max-width: 100%; }
:deep(.el-alert__content) { min-width: 0; }
:deep(.el-alert__title) { white-space: normal; }
.id-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--space-4); }
.actions { display: flex; align-items: center; gap: var(--space-4); }
@media (max-width: 800px) { .id-row { grid-template-columns: 1fr; } }
@media (max-width: 480px) { .upload-form { padding: var(--space-4); } }
</style>
