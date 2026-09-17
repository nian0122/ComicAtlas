<template>
  <div class="structure-page">
    <PageHeader
      class="structure-header"
      title="目录与媒体"
      description="从目录树定位章节，再在右侧完成维护。"
      eyebrow="COMIC / STRUCTURE"
    >
      <div class="header-tools">
        <span class="comic-ref">漫画 #{{ comicId }}</span
        ><el-button :loading="loading" @click="loadTree">刷新结构</el-button>
      </div>
    </PageHeader>
    <StatGrid class="structure-summary" aria-label="结构概览" :columns="4">
      <StatCard
        label="目录节点"
        :value="catalogCount"
        :description="rootChapterCount ? `${rootChapterCount} 个根章节` : '暂无根章节'"
      />
      <StatCard label="章节总数" :value="chapterCount" description="包含目录下的全部章节" />
      <StatCard
        label="当前章节媒体"
        :value="mediaItems.length || '—'"
        :description="selectedRow?.kind === 'CHAPTER' ? selectedRow.title : '选择章节后统计'"
      />
      <StatCard label="目录结构状态" :value="treeStateLabel" :description="structureRows.length + ' 个根节点'" />
    </StatGrid>
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <section v-if="lqIssueChapters.length" class="issue-strip" aria-label="LQ 异常章节">
      <div class="issue-strip-heading">
        <div>
          <span class="panel-kicker">LQ / ATTENTION</span><strong>{{ lqIssueChapters.length }} 个章节需要检查</strong>
        </div>
        <small>点击章节可直接定位到媒体明细</small>
      </div>
      <div class="issue-chapter-list">
        <AppButton
          v-for="chapter in lqIssueChapters"
          :key="chapter.chapterId"
          type="button"
          class="issue-chapter"
          @click="locateChapter(chapter.chapterId)"
        >
          <span>{{ chapter.title || `章节 ${chapter.chapterNo}` }}</span
          ><small>{{ mediaLqLabel(chapter.lqStatus) }} · {{ chapter.pageCount }} 个媒体</small>
        </AppButton>
      </div>
    </section>

    <section class="structure-browser">
      <aside class="tree-panel">
        <PanelHeader title="目录树" eyebrow="NAVIGATOR"
          ><span class="node-count">{{ structureRows.length }} 个根节点</span></PanelHeader
        >
        <el-input v-model="structureKeyword" clearable placeholder="搜索目录或章节" class="tree-search" />
        <el-table
          v-loading="loading"
          class="structure-table"
          :data="filteredStructureRows"
          row-key="key"
          :tree-props="{ children: 'children' }"
          :row-class-name="rowClassName"
          :empty-text="emptyStateText"
          highlight-current-row
          @row-click="selectStructureRow"
        >
          <el-table-column prop="title" min-width="0"
            ><template #default="{ row }"
              ><div class="tree-title">
                <span class="tree-icon">{{ row.kind === 'CATALOG' ? '▰' : '▱' }}</span
                ><span>{{ row.title }}</span>
              </div></template
            ></el-table-column
          >
          <el-table-column width="72"
            ><template #default="{ row }"
              ><span class="tree-kind">{{ row.kind === 'CATALOG' ? '目录' : '章节' }}</span></template
            ></el-table-column
          >
        </el-table>
      </aside>

      <main class="detail-panel">
        <template v-if="selectedRow">
          <div class="selected-header">
            <div>
              <span class="panel-kicker">SELECTED NODE</span>
              <h2>{{ selectedRow.title }}</h2>
              <p>
                {{ selectedRow.kind === 'CATALOG' ? '目录节点' : '章节节点' }} · ID {{ selectedRow.id }} · 顺序
                {{ selectedRow.order ?? '—' }}
              </p>
            </div>
            <el-tag :type="selectedRow.kind === 'CATALOG' ? 'warning' : 'info'" effect="plain">{{
              selectedRow.kind === 'CATALOG' ? '目录' : '章节'
            }}</el-tag>
          </div>
          <template v-if="selectedRow.kind === 'CATALOG'">
            <div class="child-summary">
              <strong>{{ selectedRow.children?.length ?? 0 }}</strong
              ><span>个下级节点</span>
            </div>
            <div class="child-list">
              <AppButton
                v-for="child in selectedRow.children"
                :key="child.key"
                type="button"
                @click="selectStructureRow(child)"
              >
                <span>{{ child.kind === 'CATALOG' ? '▰' : '▱' }}</span
                >{{ child.title }}<small>{{ child.kind === 'CATALOG' ? '目录' : '章节' }}</small>
              </AppButton>
              <div v-if="!selectedRow.children?.length" class="empty-copy">这个目录还没有下级节点。</div>
            </div>
          </template>
          <template v-else>
            <div class="media-heading">
              <div>
                <h3>章节媒体</h3>
                <p>{{ mediaItems.length ? `共 ${mediaItems.length} 个媒体` : '正在等待媒体加载' }}</p>
              </div>
              <el-button text @click="loadMedia">刷新媒体</el-button>
            </div>
            <div class="media-summary">
              <div>
                <span>HQ</span><strong>{{ mediaHqReadyCount }} / {{ mediaItems.length }}</strong
                ><small>可访问</small>
              </div>
              <div>
                <span>LQ</span><strong>{{ mediaLqReadyCount }} / {{ mediaLqApplicableCount }}</strong
                ><small>{{
                  mediaLqApplicableCount === 0
                    ? '不适用'
                    : mediaLqReadyCount === mediaLqApplicableCount
                      ? '已生成'
                      : '未生成'
                }}</small>
              </div>
              <div>
                <span>媒体类型</span><strong>{{ mediaVideoCount ? '视频' : '图片' }}</strong
                ><small>{{ mediaVideoCount ? `${mediaVideoCount} 个视频` : '图片媒体' }}</small>
              </div>
            </div>
            <div v-if="selectedMediaIds.length" class="media-selection-toolbar">
              <span>已选 {{ selectedMediaIds.length }} 个媒体</span>
              <div>
                <el-button text @click="clearMediaSelection">清空选择</el-button
                ><el-button type="danger" plain @click="trashSelectedMediaBatch">批量回收</el-button>
              </div>
            </div>
            <div class="media-table-scroll">
              <el-table
                ref="mediaTableRef"
                class="media-table"
                :data="mediaItems"
                row-key="id"
                empty-text="该章节暂无媒体"
                highlight-current-row
                :row-class-name="mediaRowClassName"
                @row-click="selectMediaRow"
                @selection-change="handleMediaSelection"
              >
                <el-table-column type="selection" width="48" reserve-selection />
                <el-table-column prop="pageNumber" label="顺序" width="76" />
                <el-table-column prop="fileName" label="文件名" min-width="190" show-overflow-tooltip />
                <el-table-column label="类型" width="90"
                  ><template #default="{ row }">{{
                    row.mediaType === 'VIDEO' ? 'VIDEO' : 'IMAGE'
                  }}</template></el-table-column
                >
                <el-table-column :label="mediaStorageColumnLabel" width="120"
                  ><template #default="{ row }"
                    ><span class="media-status" :class="mediaHqClass(row)">{{
                      mediaHqLabel(row.hqStatus, row.hqUrl, row.mediaType)
                    }}</span></template
                  ></el-table-column
                >
                <el-table-column label="LQ 状态" width="110"
                  ><template #default="{ row }"
                    ><span v-if="row.mediaType === 'VIDEO'" class="media-status is-na">不适用</span
                    ><span v-else class="media-status" :class="row.lqStatus === 'READY' ? 'is-ready' : 'is-pending'">{{
                      mediaLqLabel(row.lqStatus)
                    }}</span></template
                  ></el-table-column
                >
                <el-table-column label="处理建议" min-width="150"
                  ><template #default="{ row }"
                    ><span class="media-hint" :class="mediaHintClass(row)">{{ mediaActionHint(row) }}</span></template
                  ></el-table-column
                >
              </el-table>
            </div>
          </template>
        </template>
        <div v-else class="selection-empty">
          <span class="empty-mark">✦</span>
          <h2>选择一个目录或章节</h2>
          <p>左侧目录树用于导航，选中节点后这里会显示详细内容。</p>
        </div>
      </main>

      <aside class="action-panel">
        <template v-if="selectedRow?.kind === 'CATALOG'">
          <PanelHeader title="目录操作" eyebrow="MAINTENANCE" />
          <el-form label-position="top" class="action-form">
            <el-form-item label="操作"
              ><el-select v-model="catalogForm.action"
                ><el-option
                  v-for="item in CATALOG_ACTIONS"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value" /></el-select
            ></el-form-item>
            <el-form-item v-if="['create', 'rename'].includes(catalogForm.action)" label="目录标题"
              ><el-input v-model="catalogForm.title" placeholder="输入目录标题"
            /></el-form-item>
            <el-form-item v-if="['create', 'move'].includes(catalogForm.action)" label="父目录 ID"
              ><el-input-number
                v-model="catalogForm.parentId"
                :min="1"
                :controls="false"
                placeholder="留空为根级"
                clearable
            /></el-form-item>
            <el-form-item v-if="catalogForm.action === 'reorder'" label="目标顺序"
              ><el-input-number v-model="catalogForm.order" :min="1" :controls="false"
            /></el-form-item>
            <el-form-item v-if="catalogForm.action === 'delete'" label="重挂目标 ID"
              ><el-input-number v-model="catalogForm.reparentTo" :min="1" :controls="false" clearable
            /></el-form-item>
            <el-button type="primary" block @click="submitCatalog">执行目录操作</el-button>
          </el-form>
        </template>
        <template v-else-if="selectedMediaIds.length">
          <div class="action-card action-card--media-batch">
            <div class="action-card-head">
              <div>
                <span class="panel-kicker">BATCH MEDIA MAINTENANCE</span>
                <h2>批量媒体操作</h2>
                <p>仅处理当前章节中勾选的媒体。</p>
              </div>
              <span class="action-id">{{ selectedMediaIds.length }} ITEMS</span>
            </div>
            <div class="batch-selection-summary">
              <strong>{{ selectedMediaIds.length }}</strong
              ><span>个媒体已选择</span>
            </div>
            <el-button type="danger" block @click="trashSelectedMediaBatch">回收选中媒体</el-button>
            <el-button plain block @click="clearMediaSelection">取消选择</el-button>
          </div>
        </template>
        <template v-else-if="selectedMedia">
          <div class="action-card action-card--media">
            <div class="action-card-head">
              <div>
                <span class="panel-kicker">MEDIA MAINTENANCE</span>
                <h2>媒体操作</h2>
                <p>只修改当前选中的媒体，不影响同章节其他文件。</p>
              </div>
              <span class="action-id">MEDIA · {{ selectedMedia.id }}</span>
            </div>
            <div class="action-context">
              <span class="context-mark">{{ selectedMedia.mediaType === 'VIDEO' ? '▶' : '▧' }}</span>
              <div>
                <strong>{{ selectedMedia.fileName || '未命名媒体' }}</strong
                ><small
                  >第 {{ selectedMedia.pageNumber }} 项 ·
                  {{ selectedMedia.mediaType === 'VIDEO' ? '视频' : '图片' }}</small
                >
              </div>
            </div>
            <div class="media-detail-grid">
              <div>
                <small>{{ selectedMedia.mediaType === 'VIDEO' ? '源文件' : 'HQ' }}</small
                ><strong>{{
                  mediaHqLabel(selectedMedia.hqStatus, selectedMedia.hqUrl, selectedMedia.mediaType)
                }}</strong>
              </div>
              <div>
                <small>LQ</small
                ><strong>{{
                  selectedMedia.mediaType === 'VIDEO' ? '不适用' : mediaLqLabel(selectedMedia.lqStatus)
                }}</strong>
              </div>
              <div>
                <small>HQ 大小</small><strong>{{ mediaSizeLabel(selectedMedia) }}</strong>
              </div>
              <div>
                <small>LQ 大小</small><strong>{{ mediaLqSizeLabel(selectedMedia) }}</strong>
              </div>
              <div>
                <small>分辨率</small><strong>{{ mediaResolution(selectedMedia) }}</strong>
              </div>
              <div>
                <small>时长</small
                ><strong>{{
                  selectedMedia.mediaType === 'VIDEO' ? formatDuration(selectedMedia.duration) : '不适用'
                }}</strong>
              </div>
              <div>
                <small>容器 / 编码</small><strong>{{ mediaCodec(selectedMedia) }}</strong>
              </div>
              <div>
                <small>转码</small
                ><strong>{{
                  selectedMedia.mediaType === 'VIDEO' ? transcodeLabel(selectedMedia.transcodeStatus) : '不适用'
                }}</strong>
              </div>
            </div>
            <div class="media-operation-note">
              HQ 删除和 LQ 生成属于章节级操作。当前媒体面板只执行针对这一份文件的操作。
            </div>
            <div class="media-action-buttons">
              <el-button
                v-if="selectedMedia.mediaType === 'VIDEO'"
                type="warning"
                plain
                block
                @click="transcodeSelectedMedia"
                >转码此视频</el-button
              ><el-button type="danger" plain block @click="trashSelectedMedia">回收此媒体</el-button>
            </div>
          </div>
        </template>
        <template v-else-if="selectedRow?.kind === 'CHAPTER'">
          <div class="action-card action-card--chapter">
            <div class="action-card-head">
              <div>
                <span class="panel-kicker">CHAPTER MAINTENANCE</span>
                <h2>章节操作</h2>
                <p>只修改当前选中的章节，不影响其他章节。</p>
              </div>
              <span class="action-id">CH · {{ selectedRow.id }}</span>
            </div>
            <div class="action-context">
              <span class="context-mark">▱</span>
              <div>
                <strong>{{ selectedRow.title }}</strong
                ><small>全书顺序 {{ selectedRow.order ?? '—' }}</small>
              </div>
            </div>
            <nav class="chapter-workspace-tabs" aria-label="章节功能分区">
              <AppButton
                type="button"
                :class="{ 'is-active': chapterWorkspaceTab === 'chapter' }"
                @click="chapterWorkspaceTab = 'chapter'"
              >
                <span>▱</span><strong>章节</strong><small>编辑与排序</small></AppButton
              ><AppButton
                type="button"
                :class="{ 'is-active': chapterWorkspaceTab === 'media' }"
                @click="chapterWorkspaceTab = 'media'"
              >
                <span>▤</span><strong>媒体</strong><small>排序与补充</small></AppButton
              ><AppButton
                type="button"
                :class="{ 'is-active': chapterWorkspaceTab === 'storage' }"
                @click="chapterWorkspaceTab = 'storage'"
              >
                <span>◈</span><strong>存储</strong><small>HQ / LQ</small>
              </AppButton>
            </nav>
            <el-form v-if="chapterWorkspaceTab === 'chapter'" label-position="top" class="action-form">
              <div class="chapter-action-toolbar">
                <div><span class="panel-kicker">COMMAND DECK</span><strong>章节命令面板</strong></div>
                <el-button class="create-chapter-button" type="primary" plain @click="toggleCreateChapter"
                  ><span class="create-chapter-icon">＋</span
                  >{{ chapterForm.action === 'create' ? '返回当前章节' : '新建章节' }}</el-button
                >
              </div>
              <div v-if="chapterForm.action !== 'create'" class="chapter-choice">
                <label>选择要执行的操作</label>
                <div class="chapter-choice-grid">
                  <AppButton
                    v-for="item in CHAPTER_ACTIONS"
                    :key="item.value"
                    type="button"
                    :class="{ 'is-active': chapterForm.action === item.value, 'is-danger': item.value === 'trash' }"
                    @click="selectChapterAction(item.value)"
                  >
                    <span class="chapter-choice-icon">{{ chapterActionIcon(item.value) }}</span
                    ><span class="chapter-choice-copy"
                      ><strong>{{ item.label }}</strong
                      ><small>{{ chapterActionTagline(item.value) }}</small></span
                    ><span class="chapter-choice-arrow">→</span>
                  </AppButton>
                </div>
                <div class="chapter-choice-help">
                  <span class="help-mark">i</span><small>{{ chapterActionDescription(chapterForm.action) }}</small>
                </div>
              </div>
              <div v-else class="create-context">
                <span class="context-mark">＋</span>
                <div><strong>新建章节</strong><small>将在当前漫画中创建一个新章节</small></div>
              </div>
              <div v-if="['create', 'rename'].includes(chapterForm.action)" class="form-grid">
                <el-form-item label="章节标题"
                  ><el-input v-model="chapterForm.title" placeholder="输入章节标题" /></el-form-item
                ><el-form-item label="原始章节编号"
                  ><el-input v-model="chapterForm.chapterNo" placeholder="如 01、番外"
                /></el-form-item>
              </div>
              <el-form-item v-if="chapterForm.action === 'create'" label="目标目录 ID"
                ><el-input-number
                  v-model="chapterForm.catalogId"
                  :min="1"
                  :controls="false"
                  clearable
                  placeholder="留空为根目录"
              /></el-form-item>
              <el-form-item v-if="chapterForm.action === 'move'" label="移动到目录"
                ><el-select v-model="chapterForm.catalogId" clearable placeholder="选择目标目录，留空为根目录"
                  ><el-option label="根目录" :value="null" /><el-option
                    v-for="catalog in catalogOptions"
                    :key="catalog.id"
                    :label="catalog.title"
                    :value="catalog.id" /></el-select
              ></el-form-item>
              <div v-if="chapterForm.action === 'reorder'" class="chapter-reorder-box">
                <div class="chapter-position">
                  <span>当前位置</span><strong>{{ selectedRow?.order ?? '—' }}</strong>
                </div>
                <span class="position-arrow">→</span
                ><el-form-item label="移动到第几位"
                  ><el-input-number
                    v-model="chapterForm.order"
                    :min="1"
                    :controls="true"
                    placeholder="输入新位置" /></el-form-item
                ><small>按全书阅读顺序调整，目标位置不能与当前位置相同。</small>
              </div>
              <el-button
                class="action-submit"
                :type="chapterForm.action === 'trash' ? 'danger' : 'primary'"
                block
                @click="submitChapter"
                >{{ chapterForm.action === 'trash' ? '回收当前章节' : '执行章节操作' }}</el-button
              >
            </el-form>
          </div>
          <div v-if="chapterWorkspaceTab === 'media'" class="chapter-feature-grid">
            <section class="chapter-feature-card feature-order">
              <div class="feature-card-top">
                <span class="panel-kicker">MEDIA ORDER</span
                ><span class="feature-count">{{ mediaOrderItems.length }} 项</span>
              </div>
              <strong>媒体顺序</strong>
              <p>调整本章阅读顺序</p>
              <el-button plain block @click="mediaOrderDialogVisible = true">打开排序面板</el-button>
            </section>
            <section class="chapter-feature-card feature-intake">
              <div class="feature-card-top">
                <span class="panel-kicker">MEDIA INTAKE</span><span class="feature-mark">＋</span>
              </div>
              <strong>补充媒体</strong>
              <p>追加图片或替换当前媒体</p>
              <div class="feature-button-row">
                <el-button type="primary" block @click="openUploadDialog()">上传媒体</el-button
                ><el-button v-if="selectedMedia" plain block @click="openReplaceSelectedMedia">替换</el-button>
              </div>
            </section>
          </div>
          <div v-else-if="chapterWorkspaceTab === 'storage'" class="chapter-feature-grid">
            <section class="chapter-feature-card feature-storage feature-storage--focus">
              <div class="feature-card-top">
                <span class="panel-kicker">STORAGE / CHAPTER</span
                ><StorageStatusTag v-if="selectedStorageChapter" :status="selectedStorageChapter.hqStatus" type="hq" />
              </div>
              <strong>章节存储</strong>
              <p>
                HQ {{ formatSize(selectedStorageChapter?.hqSize ?? 0) }} · LQ
                {{ formatSize(selectedStorageChapter?.lqSize ?? 0) }}
              </p>
              <div class="storage-focus-status">
                <span>LQ 状态</span><span v-if="mediaLqApplicableCount === 0" class="media-status is-na">不适用</span
                ><StorageStatusTag
                  v-else-if="selectedStorageChapter"
                  :status="selectedStorageChapter.lqStatus"
                  type="lq"
                />
              </div>
              <div class="feature-button-row">
                <el-button
                  type="primary"
                  plain
                  :disabled="mediaLqApplicableCount === 0"
                  block
                  @click="generateChapterLq"
                  >{{ chapterLqActionLabel }}</el-button
                ><el-button type="danger" plain block @click="deleteChapterHq">删除 HQ</el-button
                ><el-button v-if="mediaVideoCount > 0" type="warning" plain block @click="transcodeChapter"
                  >转码视频</el-button
                >
              </div>
            </section>
          </div>
        </template>
        <div v-else class="action-empty">
          <span class="empty-mark">＋</span>
          <p>选择节点后显示可用操作。</p>
        </div>
      </aside>
    </section>
  </div>

  <el-dialog
    v-model="uploadDialogVisible"
    width="min(720px, calc(100vw - 32px))"
    class="media-upload-dialog"
    destroy-on-close
  >
    <template #header>
      <div class="upload-dialog-heading">
        <span class="panel-kicker">MEDIA INTAKE</span>
        <h2>{{ uploadReplaceMediaId ? '替换章节媒体' : '上传章节媒体' }}</h2>
        <p>{{ selectedRow?.title }} · 章节 ID {{ selectedRow?.id }}</p>
      </div>
    </template>
    <div class="upload-dialog-body">
      <div class="upload-mode">
        <span class="mode-mark">{{ uploadReplaceMediaId ? '↻' : '+' }}</span>
        <div>
          <strong>{{ uploadReplaceMediaId ? '替换当前选中的媒体' : '追加到当前章节' }}</strong
          ><small>{{ uploadReplaceMediaId ? '只能选择一个图片或视频文件。' : '可一次选择多个图片或视频文件。' }}</small>
        </div>
      </div>
      <label class="upload-dropzone" :class="{ 'is-ready': uploadRows.length > 0 }"
        ><input
          type="file"
          multiple
          :accept="uploadReplaceMediaId ? 'image/*,video/*' : 'image/*,video/*'"
          @change="onUploadFilesSelected"
        /><span class="dropzone-icon">↑</span
        ><strong>{{ uploadRows.length ? `已选择 ${uploadRows.length} 个文件` : '选择文件或拖入此处' }}</strong
        ><small>支持图片与视频 · 上传前会校验 SHA-256</small></label
      >
      <div v-if="uploadRows.length" class="upload-file-list">
        <div v-for="row in uploadRows" :key="row.id" class="upload-file-row">
          <div>
            <strong>{{ row.file.name }}</strong
            ><small>{{ formatSize(row.file.size) }} · {{ row.status }}</small>
          </div>
          <el-progress :percentage="row.progress" :show-text="false" />
        </div>
      </div>
      <div v-if="uploadSessionId" class="upload-session-note">
        会话 {{ uploadSessionId }} · {{ uploadStatus }}<span v-if="uploadTaskId"> · 任务 #{{ uploadTaskId }}</span>
      </div>
    </div>
    <template #footer
      ><el-button @click="uploadDialogVisible = false">关闭</el-button
      ><el-button v-if="uploadSessionId && uploadRunning" type="danger" plain @click="cancelUpload">取消上传</el-button
      ><el-button
        type="primary"
        :loading="uploadRunning"
        :disabled="!uploadRows.length || uploadRunning"
        @click="startUpload"
        >开始上传</el-button
      ></template
    >
  </el-dialog>
  <el-dialog
    v-model="mediaOrderDialogVisible"
    width="min(760px, calc(100vw - 32px))"
    class="media-order-dialog"
    destroy-on-close
  >
    <template #header
      ><div class="upload-dialog-heading">
        <span class="panel-kicker">MEDIA ORDER</span>
        <h2>媒体顺序</h2>
        <p>{{ selectedRow?.title }} · {{ mediaOrderItems.length }} 个媒体</p>
      </div></template
    >
    <div class="media-order-dialog-body">
      <div class="order-toolbar">
        <AppButton type="button" @click="sortMediaByName">按文件名排序</AppButton
        ><AppButton type="button" @click="resetMediaOrder">恢复当前顺序</AppButton
        ><span class="order-dialog-hint">拖动卡片调整阅读顺序</span>
      </div>
      <div class="media-order-list media-order-list--dialog" :class="{ 'is-dirty': mediaOrderDirty }">
        <div
          v-for="(item, index) in mediaOrderItems"
          :key="item.id"
          class="media-order-item"
          draggable="true"
          @dragstart="startMediaDrag(index)"
          @dragover.prevent
          @drop="dropMedia(index)"
        >
          <span class="drag-handle" aria-hidden="true">⠿</span
          ><span class="order-number">{{ String(index + 1).padStart(2, '0') }}</span
          ><span class="order-type">{{ item.mediaType === 'VIDEO' ? 'VID' : 'IMG' }}</span
          ><span class="order-file" :title="item.fileName || `媒体 ${item.id}`">{{
            item.fileName || `媒体 ${item.id}`
          }}</span
          ><span class="order-id">#{{ item.id }}</span>
        </div>
        <div v-if="!mediaOrderItems.length" class="order-empty">当前章节暂无可排序媒体</div>
      </div>
      <details class="advanced-order">
        <summary>高级编辑：按 ID 输入顺序</summary>
        <p>适合批量处理。ID 必须完整且不重复，提交前会覆盖上方拖拽顺序。</p>
        <el-input v-model="mediaOrder" type="textarea" :rows="3" placeholder="例如 128905,128906,128907" /><el-button
          text
          @click="applyAdvancedMediaOrder"
          >应用到列表</el-button
        >
      </details>
    </div>
    <template #footer
      ><span class="order-dialog-status">{{
        mediaOrderDirty ? `已调整 ${mediaOrderChangeCount} 项` : '顺序未修改'
      }}</span
      ><el-button @click="mediaOrderDialogVisible = false">关闭</el-button
      ><el-button type="primary" :disabled="!mediaOrderDirty" @click="saveMediaOrderAndClose"
        >保存媒体顺序</el-button
      ></template
    >
  </el-dialog>
  <el-dialog
    v-model="storageDialogVisible"
    width="min(520px, calc(100vw - 32px))"
    class="storage-dialog"
    destroy-on-close
  >
    <template #header
      ><div class="upload-dialog-heading">
        <span class="panel-kicker">STORAGE</span>
        <h2>章节存储</h2>
        <p>{{ selectedRow?.title }} · 查看占用并执行存储操作</p>
      </div></template
    >
    <div class="storage-dialog-body">
      <div class="storage-mini-grid">
        <div>
          <small>HQ 占用</small><strong>{{ formatSize(selectedStorageChapter?.hqSize ?? 0) }}</strong>
        </div>
        <div>
          <small>LQ 占用</small><strong>{{ formatSize(selectedStorageChapter?.lqSize ?? 0) }}</strong>
        </div>
      </div>
      <div class="storage-dialog-status">
        <span>HQ 状态</span
        ><StorageStatusTag v-if="selectedStorageChapter" :status="selectedStorageChapter.hqStatus" type="hq" /><span
          >LQ 状态</span
        ><StorageStatusTag v-if="selectedStorageChapter" :status="selectedStorageChapter.lqStatus" type="lq" />
      </div>
      <div class="storage-action-buttons">
        <el-button type="danger" plain @click="deleteChapterHq">删除本章 HQ</el-button
        ><el-button v-if="mediaLqApplicableCount > 0" type="primary" plain @click="generateChapterLq">{{
          chapterLqActionLabel
        }}</el-button
        ><el-button v-if="mediaVideoCount > 0" type="warning" plain @click="transcodeChapter">转码本章视频</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { StatGrid } from '@/shared/ui/management-panel'
