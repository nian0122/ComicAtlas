<template>
  <div class="media-upload-page">
    <ManagementPageHeader title="媒体上传" description="向现有章节追加媒体，或用新文件替换单个媒体。" eyebrow="MEDIA / INTAKE">
      <router-link class="back-link" to="/manage/comics">返回漫画管理</router-link>
    </ManagementPageHeader>

    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon closable @close="errorMessage = ''" />
    <section class="upload-workbench">
      <div class="upload-form-card">
        <div class="section-heading"><span class="panel-kicker">TARGET</span><h2>选择目标章节</h2><p>上传完成后由管理任务中心异步搬入正式媒体目录。</p></div>
        <el-form label-position="top" @submit.prevent="startUpload">
          <el-form-item label="漫画" required>
            <el-select v-model="selectedComicId" filterable clearable placeholder="选择漫画" :loading="loadingComics" @change="onComicChanged">
              <el-option v-for="comic in comics" :key="comic.id" :label="comic.title" :value="comic.id"><span>{{ comic.title }}</span><small> #{{ comic.id }}</small></el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="章节" required>
            <el-select v-model="selectedChapterId" filterable clearable placeholder="先选择漫画" :loading="loadingChapters" :disabled="!selectedComicId" @change="onChapterChanged">
              <el-option v-for="chapter in chapters" :key="chapter.id" :label="chapterLabel(chapter)" :value="chapter.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="操作模式">
            <el-radio-group v-model="mode" :disabled="uploading">
              <el-radio-button value="append">追加媒体</el-radio-button>
              <el-radio-button value="replace">替换媒体</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="mode === 'replace'" label="替换目标" required>
            <el-select v-model="replaceMediaId" filterable clearable placeholder="选择要替换的媒体" :loading="loadingMedia" :disabled="!selectedChapterId">
              <el-option v-for="media in mediaItems" :key="media.id" :label="mediaLabel(media)" :value="media.id" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <div class="upload-file-card">
        <div class="section-heading"><span class="panel-kicker">PAYLOAD</span><h2>选择文件</h2><p>支持图片与视频；替换模式只能提交一个文件。</p></div>
        <label class="dropzone" :class="{ 'is-ready': selectedFiles.length }">
          <input type="file" :multiple="mode === 'append'" accept="image/*,video/*" :disabled="uploading" @change="onFilesChanged" />
          <span class="dropzone-mark">↑</span><strong>{{ selectedFiles.length ? `已选择 ${selectedFiles.length} 个文件` : '选择或拖入媒体文件' }}</strong><small>上传前会计算 SHA-256，分片大小由服务端返回</small>
        </label>
        <div v-if="selectedFiles.length" class="file-list" aria-live="polite">
          <div v-for="file in selectedFiles" :key="file.id" class="file-row"><div class="file-copy"><strong>{{ file.file.name }}</strong><small>{{ formatBytes(file.file.size) }} · {{ file.status }}</small></div><el-progress :percentage="file.progress" :show-text="false" /></div>
        </div>
        <div class="upload-actions"><el-button :disabled="!selectedFiles.length || uploading" @click="clearFiles">清空</el-button><el-button v-if="sessionId && uploading" type="danger" plain @click="cancelUpload">取消上传</el-button><el-button type="primary" :loading="uploading" :disabled="!canStart" @click="startUpload">{{ uploading ? uploadStatus : '开始上传' }}</el-button></div>
        <div v-if="sessionId" class="session-note">会话 {{ sessionId }} · {{ uploadStatus }}<span v-if="taskId"> · 任务 #{{ taskId }}</span></div>
      </div>
    </section>

    <section class="upload-guide"><div><span class="panel-kicker">HOW IT WORKS</span><h2>安全的异步媒体管线</h2></div><ol><li><strong>校验</strong><span>浏览器计算文件摘要，服务端检查目标章节和文件类型。</span></li><li><strong>分片</strong><span>大文件按服务端分片大小上传，可观察每个文件进度。</span></li><li><strong>入库</strong><span>完成后生成管理任务，Worker 负责搬运并更新媒体状态。</span></li></ol></section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import ManagementPageHeader from '@/components/management/ManagementPageHeader.vue'
import { managementCatalogApi, managementChapterApi, managementComicApi } from '@/features/comic/management-api'
import { uploadApi } from '@/features/upload/api'
import type { CreateUploadSessionRequest, UploadFileManifest } from '@/features/upload/types'
import type { CatalogNode, ChapterRef, ComicListVO } from '@/entities/comic/types'
import type { MediaItemInfo } from '@/entities/media/types'
import { getApiErrorMessage } from '@/services/http'

type UploadMode = 'append' | 'replace'
type UploadRow = { id: string; file: File; progress: number; status: string; sha256: string }