import { PanelHeader } from '@/shared/ui/management-panel'
import { StatCard } from '@/shared/ui/management-panel'
import { PageHeader } from '@/shared/ui/page-header'
import { formatBytes as formatSize } from '@/shared/lib/format/bytes'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  catalogManagementApi,
  chapterManagementApi,
  managementCatalogApi,
  managementChapterApi,
  mediaManagementApi,
} from '@/entities/comic/api/management-api'
import { hqApi } from '@/entities/storage/api'
import { uploadApi as trackedUploadApi } from '@/features/upload/model/api'
import { storageAdminApi } from '@/entities/storage/api'
import { storageService } from '@/features/storage/service'
import {
  CATALOG_ACTIONS,
  CHAPTER_ACTIONS,
  countRows,
  filterStructureRows,
  findStructureRow,
  flattenCatalogOptions,
  toStructureRows,
} from '@/entities/comic/model/structure'
import type { CatalogAction, ChapterAction, StructureRow } from '@/entities/comic/model/structure'
import StorageStatusTag from '@/entities/storage/ui/StorageStatusTag.vue'
import type { CatalogNode } from '@/entities/comic/model/types'
import type { MediaItemInfo } from '@/entities/media/types'
import type { ChapterStorageItem } from '@/entities/storage/model/types'
import { StorageOperationType as StorageOperation } from '@/entities/storage/model/types'
import type { CreateUploadSessionRequest, UploadFileManifest } from '@/features/upload/model/types'
import { useMediaOrder } from '../model/useMediaOrder'

const route = useRoute()
const comicId = ref(Number(route.params.id) || 1)
const tree = ref<readonly CatalogNode[]>([])
const mediaItems = ref<readonly MediaItemInfo[]>([])
const selectedMedia = ref<MediaItemInfo | null>(null)
const selectedMediaIds = ref<number[]>([])
const mediaTableRef = ref()
const storageChapters = ref<readonly ChapterStorageItem[]>([])
const selectedStorageChapter = computed(() =>
  selectedRow.value?.kind === 'CHAPTER'
    ? (storageChapters.value.find((chapter) => chapter.chapterId === selectedRow.value?.id) ?? null)
    : null,
)
const mediaChapterId = ref(1)
const {
  mediaOrderItems,
  mediaOrder,
  mediaOrderDirty,
  mediaOrderChangeCount,
  syncMediaOrder,
  startMediaDrag,
  dropMedia,
  resetMediaOrder,
  sortMediaByName,
  applyAdvancedMediaOrder,
} = useMediaOrder(mediaItems)
const structureKeyword = ref('')
const selectedRow = ref<StructureRow | null>(null)
const chapterWorkspaceTab = ref<'chapter' | 'media' | 'storage'>('chapter')
const loading = ref(false)
const error = ref('')
type UploadRow = { readonly id: string; readonly file: File; status: string; progress: number; sha256: string }
const uploadDialogVisible = ref(false)
const mediaOrderDialogVisible = ref(false)
const storageDialogVisible = ref(false)
const uploadReplaceMediaId = ref<number | null>(null)
const uploadRows = ref<UploadRow[]>([])
const uploadSessionId = ref('')
const uploadStatus = ref('尚未创建')
const uploadTaskId = ref<number | null>(null)
const uploadRunning = ref(false)
let uploadAbortController: AbortController | undefined
const catalogForm = reactive<{
  action: CatalogAction
  id?: number
  title: string
  parentId?: number
  order?: number
  reparentTo?: number
}>({ action: 'create', title: '' })
const chapterForm = reactive<{
  action: ChapterAction
  id?: number
  title: string
  chapterNo: string
  catalogId?: number
  order?: number
}>({ action: 'create', title: '', chapterNo: '' })
const structureRows = computed<readonly StructureRow[]>(() => tree.value.flatMap(toStructureRows))
const filteredStructureRows = computed<readonly StructureRow[]>(() =>
  filterStructureRows(structureRows.value, structureKeyword.value.trim().toLowerCase()),
)
const catalogOptions = computed(() => flattenCatalogOptions(structureRows.value))
const catalogCount = computed(() => countRows(structureRows.value, 'CATALOG'))
const chapterCount = computed(() => countRows(structureRows.value, 'CHAPTER'))
const rootChapterCount = computed(() => structureRows.value.filter((row) => row.kind === 'CHAPTER').length)
const mediaHqReadyCount = computed(() => mediaItems.value.filter((item) => normalizedHqStatus(item) === 'READY').length)
const mediaLqApplicableCount = computed(() => mediaItems.value.filter((item) => item.mediaType !== 'VIDEO').length)
const mediaLqReadyCount = computed(
  () => mediaItems.value.filter((item) => item.mediaType !== 'VIDEO' && item.lqStatus === 'READY').length,
)
const lqIssueChapters = computed(() =>
  storageChapters.value.filter(
    (chapter) =>
      chapter.mediaType !== 'VIDEO' &&
      ['MIXED', 'NOT_GENERATED', 'FAILED', 'MISSING', 'QUEUED', 'GENERATING'].includes(chapter.lqStatus),
  ),
)
const chapterLqActionLabel = computed(() =>
  selectedStorageChapter.value?.lqStatus === 'READY' ? '重新生成本章 LQ' : '生成本章 LQ',
)
const mediaVideoCount = computed(() => mediaItems.value.filter((item) => item.mediaType === 'VIDEO').length)
const treeState = ref<'idle' | 'loading' | 'loaded' | 'empty' | 'error'>('idle')
const treeStateLabel = computed(
  () =>
    ({ idle: '等待加载', loading: '加载中', loaded: '已加载', empty: '已加载，暂无结构', error: '加载失败' })[
      treeState.value
    ],
)
const emptyStateText = computed(
  () =>
    ({
      idle: '请输入漫画 ID 后加载目录',
      loading: '正在加载目录…',
      loaded: '目录为空',
      empty: '该漫画暂无目录或章节',
      error: '目录加载失败，请重试',
    })[treeState.value],
)