const comics = ref<ComicListVO[]>([])
const chapters = ref<ChapterRef[]>([])
const mediaItems = ref<MediaItemInfo[]>([])
const selectedComicId = ref<number | undefined>()
const selectedChapterId = ref<number | undefined>()
const replaceMediaId = ref<number | undefined>()
const mode = ref<UploadMode>('append')
const selectedFiles = ref<UploadRow[]>([])
const loadingComics = ref(false)
const loadingChapters = ref(false)
const loadingMedia = ref(false)
const uploading = ref(false)
const errorMessage = ref('')
const uploadStatus = ref('尚未创建')
const sessionId = ref('')
const taskId = ref<number | null>(null)
let abortController: AbortController | undefined

const canStart = computed(() => Boolean(selectedComicId.value && selectedChapterId.value && selectedFiles.value.length && (mode.value === 'append' || replaceMediaId.value)))

function flattenChapters(nodes: readonly CatalogNode[]): ChapterRef[] {
  return nodes.flatMap((node) => [...node.chapters, ...flattenChapters(node.children ?? [])]).sort((left, right) => left.globalOrder - right.globalOrder)
}
function chapterLabel(chapter: ChapterRef): string { return `${chapter.title || `章节 ${chapter.chapterNo}`} · ${chapter.pageCount} 个媒体` }
function mediaLabel(media: MediaItemInfo): string { return `${media.pageNumber}. ${media.fileName || '未命名媒体'} · ${media.mediaType === 'VIDEO' ? '视频' : '图片'}` }
function formatBytes(bytes: number): string { if (bytes < 1024) return `${bytes} B`; const units = ['KB', 'MB', 'GB']; let value = bytes; let unit = -1; do { value /= 1024; unit++ } while (value >= 1024 && unit < units.length - 1); return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unit]}` }
function toHex(buffer: ArrayBuffer): string { return Array.from(new Uint8Array(buffer), (byte) => byte.toString(16).padStart(2, '0')).join('') }
async function createManifest(): Promise<readonly UploadFileManifest[]> { return Promise.all(selectedFiles.value.map(async (row) => { row.status = '计算校验值'; row.sha256 = toHex(await crypto.subtle.digest('SHA-256', await row.file.arrayBuffer())); return { fileId: row.id, name: row.file.name, contentType: row.file.type || 'application/octet-stream', size: row.file.size, sha256: row.sha256 } })) }
async function uploadRow(row: UploadRow, chunkSize: number): Promise<void> { let offset = 0; row.status = '上传中'; while (offset < row.file.size) { const end = Math.min(offset + chunkSize, row.file.size); await uploadApi.uploadChunk({ sessionId: sessionId.value, fileId: row.id, chunk: row.file.slice(offset, end), contentRange: `bytes=${offset}-${end - 1}/${row.file.size}`, signal: abortController?.signal }); offset = end; row.progress = Math.round(offset / row.file.size * 100) } row.status = '已上传' }
function onFilesChanged(event: Event): void { const input = event.currentTarget; if (!(input instanceof HTMLInputElement)) return; const files = Array.from(input.files ?? []).slice(0, mode.value === 'replace' ? 1 : undefined); selectedFiles.value = files.map((file) => ({ id: crypto.randomUUID(), file, progress: 0, status: '等待上传', sha256: '' })); sessionId.value = ''; taskId.value = null; uploadStatus.value = '尚未创建' }
function clearFiles(): void { selectedFiles.value = []; sessionId.value = ''; taskId.value = null; uploadStatus.value = '尚未创建' }
async function startUpload(): Promise<void> { if (!canStart.value || !selectedComicId.value || !selectedChapterId.value) return; uploading.value = true; errorMessage.value = ''; abortController = new AbortController(); try { const request: CreateUploadSessionRequest = { comicId: selectedComicId.value, chapterId: selectedChapterId.value, ...(mode.value === 'replace' ? { replaceMediaId: replaceMediaId.value } : {}), files: await createManifest() }; const created = (await uploadApi.createSession(request)).data; sessionId.value = created.sessionId; uploadStatus.value = '上传中'; for (const row of selectedFiles.value) await uploadRow(row, created.chunkSize); uploadStatus.value = '提交任务'; const completed = (await uploadApi.completeSession(created.sessionId)).data; taskId.value = completed.taskId; uploadStatus.value = '已提交'; ElMessage.success('媒体上传任务已提交'); await onChapterChanged(selectedChapterId.value) } catch (reason: unknown) { if (!axios.isCancel(reason)) { errorMessage.value = getApiErrorMessage(reason, '媒体上传失败'); uploadStatus.value = '失败' } } finally { uploading.value = false; abortController = undefined } }
async function cancelUpload(): Promise<void> { abortController?.abort(); if (sessionId.value) await uploadApi.cancelSession(sessionId.value); uploading.value = false; uploadStatus.value = '已取消' }
async function onComicChanged(): Promise<void> { selectedChapterId.value = undefined; replaceMediaId.value = undefined; mediaItems.value = []; chapters.value = []; if (!selectedComicId.value) return; loadingChapters.value = true; try { chapters.value = flattenChapters((await managementCatalogApi.tree(selectedComicId.value)).data) } catch (reason: unknown) { errorMessage.value = getApiErrorMessage(reason, '章节加载失败') } finally { loadingChapters.value = false } }
async function onChapterChanged(chapterId?: number): Promise<void> { replaceMediaId.value = undefined; mediaItems.value = []; if (!chapterId) return; loadingMedia.value = true; try { mediaItems.value = ((await managementChapterApi.detail(chapterId)).data.pages ?? []) as MediaItemInfo[] } catch (reason: unknown) { errorMessage.value = getApiErrorMessage(reason, '媒体列表加载失败') } finally { loadingMedia.value = false } }
watch(mode, () => { replaceMediaId.value = undefined; if (mode.value === 'replace' && selectedFiles.value.length > 1) selectedFiles.value = selectedFiles.value.slice(0, 1) })
onMounted(async () => { loadingComics.value = true; try { comics.value = (await managementComicApi.list({ size: 200, order: 'asc', sort: 'title' })).data.records } catch (reason: unknown) { errorMessage.value = getApiErrorMessage(reason, '漫画列表加载失败') } finally { loadingComics.value = false } })
</script>

<style scoped>
.media-upload-page { display: grid; gap: var(--space-8); min-width: 0; }
.back-link { color: var(--color-brand); font-size: var(--text-sm); font-weight: 700; text-decoration: none; }
.upload-workbench { display: grid; grid-template-columns: minmax(280px, .8fr) minmax(360px, 1.2fr); gap: var(--space-6); }
.upload-form-card, .upload-file-card, .upload-guide { display: grid; gap: var(--space-6); padding: clamp(22px, 3vw, 36px); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-surface); }
.section-heading { display: grid; gap: var(--space-2); }
.section-heading h2, .upload-guide h2 { margin: 0; color: var(--text-primary); font-family: Georgia, 'Times New Roman', serif; font-size: 26px; letter-spacing: -.035em; }
.section-heading p { margin: 0; color: var(--text-muted); font-size: var(--text-sm); line-height: 1.7; }
.panel-kicker { color: var(--color-brand); font: 800 10px ui-monospace, SFMono-Regular, Consolas, monospace; letter-spacing: .18em; }
.dropzone { display: grid; justify-items: center; gap: var(--space-2); min-height: 180px; align-content: center; border: 1px dashed var(--color-brand); background: color-mix(in srgb, var(--color-brand) 4%, var(--bg-surface)); cursor: pointer; text-align: center; transition: background .2s ease, transform .2s ease; }
.dropzone:hover, .dropzone.is-ready { background: color-mix(in srgb, var(--color-brand) 10%, var(--bg-surface)); transform: translateY(-2px); }
.dropzone input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.dropzone-mark { color: var(--color-brand); font-size: 42px; line-height: 1; }
.dropzone strong { color: var(--text-primary); }
.dropzone small, .file-row small, .session-note { color: var(--text-muted); font-size: var(--text-xs); }
.file-list { display: grid; max-height: 220px; overflow: auto; border: 1px solid var(--border); }
.file-row { display: grid; grid-template-columns: minmax(0, 1fr) 140px; gap: var(--space-4); align-items: center; padding: 12px; border-bottom: 1px solid var(--border); }
.file-row:last-child { border-bottom: 0; }
.file-copy { min-width: 0; }.file-copy strong, .file-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upload-actions { display: flex; justify-content: flex-end; gap: var(--space-3); }.upload-actions :deep(.el-button + .el-button) { margin-left: 0; }
.session-note { padding: 10px 12px; background: var(--bg-elevated); }
.upload-guide { grid-template-columns: minmax(180px, .5fr) 1fr; align-items: start; background: var(--bg-secondary); }
.upload-guide ol { display: grid; gap: var(--space-4); margin: 0; padding-left: 22px; }.upload-guide li { padding-left: 6px; color: var(--text-primary); }.upload-guide li::marker { color: var(--color-brand); font-weight: 800; }.upload-guide li strong, .upload-guide li span { display: block; }.upload-guide li span { margin-top: 3px; color: var(--text-muted); font-size: var(--text-sm); line-height: 1.6; }
@media (max-width: 800px) { .upload-workbench, .upload-guide { grid-template-columns: 1fr; } }
</style>