function rowClassName({ row }: { row: StructureRow }): string {
  return row.kind === 'CATALOG' ? 'structure-row--catalog' : 'structure-row--chapter'
}
function selectStructureRow(row: StructureRow): void {
  selectedMedia.value = null
  selectedRow.value = row
  if (row.kind === 'CHAPTER') chapterWorkspaceTab.value = 'chapter'
  if (row.kind === 'CATALOG') {
    catalogForm.id = row.id
    catalogForm.parentId = row.id
    return
  }
  chapterForm.id = row.id
  chapterForm.action = 'rename'
  chapterForm.title = row.title
  chapterForm.chapterNo = row.chapterNo ?? ''
  chapterForm.order = undefined
  mediaChapterId.value = row.id
  void loadMedia()
}
function locateChapter(chapterId: number): void {
  const row = findStructureRow(structureRows.value, chapterId)
  if (row) selectStructureRow(row)
}
function selectMediaRow(row: MediaItemInfo): void {
  selectedMedia.value = row
  chapterWorkspaceTab.value = 'media'
}
function handleMediaSelection(rows: MediaItemInfo[]): void {
  selectedMediaIds.value = rows.map((item) => item.id)
}
function clearMediaSelection(): void {
  selectedMediaIds.value = []
  mediaTableRef.value?.clearSelection()
}
function mediaRowClassName({ row }: { row: MediaItemInfo }): string {
  return selectedMedia.value?.id === row.id ? 'media-row--selected' : ''
}
function mediaLqLabel(status: string): string {
  const labels: Readonly<Record<string, string>> = {
    READY: '已生成',
    QUEUED: '排队中',
    GENERATING: '生成中',
    MISSING: '文件缺失',
    FAILED: '生成失败',
    NOT_GENERATED: '未生成',
  }
  return labels[status] ?? (status || '未生成')
}
const mediaStorageColumnLabel = computed(() => {
  if (mediaItems.value.length > 0 && mediaItems.value.every((item) => item.mediaType === 'VIDEO')) return '源文件状态'
  if (mediaItems.value.some((item) => item.mediaType === 'VIDEO')) return 'HQ / 源文件'
  return 'HQ 状态'
})
function transcodeLabel(status?: string): string {
  const labels: Readonly<Record<string, string>> = {
    NOT_NEEDED: '无需转码',
    PENDING: '待处理',
    QUEUED: '排队中',
    TRANSCODING: '转码中',
    PROCESSING: '转码中',
    READY: '已完成',
    DONE: '已完成',
    FAILED: '失败',
  }
  return labels[status ?? ''] ?? (status || '待检查')
}
function mediaResolution(item: MediaItemInfo): string {
  return item.width && item.height ? `${item.width} × ${item.height}` : '未统计'
}
function mediaSizeLabel(item: MediaItemInfo): string {
  if (normalizedHqStatus(item) === 'DELETED') return '已删除'
  if (item.hqSize) return formatSize(item.hqSize)
  return '未统计'
}
function mediaLqSizeLabel(item: MediaItemInfo): string {
  if (item.mediaType === 'VIDEO') return '不适用'
  if (item.lqSize) return formatSize(item.lqSize)
  return item.lqStatus === 'READY' ? '已生成（未统计）' : '未生成'
}
function formatDuration(seconds?: number): string {
  if (!seconds || seconds < 0) return '未统计'
  const total = Math.round(seconds)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}
function mediaCodec(item: MediaItemInfo): string {
  if (item.mediaType !== 'VIDEO') return item.container || '图片'
  return [item.container, item.videoCodec, item.audioCodec].filter(Boolean).join(' / ') || '未统计'
}
function chapterActionDescription(action: ChapterAction): string {
  const descriptions: Readonly<Record<ChapterAction, string>> = {
    create: '在当前漫画中新建一个章节',
    rename: '修改章节标题或原始编号',
    move: '将章节移动到其他目录',
    reorder: '调整章节在全书中的阅读顺序',
    trash: '将当前章节移入回收站',
  }
  return descriptions[action]
}
function chapterActionIcon(action: ChapterAction): string {
  return ({ create: '＋', rename: '✎', move: '⇄', reorder: '≡', trash: '⌫' } as const)[action]
}
function chapterActionTagline(action: ChapterAction): string {
  return (
    {
      create: '创建新章节',
      rename: '标题与编号',
      move: '调整目录归属',
      reorder: '改变阅读顺序',
      trash: '移入回收站',
    } as const
  )[action]
}
function selectChapterAction(action: ChapterAction): void {
  chapterForm.action = action
  if (action === 'reorder') chapterForm.order = undefined
}
function toggleCreateChapter(): void {
  if (chapterForm.action === 'create') {
    chapterForm.action = 'rename'
    chapterForm.id = selectedRow.value?.kind === 'CHAPTER' ? selectedRow.value.id : undefined
    chapterForm.title = selectedRow.value?.kind === 'CHAPTER' ? selectedRow.value.title : ''
    chapterForm.chapterNo = selectedRow.value?.kind === 'CHAPTER' ? (selectedRow.value.chapterNo ?? '') : ''
    return
  }
  chapterForm.action = 'create'
  chapterForm.id = undefined
  chapterForm.title = ''
  chapterForm.chapterNo = ''
}
function normalizedHqStatus(item: MediaItemInfo): string {
  return item.hqStatus || (item.hqUrl ? 'READY' : 'UNKNOWN')
}
function mediaHqLabel(status: string | undefined, hqUrl: string | undefined, mediaType?: string): string {
  const normalized = status || (hqUrl ? 'READY' : 'UNKNOWN')
  const labels: Readonly<Record<string, string>> = {
    READY: '已就绪',
    DELETE_QUEUED: '删除排队中',
    DELETING: '删除中',
    DELETED: '已删除',
    MISSING: '文件缺失',
    FAILED: '处理失败',
    PENDING: '待处理',
    UNKNOWN: '状态未同步',
  }
  const label = labels[normalized] ?? normalized
  return mediaType === 'VIDEO' ? `源文件${label}` : label
}
function mediaHqClass(item: MediaItemInfo): string {
  const status = normalizedHqStatus(item)
  if (status === 'READY') return 'is-ready'
  if (status === 'DELETED') return 'is-deleted'
  if (status === 'DELETE_QUEUED' || status === 'DELETING' || status === 'PENDING') return 'is-pending'
  if (status === 'UNKNOWN') return 'is-na'
  return 'is-missing'
}
function mediaActionHint(item: MediaItemInfo): string {
  const hqStatus = normalizedHqStatus(item)
  if (hqStatus === 'DELETED')
    return item.mediaType !== 'VIDEO' && item.lqStatus === 'READY' ? 'HQ 已删除，LQ 可继续使用' : 'HQ 已删除'
  if (hqStatus === 'DELETE_QUEUED' || hqStatus === 'DELETING') return 'HQ 删除处理中'
  if (hqStatus === 'FAILED') return '查看 HQ 处理任务'
  if (hqStatus === 'UNKNOWN') return '刷新阅读服务后重试'
  if (hqStatus !== 'READY') return '先检查 HQ 文件'
  if (item.mediaType === 'VIDEO') return '可检查转码状态'
  if (item.lqStatus === 'READY') return '无需处理'
  if (item.lqStatus === 'FAILED') return '重新生成 LQ'
  return '可生成 LQ'
}
function mediaHintClass(item: MediaItemInfo): string {
  const hqStatus = normalizedHqStatus(item)
  if (hqStatus === 'DELETED') return 'is-info'
  if (hqStatus !== 'READY' || item.lqStatus === 'FAILED') return 'is-alert'
  if (item.mediaType === 'VIDEO') return 'is-info'
  return item.lqStatus === 'READY' ? 'is-ok' : 'is-actionable'
}
function errorMessage(reason: unknown): string {
  if (axios.isAxiosError<{ message?: string }>(reason)) return reason.response?.data?.message ?? reason.message
  return reason instanceof Error ? reason.message : '未知错误'
}
function openUploadDialog(replaceMediaId?: number): void {
  if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return
  uploadReplaceMediaId.value = replaceMediaId ?? null
  uploadRows.value = []
  uploadSessionId.value = ''
  uploadTaskId.value = null
  uploadStatus.value = '尚未创建'
  uploadDialogVisible.value = true
}
function openReplaceSelectedMedia(): void {
  if (selectedMedia.value) openUploadDialog(selectedMedia.value.id)
}
function onUploadFilesSelected(event: Event): void {
  const input = event.currentTarget
  if (!(input instanceof HTMLInputElement)) return
  const files = Array.from(input.files ?? [])
  uploadRows.value = (uploadReplaceMediaId.value ? files.slice(0, 1) : files).map((file) => ({
    id: crypto.randomUUID(),
    file,
    status: '等待',
    progress: 0,
    sha256: '',
  }))
  uploadTaskId.value = null
  uploadSessionId.value = ''
  uploadStatus.value = '尚未创建'
}
function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer), (byte) => byte.toString(16).padStart(2, '0')).join('')
}
async function hashUploadFiles(): Promise<readonly UploadFileManifest[]> {
  const manifests: UploadFileManifest[] = []
  for (const row of uploadRows.value) {
    row.status = '计算校验值'
    row.sha256 = toHex(await crypto.subtle.digest('SHA-256', await row.file.arrayBuffer()))
    manifests.push({
      fileId: row.id,
      name: row.file.name,
      contentType: row.file.type || 'application/octet-stream',
      size: row.file.size,
      sha256: row.sha256,
    })
  }
  return manifests
}
async function uploadFile(row: UploadRow, chunkSize: number): Promise<void> {
  let offset = 0
  row.status = '上传中'
  while (offset < row.file.size) {
    const endExclusive = Math.min(offset + chunkSize, row.file.size)
    await trackedUploadApi.uploadChunk({
      sessionId: uploadSessionId.value,
      fileId: row.id,
      chunk: row.file.slice(offset, endExclusive),
      contentRange: `bytes=${offset}-${endExclusive - 1}/${row.file.size}`,
      signal: uploadAbortController?.signal,
    })
    offset = endExclusive
    row.progress = Math.round((offset / row.file.size) * 100)
  }
  row.status = '已上传'
}
async function startUpload(): Promise<void> {
  if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return
  uploadRunning.value = true
  uploadAbortController = new AbortController()
  try {
    const files = await hashUploadFiles()
    const request: CreateUploadSessionRequest = {
      comicId: comicId.value,
      chapterId: selectedRow.value.id,
      ...(uploadReplaceMediaId.value ? { replaceMediaId: uploadReplaceMediaId.value } : {}),
      files,
    }
    const created = (await trackedUploadApi.createSession(request)).data
    uploadSessionId.value = created.sessionId
    uploadStatus.value = '上传中'
    for (const row of uploadRows.value) await uploadFile(row, created.chunkSize)
    uploadStatus.value = '提交任务'
    const completed = (await trackedUploadApi.completeSession(created.sessionId)).data
    uploadTaskId.value = completed.taskId
    uploadStatus.value = '已提交'
    ElMessage.success('媒体上传任务已提交')
    await loadMedia()
    await refreshStorage()
  } catch (reason: unknown) {
    if (!axios.isCancel(reason)) {
      uploadStatus.value = '失败'
      ElMessage.error(errorMessage(reason))
    }
  } finally {
    uploadRunning.value = false
    uploadAbortController = undefined
  }
}
async function cancelUpload(): Promise<void> {
  uploadAbortController?.abort()
  if (uploadSessionId.value) await trackedUploadApi.cancelSession(uploadSessionId.value)
  uploadStatus.value = '已取消'
  uploadRunning.value = false
}
function assertNever(value: never): never {
  throw new TypeError(`未知操作: ${String(value)}`)
}
async function loadTree(): Promise<void> {
  loading.value = true
  treeState.value = 'loading'
  tree.value = []
  selectedRow.value = null
  selectedMedia.value = null
  mediaItems.value = []
  error.value = ''
  try {
    tree.value = [...(await managementCatalogApi.tree(comicId.value)).data]
    treeState.value = tree.value.length > 0 ? 'loaded' : 'empty'
  } catch (reason: unknown) {
    treeState.value = 'error'
    error.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
  try {
    storageChapters.value = await storageService.fetchChapters(comicId.value)
  } catch {
    storageChapters.value = []
  }
  const requestedChapterId = Number(route.query.chapterId)
  if (Number.isSafeInteger(requestedChapterId) && requestedChapterId > 0) locateChapter(requestedChapterId)
}
async function submitCatalog(): Promise<void> {
  try {
    const id = catalogForm.id ?? 0
    switch (catalogForm.action) {
      case 'create':
        await catalogManagementApi.create(comicId.value, {
          title: catalogForm.title.trim(),
          parentId: catalogForm.parentId ?? null,
        })
        break
      case 'rename':
        await catalogManagementApi.rename(comicId.value, id, { title: catalogForm.title.trim() })
        break
      case 'move':
        await catalogManagementApi.move(comicId.value, id, { parentId: catalogForm.parentId ?? null })
        break
      case 'reorder':
        await catalogManagementApi.reorder(comicId.value, id, { sortOrder: catalogForm.order })
        break
      case 'delete':
        await ElMessageBox.confirm('删除目录前请确认重挂目标。', '确认删除', { type: 'warning' })
        await catalogManagementApi.delete(comicId.value, id, catalogForm.reparentTo)
        break
      default:
        assertNever(catalogForm.action)
    }
    ElMessage.success('目录操作完成')
    await loadTree()
  } catch (reason: unknown) {
    ElMessage.error(errorMessage(reason))
  }
}
async function submitChapter(): Promise<void> {
  try {
    const id = chapterForm.id ?? 0
    switch (chapterForm.action) {
      case 'create':
        await chapterManagementApi.create(comicId.value, {
          title: chapterForm.title.trim(),
          chapterNo: chapterForm.chapterNo.trim(),
          catalogId: chapterForm.catalogId ?? null,
        })
        break
      case 'rename':
        await chapterManagementApi.rename(comicId.value, id, {
          title: chapterForm.title.trim() || undefined,
          chapterNo: chapterForm.chapterNo.trim() || undefined,
        })
        break
      case 'move':
        await chapterManagementApi.move(comicId.value, id, { catalogId: chapterForm.catalogId ?? null })
        break
      case 'reorder':
        if (!chapterForm.order) {
          ElMessage.warning('请输入目标顺序')
          return
        }
        if (chapterForm.order === selectedRow.value?.order) {
          ElMessage.info('目标顺序与当前位置相同，无需提交')
          return
        }
        await chapterManagementApi.reorder(comicId.value, id, { targetGlobalOrder: chapterForm.order })
        break
      case 'trash':
        await ElMessageBox.confirm('章节将进入回收站。', '确认回收', { type: 'warning' })
        await chapterManagementApi.trash(comicId.value, id)
        break
      default:
        assertNever(chapterForm.action)
    }
    ElMessage.success('章节操作完成')
    await loadTree()
  } catch (reason: unknown) {
    ElMessage.error(errorMessage(reason))
  }
}
async function loadMedia(): Promise<void> {
  try {
    mediaItems.value = (await managementChapterApi.detail(mediaChapterId.value)).data.pages
    syncMediaOrder(mediaItems.value)
    selectedMedia.value = null
    selectedMediaIds.value = []
    mediaTableRef.value?.clearSelection()
  } catch (reason: unknown) {
    ElMessage.error(errorMessage(reason))
  }
}
async function reorderMedia(): Promise<void> {
  const mediaIds = mediaOrderItems.value.map((item) => item.id)
  if (!mediaIds.length) return
  try {
    await mediaManagementApi.reorder(mediaChapterId.value, { mediaIds })
    ElMessage.success('媒体顺序已保存')
    await loadMedia()
  } catch (reason: unknown) {
    ElMessage.error(errorMessage(reason))
  }
}
async function saveMediaOrderAndClose(): Promise<void> {
  await reorderMedia()
  if (!mediaOrderDirty.value) mediaOrderDialogVisible.value = false
}
async function deleteChapterHq(): Promise<void> {
  if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return
  try {
    await ElMessageBox.confirm('确定删除当前章节的 HQ？LQ 文件会保留。', '删除章节 HQ', { type: 'warning' })
    await storageService.executeOperation({
      type: StorageOperation.DeleteHQ,
      comicId: comicId.value,
      chapterId: selectedRow.value.id,
    })
    ElMessage.success('HQ 删除任务已提交')
    await refreshStorage()
  } catch (reason: unknown) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason))
  }
}
async function generateChapterLq(): Promise<void> {
  if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return
  try {
    const regenerate = selectedStorageChapter.value?.lqStatus === 'READY'
    await storageService.executeOperation({
      type: StorageOperation.GenerateLQ,
      comicId: comicId.value,
      chapterId: selectedRow.value.id,
      regenerate,
    })
    ElMessage.success(regenerate ? '本章 LQ 重建任务已提交' : '本章 LQ 生成任务已提交')
    await refreshStorage()
  } catch (reason: unknown) {
    ElMessage.error(errorMessage(reason))
  }
}
async function transcodeChapter(): Promise<void> {
  if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return
  try {
    await ElMessageBox.confirm('确定对当前章节的视频发起转码？', '章节视频转码', { type: 'warning' })
    await storageAdminApi.transcodeChapter(selectedRow.value.id)
    ElMessage.success('章节视频转码任务已提交')
    await loadMedia()
  } catch (reason: unknown) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason))
  }
}
async function transcodeSelectedMedia(): Promise<void> {
  if (!selectedMedia.value || selectedMedia.value.mediaType !== 'VIDEO') return
  try {
    await ElMessageBox.confirm('确定对当前视频发起转码？', '视频转码', { type: 'warning' })
    await hqApi.transcodeMedia(selectedMedia.value.id)
    ElMessage.success('视频转码任务已提交')
    await loadMedia()
  } catch (reason: unknown) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason))
  }
}
async function trashSelectedMedia(): Promise<void> {
  if (!selectedMedia.value) return
  try {
    await ElMessageBox.confirm(`确定将「${selectedMedia.value.fileName || '此媒体'}」移入回收站？`, '回收媒体', {
      type: 'warning',
    })
    await mediaManagementApi.trash(selectedMedia.value.id)
    ElMessage.success('媒体回收任务已提交')
    selectedMedia.value = null
    await loadMedia()
  } catch (reason: unknown) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason))
  }
}
async function trashSelectedMediaBatch(): Promise<void> {
  if (!selectedMediaIds.value.length) return
  const mediaIds = [...selectedMediaIds.value]
  try {
    await ElMessageBox.confirm(`确定将选中的 ${mediaIds.length} 个媒体移入回收站？`, '批量回收媒体', {
      type: 'warning',
    })
    await Promise.all(mediaIds.map((mediaId) => mediaManagementApi.trash(mediaId)))
    ElMessage.success(`已提交 ${mediaIds.length} 个媒体回收任务`)
    selectedMedia.value = null
    clearMediaSelection()
    await loadMedia()
  } catch (reason: unknown) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason))
  }
}
async function refreshStorage(): Promise<void> {
  try {
    storageChapters.value = await storageService.fetchChapters(comicId.value)
  } catch {
    storageChapters.value = []
  }
  await loadMedia()
}

onMounted(() => {
  void loadTree()
})
</script>

<style scoped src="@/pages/management/comic-structure/intake.css"></style>
<style scoped src="@/pages/management/comic-structure/browser.css"></style>
<style scoped src="@/pages/management/comic-structure/actions.css"></style>
