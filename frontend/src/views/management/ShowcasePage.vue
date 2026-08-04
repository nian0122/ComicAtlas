<template>
  <div class="showcase-page">
    <header class="showcase-header">
      <h1 class="showcase-title">管理端原语展示门</h1>
      <p class="showcase-subtitle">
        开发期组件状态 harness &mdash; 所有原语状态一览。Fixture query:
        <code>?case=long-cjk|empty|long-error|media-10k</code>
      </p>
    </header>

    <!-- ================================================================ -->
    <!-- 1. 管理状态标签                                                     -->
    <!-- ================================================================ -->
    <section class="primitive-section" data-primitive-section="status-tag">
      <h2 class="section-title">管理状态标签</h2>
      <div class="primitive-row">
        <span data-primitive="status-tag" data-state="success" class="status-tag status-tag--success" role="status">HQ 就绪</span>
        <span data-primitive="status-tag" data-state="warning" class="status-tag status-tag--warning" role="status">未生成</span>
        <span data-primitive="status-tag" data-state="danger" class="status-tag status-tag--danger" role="status">HQ 缺失</span>
        <span data-primitive="status-tag" data-state="info" class="status-tag status-tag--info" role="status">HQ 已删</span>
        <span data-primitive="status-tag" data-state="neutral" class="status-tag status-tag--neutral" role="status">无数据</span>
        <span data-primitive="status-tag" data-state="warning" class="status-tag status-tag--warning" role="status">生成中</span>
        <span data-primitive="status-tag" data-state="success" class="status-tag status-tag--success" role="status">LQ 就绪</span>
        <span data-primitive="status-tag" data-state="danger" class="status-tag status-tag--danger" role="status">生成失败</span>
      </div>
    </section>

    <!-- ================================================================ -->
    <!-- 2. 操作按钮                                                        -->
    <!-- ================================================================ -->
    <section class="primitive-section" data-primitive-section="action-btn">
      <h2 class="section-title">操作按钮</h2>

      <!-- variant=primary -->
      <div class="primitive-row">
        <button data-primitive="action-btn" data-variant="primary" data-state="default" class="action-btn action-btn--primary">主要操作</button>
        <button data-primitive="action-btn" data-variant="primary" data-state="hover" class="action-btn action-btn--primary is-hover">悬浮态</button>
        <button data-primitive="action-btn" data-variant="primary" data-state="focus" class="action-btn action-btn--primary is-focus">聚焦态</button>
        <button data-primitive="action-btn" data-variant="primary" data-state="disabled" class="action-btn action-btn--primary" disabled>禁用态</button>
        <button data-primitive="action-btn" data-variant="primary" data-state="loading" class="action-btn action-btn--primary" aria-busy="true" disabled>
          <span class="action-btn-spinner" aria-hidden="true" />
          <span>加载中</span>
        </button>
      </div>

      <!-- variant=secondary -->
      <div class="primitive-row">
        <button data-primitive="action-btn" data-variant="secondary" data-state="default" class="action-btn action-btn--secondary">次要操作</button>
        <button data-primitive="action-btn" data-variant="secondary" data-state="disabled" class="action-btn action-btn--secondary" disabled>禁用态</button>
        <button data-primitive="action-btn" data-variant="secondary" data-state="loading" class="action-btn action-btn--secondary" aria-busy="true" disabled>
          <span class="action-btn-spinner" aria-hidden="true" />
          <span>加载中</span>
        </button>
      </div>

      <!-- variant=ghost -->
      <div class="primitive-row">
        <button data-primitive="action-btn" data-variant="ghost" data-state="default" class="action-btn action-btn--ghost">幽灵操作</button>
        <button data-primitive="action-btn" data-variant="ghost" data-state="disabled" class="action-btn action-btn--ghost" disabled>禁用态</button>
        <button data-primitive="action-btn" data-variant="ghost" data-state="loading" class="action-btn action-btn--ghost" aria-busy="true" disabled>
          <span class="action-btn-spinner" aria-hidden="true" />
          <span>加载中</span>
        </button>
      </div>

      <!-- variant=danger -->
      <div class="primitive-row">
        <button data-primitive="action-btn" data-variant="danger" data-state="default" class="action-btn action-btn--danger-ghost">删除</button>
        <button data-primitive="action-btn" data-variant="danger-filled" data-state="default" class="action-btn action-btn--danger-filled">永久删除</button>
        <button data-primitive="action-btn" data-variant="danger-filled" data-state="disabled" class="action-btn action-btn--danger-filled" disabled>禁用态</button>
      </div>
    </section>

    <!-- ================================================================ -->
    <!-- 3. 批量选择栏                                                      -->
    <!-- ================================================================ -->
    <section class="primitive-section" data-primitive-section="batch-bar">
      <h2 class="section-title">批量选择栏</h2>

      <!-- active 状态 -->
      <div data-primitive="batch-bar" data-state="active" class="batch-bar">
        <div class="batch-bar-info">
          <el-icon :size="16"><Check /></el-icon>
          <span data-batch-count>已选 <strong>3</strong> 项</span>
          <button class="batch-link" type="button">全选</button>
          <button class="batch-link" type="button">取消选择</button>
        </div>
        <div class="batch-bar-actions">
          <button class="action-btn action-btn--secondary">批量生成 LQ</button>
          <button class="action-btn action-btn--danger-ghost">批量删除 HQ</button>
        </div>
      </div>

      <!-- loading 状态 -->
      <div data-primitive="batch-bar" data-state="loading" class="batch-bar batch-bar--loading" style="margin-top: var(--space-4)">
        <div class="batch-bar-info">
          <span class="action-btn-spinner" aria-hidden="true" />
          <span>正在批量删除 HQ&hellip;</span>
        </div>
      </div>

      <!-- hidden 状态 -->
      <template v-if="isFixtureEmpty">
        <div data-primitive="batch-bar" data-state="hidden" class="batch-bar batch-bar--hidden" aria-hidden="true">
          <span class="batch-bar-info">未选中任何项</span>
        </div>
      </template>
    </section>

    <!-- ================================================================ -->
    <!-- 4. 任务进度                                                        -->
    <!-- ================================================================ -->
    <section class="primitive-section" data-primitive-section="task-progress">
      <h2 class="section-title">任务进度</h2>

      <div class="task-progress-list">
        <!-- queued -->
        <div data-primitive="task-progress" data-state="queued" class="task-progress-item task-progress-item--queued">
          <span class="task-progress-status">等待中</span>
          <div class="task-progress-track" role="progressbar" aria-valuenow="0" aria-valuemin="0" aria-valuemax="100">
            <div class="task-progress-fill" :style="{ width: '0%' }" />
          </div>
          <span class="task-progress-pct">—</span>
        </div>

        <!-- running -->
        <div data-primitive="task-progress" data-state="running" class="task-progress-item task-progress-item--running">
          <span class="task-progress-status">导入中</span>
          <div class="task-progress-track" role="progressbar" aria-valuenow="67" aria-valuemin="0" aria-valuemax="100">
            <div class="task-progress-fill" :style="{ width: '67%' }" />
          </div>
          <span class="task-progress-pct">67%</span>
          <span class="task-progress-eta">剩余 2 分 14 秒</span>
        </div>

        <!-- completed -->
        <div data-primitive="task-progress" data-state="completed" class="task-progress-item task-progress-item--completed">
          <span class="task-progress-status">已完成</span>
          <div class="task-progress-track" role="progressbar" aria-valuenow="100" aria-valuemin="0" aria-valuemax="100">
            <div class="task-progress-fill" :style="{ width: '100%' }" />
          </div>
          <span class="task-progress-pct">100%</span>
        </div>

        <!-- failed -->
        <div data-primitive="task-progress" data-state="failed" class="task-progress-item task-progress-item--failed">
          <span class="task-progress-status">失败</span>
          <div class="task-progress-track" role="progressbar" aria-valuenow="22" aria-valuemin="0" aria-valuemax="100">
            <div class="task-progress-fill" :style="{ width: '22%' }" />
          </div>
          <span class="task-progress-pct">22%</span>
          <p v-if="isFixtureLongError" data-fixture="long-error-block" class="task-progress-error">
            {{ longErrorMessage }}
          </p>
          <p v-else class="task-progress-error">文件损坏: unexpected EOF at offset 0x4A2F</p>
        </div>

        <!-- cancelled -->
        <div data-primitive="task-progress" data-state="cancelled" class="task-progress-item task-progress-item--cancelled">
          <span class="task-progress-status">已取消</span>
          <div class="task-progress-track" role="progressbar" aria-valuenow="0" aria-valuemin="0" aria-valuemax="100">
            <div class="task-progress-fill" :style="{ width: '0%' }" />
          </div>
          <span class="task-progress-pct">—</span>
        </div>
      </div>
    </section>

    <!-- ================================================================ -->
    <!-- 5. 目录树行                                                        -->
    <!-- ================================================================ -->
    <section class="primitive-section" data-primitive-section="tree-row">
      <h2 class="section-title">目录树行</h2>

      <div class="tree-demo" role="tree">
        <!-- 展开 -->
        <div data-primitive="tree-row" data-state="expanded" class="tree-row" role="treeitem" aria-expanded="true" tabindex="0">
          <span class="tree-arrow tree-arrow--expanded" aria-hidden="true" />
          <span :class="['tree-title', isFixtureLongCjk ? 'tree-title--cjk' : '']" data-fixture="long-cjk-content">
            {{ isFixtureLongCjk ? '第1卷 转生成了绝对不想忘记的究极魔法少女在异世界被最强的暗杀者大小姐捡到后每天都过得心惊胆战的同居生活' : '第1卷' }}
          </span>
          <span class="tree-badge">12</span>
        </div>
        <!-- 子节点 -->
        <div data-primitive="tree-row" data-state="default" class="tree-row tree-row--child" role="treeitem" tabindex="-1">
          <span class="tree-arrow tree-arrow--leaf" aria-hidden="true" />
          <span class="tree-title">第1话</span>
          <span class="tree-badge">24</span>
        </div>
        <div data-primitive="tree-row" data-state="default" class="tree-row tree-row--child" role="treeitem" tabindex="-1">
          <span class="tree-arrow tree-arrow--leaf" aria-hidden="true" />
          <span class="tree-title">第2话</span>
          <span class="tree-badge">22</span>
        </div>

        <!-- 折叠 -->
        <div data-primitive="tree-row" data-state="collapsed" class="tree-row" role="treeitem" aria-expanded="false" tabindex="-1">
          <span class="tree-arrow tree-arrow--collapsed" aria-hidden="true" />
          <span class="tree-title">第2卷</span>
          <span class="tree-badge">8</span>
        </div>

        <!-- 选中 -->
        <div data-primitive="tree-row" data-state="selected" class="tree-row tree-row--selected" role="treeitem" aria-expanded="true" tabindex="-1">
          <span class="tree-arrow tree-arrow--expanded" aria-hidden="true" />
          <span class="tree-title">第3卷</span>
          <span class="tree-badge">5</span>
        </div>

        <!-- empty（无子节点） -->
        <div data-primitive="tree-row" data-state="empty" class="tree-row tree-row--empty" role="treeitem" tabindex="-1">
          <span class="tree-arrow tree-arrow--leaf" aria-hidden="true" />
          <span class="tree-title tree-title--empty">无章节</span>
        </div>

        <!-- loading -->
        <div data-primitive="tree-row" data-state="loading" class="tree-row tree-row--loading" role="treeitem" aria-busy="true" tabindex="-1">
          <span class="action-btn-spinner" aria-hidden="true" />
          <span class="tree-title" style="margin-left: var(--space-2)">加载中&hellip;</span>
        </div>
      </div>
    </section>

    <!-- ================================================================ -->
    <!-- 6. 媒体缩略格                                                      -->
    <!-- ================================================================ -->
    <section class="primitive-section" data-primitive-section="media-thumb">
      <h2 class="section-title">媒体缩略格</h2>

      <div v-if="isFixtureMedia10k" class="media-grid media-grid--dense">
        <div v-for="i in 100" :key="'m10k-' + i"
          data-primitive="media-thumb" data-fixture="media-grid-item"
          data-state="default" data-media-type="image"
          class="media-thumb"
          aria-label="图片：page_{{ String(i).padStart(4, '0') }}.jpg">
          <div class="media-thumb-placeholder">
            <span class="media-thumb-index">{{ i }}</span>
          </div>
          <span class="media-thumb-name">page_{{ String(i).padStart(4, '0') }}.jpg</span>
        </div>
      </div>

      <div v-else class="media-grid">
        <!-- default 图片 -->
        <div data-primitive="media-thumb" data-state="default" data-media-type="image"
          class="media-thumb" aria-label="图片：001.jpg">
          <div class="media-thumb-placeholder">
            <span class="media-thumb-icon" aria-hidden="true">img</span>
          </div>
          <span class="media-thumb-name">001.jpg</span>
        </div>

        <!-- selected -->
        <div data-primitive="media-thumb" data-state="selected" data-media-type="image"
          class="media-thumb media-thumb--selected" aria-label="图片：002.jpg（已选中）">
          <div class="media-thumb-placeholder">
            <span class="media-thumb-icon" aria-hidden="true">img</span>
          </div>
          <span class="media-thumb-name">002.jpg</span>
        </div>

        <!-- broken -->
        <div data-primitive="media-thumb" data-state="broken" data-media-type="image"
          class="media-thumb media-thumb--broken" aria-label="图片：003.jpg（无法加载）">
          <div class="media-thumb-placeholder">
            <span class="media-thumb-icon media-thumb-icon--broken" aria-hidden="true">!</span>
          </div>
          <span class="media-thumb-name media-thumb-name--broken">003.jpg</span>
          <span class="media-thumb-error-text">无法加载</span>
        </div>

        <!-- loading -->
        <div data-primitive="media-thumb" data-state="loading" data-media-type="image"
          class="media-thumb media-thumb--loading" aria-busy="true" aria-label="加载中">
          <div class="media-thumb-placeholder">
            <span class="action-btn-spinner" aria-hidden="true" />
          </div>
        </div>

        <!-- video -->
        <div data-primitive="media-thumb" data-state="default" data-media-type="video"
          class="media-thumb" aria-label="视频：opening.mp4">
          <div class="media-thumb-placeholder">
            <span class="media-thumb-icon" aria-hidden="true">vid</span>
            <span class="media-thumb-play" aria-hidden="true" />
          </div>
          <span class="media-thumb-name">opening.mp4</span>
          <span class="media-thumb-duration">02:34</span>
        </div>

        <!-- empty -->
        <div v-if="isFixtureEmpty" data-fixture="empty-placeholder" class="media-thumb media-thumb--empty">
          <div class="media-thumb-placeholder media-thumb-placeholder--empty">
            <span class="media-thumb-empty-text">暂无媒体</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ================================================================ -->
    <!-- 7. 上传队列                                                        -->
    <!-- ================================================================ -->
    <section class="primitive-section" data-primitive-section="upload-queue-item">
      <h2 class="section-title">上传队列</h2>

      <div class="upload-queue" role="list">
        <!-- queued -->
        <div data-primitive="upload-queue-item" data-state="queued" class="upload-item" role="listitem">
          <span class="upload-item-name">chapter-01.zip</span>
          <span class="upload-item-source">ZIP</span>
          <span class="status-tag status-tag--neutral">等待中</span>
        </div>

        <!-- uploading -->
        <div data-primitive="upload-queue-item" data-state="uploading" class="upload-item" role="listitem">
          <span class="upload-item-name">chapter-02.zip</span>
          <span class="upload-item-source">ZIP</span>
          <div class="task-progress-track task-progress-track--compact" role="progressbar" aria-valuenow="45" aria-valuemin="0" aria-valuemax="100">
            <div class="task-progress-fill" :style="{ width: '45%' }" />
          </div>
          <span class="upload-item-pct">45%</span>
          <button class="batch-link" type="button">取消</button>
        </div>

        <!-- completed -->
        <div data-primitive="upload-queue-item" data-state="completed" class="upload-item" role="listitem">
          <span class="upload-item-name">chapter-03.zip</span>
          <span class="upload-item-source">EHENTAI</span>
          <span class="status-tag status-tag--success">已完成</span>
          <button class="batch-link" type="button">移除</button>
        </div>

        <!-- failed -->
        <div data-primitive="upload-queue-item" data-state="failed" class="upload-item" role="listitem">
          <span class="upload-item-name">chapter-04.zip</span>
          <span class="upload-item-source">ZIP</span>
          <span class="status-tag status-tag--danger">失败</span>
          <span class="upload-item-error">解压错误: 文件不完整</span>
          <button class="batch-link" type="button">重试</button>
        </div>

        <!-- cancelled -->
        <div data-primitive="upload-queue-item" data-state="cancelled" class="upload-item" role="listitem">
          <span class="upload-item-name">chapter-05.zip</span>
          <span class="upload-item-source">目录</span>
          <span class="status-tag status-tag--neutral">已取消</span>
        </div>
      </div>
    </section>

    <!-- ================================================================ -->
    <!-- 8. 回收站行                                                        -->
    <!-- ================================================================ -->
    <section class="primitive-section" data-primitive-section="bin-row">
      <h2 class="section-title">回收站行</h2>

      <div class="bin-table">
        <div data-primitive="bin-row" data-state="default" class="bin-row">
          <span class="bin-row-type" aria-hidden="true">漫画</span>
          <span class="bin-row-name" data-fixture="long-cjk-content">{{ isFixtureLongCjk ? '转生成了绝对不想忘记的究极魔法少女在异世界被最强的暗杀者大小姐捡到后每天都过着心惊胆战的同居生活' : '某科学的超电磁炮' }}</span>
          <span class="bin-row-date">2026-07-28 14:32</span>
          <div class="bin-row-actions">
            <button data-action="restore" class="batch-link" type="button">恢复</button>
            <button data-action="delete-permanent" class="batch-link batch-link--danger" type="button">永久删除</button>
          </div>
        </div>

        <div data-primitive="bin-row" data-state="selected" class="bin-row bin-row--selected">
          <span class="bin-row-type" aria-hidden="true">章节</span>
          <span class="bin-row-name">第3卷第8话</span>
          <span class="bin-row-date">2026-08-01 09:15</span>
          <div class="bin-row-actions">
            <button data-action="restore" class="batch-link" type="button">恢复</button>
            <button data-action="delete-permanent" class="batch-link batch-link--danger" type="button">永久删除</button>
          </div>
        </div>

        <!-- restoring -->
        <div data-primitive="bin-row" data-state="restoring" class="bin-row" aria-busy="true">
          <span class="bin-row-type" aria-hidden="true">漫画</span>
          <span class="bin-row-name">进击的巨人</span>
          <span class="bin-row-date">2026-07-30 18:00</span>
          <div class="bin-row-actions">
            <span class="action-btn-spinner" aria-hidden="true" />
            <span class="bin-row-action-text">恢复中&hellip;</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ================================================================ -->
    <!-- 9. 危险确认对话框                                                   -->
    <!-- ================================================================ -->
    <section class="primitive-section" data-primitive-section="danger-dialog">
      <h2 class="section-title">危险确认对话框</h2>

      <button data-primitive="danger-dialog-trigger" class="action-btn action-btn--danger-filled" type="button" @click="showDangerDialog = true">
        打开危险确认对话框
      </button>

      <!-- 对话框（通过 v-if 模拟，生产环境用 el-dialog） -->
      <Teleport to="body">
        <div v-if="showDangerDialog" class="danger-dialog-overlay" @click.self="showDangerDialog = false">
          <div
            role="alertdialog"
            aria-labelledby="danger-dialog-title"
            aria-describedby="danger-dialog-desc"
            class="danger-dialog"
          >
            <h3 id="danger-dialog-title" class="danger-dialog-title">永久删除确认</h3>
            <p id="danger-dialog-desc" class="danger-dialog-desc">
              此操作将永久删除 <strong>3 部漫画</strong> 及其所有章节和媒体文件。此操作<em>不可撤销</em>。
            </p>
            <div class="danger-dialog-list">
              <span>你的标题太长以至于一行都显示不完整_卷一</span>
              <span>某科学的超电磁炮</span>
              <span>进击的巨人 最终季 Part 3</span>
            </div>
            <p class="danger-dialog-input-label">请输入 <code>永久删除</code> 以确认：</p>
            <input
              v-model="dangerConfirmInput"
              class="danger-dialog-input"
              type="text"
              placeholder="永久删除"
              @input="onDangerInput"
            />
            <div class="danger-dialog-actions">
              <button class="action-btn action-btn--ghost" type="button" @click="showDangerDialog = false">取消</button>
              <button
                class="action-btn action-btn--danger-filled"
                type="button"
                :disabled="!dangerConfirmed || dangerExecuting"
                @click="executeDangerAction"
              >
                <template v-if="dangerExecuting">
                  <span class="action-btn-spinner" aria-hidden="true" />
                  <span>删除中</span>
                </template>
                <template v-else>永久删除 3 部漫画</template>
              </button>
            </div>
          </div>
        </div>
      </Teleport>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Check } from '@element-plus/icons-vue'

const route = useRoute()

// 极值状态 fixture
const fixtureCase = computed(() => (route.query.case as string) ?? '')
const isFixtureLongCjk = computed(() => fixtureCase.value === 'long-cjk')
const isFixtureEmpty = computed(() => fixtureCase.value === 'empty')
const isFixtureLongError = computed(() => fixtureCase.value === 'long-error')
const isFixtureMedia10k = computed(() => fixtureCase.value === 'media-10k')

const longErrorMessage =
  '致命错误: 在解析阶段出现未预期的数据结构异常 (StructureException: RootElement mismatch at line 0x4A2F, expected <manifest> but found <corrupted-chunk>). 这通常意味着源文件在传输过程中被截断, 或解压时发生 I/O 错误. 请重新下载源文件后重试. 附加信息: java.nio.file.FileSystemException: D:\\manga\\temp\\import_42\\chapter_01\\page_099.jpg: 进程无法访问文件，因为另一个进程正在使用它. 建议: 检查防病毒软件是否锁定了该目录, 或重启后重试.'

// 危险对话框状态
const showDangerDialog = ref(false)
const dangerConfirmInput = ref('')
const dangerConfirmed = ref(false)
const dangerExecuting = ref(false)

function onDangerInput() {
  dangerConfirmed.value = dangerConfirmInput.value === '永久删除'
}

function executeDangerAction() {
  dangerExecuting.value = true
  setTimeout(() => {
    dangerExecuting.value = false
    showDangerDialog.value = false
    dangerConfirmInput.value = ''
    dangerConfirmed.value = false
  }, 2000)
}
</script>

<style scoped>
/* ================================================================== */
/* 页面布局                                                            */
/* ================================================================== */
.showcase-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-10);
  padding-bottom: var(--space-16);
}

.showcase-header {
  padding-bottom: var(--space-6);
  border-bottom: 1px solid var(--border);
}

.showcase-title {
  margin: 0 0 var(--space-2);
  font-size: var(--text-page);
  font-weight: 700;
  color: var(--text-primary);
}

.showcase-subtitle {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-muted);
}

.showcase-subtitle code {
  padding: 2px 6px;
  border-radius: var(--radius-xs);
  background: var(--bg-primary);
  color: var(--accent);
  font-family: var(--mono);
  font-size: var(--text-xs);
}

/* ================================================================== */
/* 区块                                                               */
/* ================================================================== */
.primitive-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}

.section-title {
  margin: 0;
  font-size: var(--text-section);
  font-weight: 700;
  color: var(--text-primary);
}

.primitive-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

/* ================================================================== */
/* 1. 状态标签                                                         */
/* ================================================================== */
.status-tag {
  display: inline-flex;
  align-items: center;
  min-height: var(--control-min-size);
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: 600;
  border: 1px solid transparent;
  white-space: nowrap;
}

.status-tag--success {
  color: var(--success);
  background: rgb(102 197 139 / 10%);
  border-color: var(--success);
}
.status-tag--warning {
  color: var(--warning);
  background: rgb(216 165 79 / 10%);
  border-color: var(--warning);
}
.status-tag--danger {
  color: var(--danger);
  background: rgb(240 107 112 / 10%);
  border-color: var(--danger);
}
.status-tag--info {
  color: var(--info);
  background: rgb(112 166 216 / 10%);
  border-color: var(--info);
}
.status-tag--neutral {
  color: var(--text-muted);
  background: var(--bg-primary);
  border-color: var(--border);
}

/* ================================================================== */
/* 2. 操作按钮                                                         */
/* ================================================================== */
.action-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-height: var(--control-min-size);
  padding: 6px 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-family: var(--font-ui);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition:
    background-color var(--transition-fast),
    border-color var(--transition-fast),
    color var(--transition-fast),
    opacity var(--transition-fast);
  white-space: nowrap;
}

.action-btn:disabled {
  cursor: not-allowed;
  opacity: var(--disabled-opacity);
}

.action-btn:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}

/* primary */
.action-btn--primary {
  background: var(--accent);
  color: var(--color-on-brand);
  border-color: var(--accent);
}
.action-btn--primary:not(:disabled):hover,
.action-btn--primary.is-hover {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
}

/* secondary */
.action-btn--secondary {
  background: var(--surface-highlight);
  color: var(--text-primary);
  border-color: var(--border-strong);
}
.action-btn--secondary:not(:disabled):hover {
  background: var(--bg-primary);
  border-color: var(--text-muted);
}

/* ghost */
.action-btn--ghost {
  background: transparent;
  color: var(--text-primary);
  border-color: var(--border);
}
.action-btn--ghost:not(:disabled):hover {
  background: var(--bg-surface);
  border-color: var(--border-strong);
}

/* danger */
.action-btn--danger-ghost {
  background: transparent;
  color: var(--danger);
  border-color: var(--danger);
}
.action-btn--danger-ghost:not(:disabled):hover {
  background: rgb(240 107 112 / 10%);
}

/* danger-filled */
.action-btn--danger-filled {
  background: var(--danger);
  color: var(--color-on-brand);
  border-color: var(--danger);
}
.action-btn--danger-filled:not(:disabled):hover {
  opacity: 0.88;
}

/* spinner */
.action-btn-spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
  flex-shrink: 0;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ================================================================== */
/* 3. 批量选择栏                                                       */
/* ================================================================== */
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-5);
  border-radius: var(--radius-md);
  background: var(--surface-highlight);
  border: 1px solid var(--border);
}

.batch-bar--hidden {
  opacity: var(--disabled-opacity);
  pointer-events: none;
}

.batch-bar--loading {
  justify-content: flex-start;
}

.batch-bar-info {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.batch-bar-info strong {
  font-weight: 700;
  color: var(--accent);
}

.batch-bar-actions {
  display: flex;
  gap: var(--space-2);
}

.batch-link {
  padding: 0;
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.batch-link:hover {
  color: var(--text-primary);
}
.batch-link:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}
.batch-link--danger {
  color: var(--danger);
}
.batch-link--danger:hover {
  color: var(--danger);
  opacity: 0.8;
}

/* ================================================================== */
/* 4. 任务进度                                                         */
/* ================================================================== */
.task-progress-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-8);
}

.task-progress-item {
  display: grid;
  grid-template-columns: 80px minmax(0, 1fr) auto;
  grid-template-rows: auto auto;
  gap: var(--space-1) var(--space-4);
  align-items: center;
}

.task-progress-status {
  font-size: var(--text-sm);
  font-weight: 600;
}

.task-progress-item--queued .task-progress-status { color: var(--text-muted); }
.task-progress-item--running .task-progress-status { color: var(--warning); }
.task-progress-item--completed .task-progress-status { color: var(--success); }
.task-progress-item--failed .task-progress-status { color: var(--danger); }
.task-progress-item--cancelled .task-progress-status { color: var(--text-muted); }

.task-progress-track {
  height: 6px;
  background: var(--bg-primary);
  border-radius: var(--radius-pill);
  overflow: hidden;
  min-width: 80px;
}

.task-progress-track--compact {
  height: 4px;
  min-width: 60px;
  flex: 1;
}

.task-progress-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  transition: width 400ms var(--ease-out);
}

.task-progress-item--queued .task-progress-fill,
.task-progress-item--cancelled .task-progress-fill { background: var(--text-muted); }
.task-progress-item--running .task-progress-fill { background: var(--accent); }
.task-progress-item--completed .task-progress-fill { background: var(--success); }
.task-progress-item--failed .task-progress-fill { background: var(--danger); }

.task-progress-pct {
  font-size: var(--text-sm);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}

.task-progress-eta {
  grid-column: 2;
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.task-progress-error {
  grid-column: 2;
  margin: var(--space-1) 0 0;
  font-size: var(--text-xs);
  color: var(--danger);
  word-break: break-word;
  overflow-wrap: anywhere;
}

/* ================================================================== */
/* 5. 目录树行                                                         */
/* ================================================================== */
.tree-demo {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px solid var(--border);
}

.tree-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: var(--control-min-size);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--text-secondary);
  cursor: pointer;
  transition: background-color var(--transition-fast);
}
.tree-row:hover { background: var(--surface-highlight); }
.tree-row:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: -2px;
}

.tree-row--child { padding-left: var(--space-8); }
.tree-row--selected {
  background: var(--accent-bg);
  color: var(--text-primary);
}
.tree-row--empty { color: var(--text-muted); }
.tree-row--loading { color: var(--text-muted); }

.tree-arrow {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  color: var(--text-muted);
  transition: transform var(--transition-fast);
}

.tree-arrow--expanded::after { content: "▾"; transform: rotate(0deg); }
.tree-arrow--collapsed::after { content: "▸"; }
.tree-arrow--leaf { visibility: hidden; }

.tree-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-title--cjk {
  word-break: normal;
  overflow-wrap: anywhere;
  white-space: normal;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.tree-title--empty { font-style: italic; }

.tree-badge {
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--bg-primary);
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-muted);
  flex-shrink: 0;
}

/* ================================================================== */
/* 6. 媒体缩略格                                                       */
/* ================================================================== */
.media-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--space-4);
  overflow-x: auto;
  min-block-size: 0;
}

.media-grid--dense {
  grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
  gap: var(--space-2);
}

.media-thumb {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-1);
  border: 2px solid transparent;
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  cursor: pointer;
  transition:
    border-color var(--transition-fast),
    transform var(--transition-fast);
  min-width: 0;
}

.media-thumb:hover {
  border-color: var(--border-strong);
  transform: scale(1.025);
}

.media-thumb--selected {
  border-color: var(--accent);
}

.media-thumb--broken {
  opacity: 0.6;
}

.media-thumb--loading {
  cursor: default;
}

.media-thumb--empty {
  opacity: var(--disabled-opacity);
  cursor: default;
}

.media-thumb-placeholder {
  aspect-ratio: 16 / 9;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  position: relative;
  overflow: hidden;
  min-height: 0;
}

.media-thumb-placeholder--empty {
  border: 1px dashed var(--border-strong);
}

.media-thumb-icon {
  font-size: var(--text-xs);
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
}

.media-thumb-icon--broken {
  color: var(--danger);
  font-size: var(--text-section);
  font-weight: 900;
}

.media-thumb-play {
  position: absolute;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: rgb(0 0 0 / 60%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.media-thumb-play::after {
  content: "";
  display: block;
  width: 0;
  height: 0;
  border-style: solid;
  border-width: 6px 0 6px 10px;
  border-color: transparent transparent transparent var(--color-on-brand);
  margin-left: 2px;
}

.media-thumb-name {
  font-size: 10px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  padding: 0 4px;
}

.media-thumb-name--broken { color: var(--danger); }

.media-thumb-error-text {
  font-size: 10px;
  color: var(--danger);
  font-weight: 600;
  padding: 0 4px;
}

.media-thumb-duration {
  font-size: 10px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  padding: 0 4px;
}

.media-thumb-index {
  font-size: 9px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  font-family: var(--mono);
}

.media-thumb-empty-text {
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-style: italic;
}

/* ================================================================== */
/* 7. 上传队列                                                         */
/* ================================================================== */
.upload-queue {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.upload-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-height: var(--control-min-size);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  flex-wrap: wrap;
}

.upload-item-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}

.upload-item-source {
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  font-size: 10px;
  font-weight: 700;
  color: var(--accent);
  background: var(--accent-bg);
  border: 1px solid var(--accent-border);
}

.upload-item-pct {
  font-size: var(--text-xs);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}

.upload-item-error {
  font-size: var(--text-xs);
  color: var(--danger);
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ================================================================== */
/* 8. 回收站行                                                         */
/* ================================================================== */
.bin-table {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  overflow-x: auto;
  min-block-size: 0;
}

.bin-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-height: var(--control-min-size);
  padding: var(--space-2) var(--space-4);
  border-bottom: 1px solid var(--border);
  transition: background-color var(--transition-fast);
  flex-wrap: nowrap;
}

.bin-row:last-child { border-bottom: none; }
.bin-row:hover { background: var(--surface-highlight); }

.bin-row--selected {
  background: var(--accent-bg);
}

.bin-row-type {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: 10px;
  font-weight: 700;
  color: var(--text-muted);
  background: var(--bg-primary);
  flex-shrink: 0;
}

.bin-row-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.bin-row-date {
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.bin-row-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-shrink: 0;
}

.bin-row-action-text {
  font-size: var(--text-xs);
  color: var(--text-muted);
}

/* ================================================================== */
/* 9. 危险确认对话框                                                    */
/* ================================================================== */
.danger-dialog-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-dialog);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-8);
  background: var(--color-overlay-scrim);
}

.danger-dialog {
  width: 100%;
  max-width: 480px;
  padding: var(--space-8);
  border-radius: var(--radius-lg);
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
}

.danger-dialog-title {
  margin: 0 0 var(--space-4);
  font-size: var(--text-section);
  font-weight: 700;
  color: var(--text-primary);
}

.danger-dialog-desc {
  margin: 0 0 var(--space-4);
  font-size: var(--text-sm);
  color: var(--text-secondary);
  line-height: 1.6;
}

.danger-dialog-desc em {
  color: var(--danger);
  font-style: normal;
  font-weight: 600;
}

.danger-dialog-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin-bottom: var(--space-6);
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  border: 1px solid var(--border);
  font-size: var(--text-xs);
  color: var(--text-muted);
  max-height: 120px;
  overflow-y: auto;
  min-block-size: 0;
}

.danger-dialog-input-label {
  margin: 0 0 var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.danger-dialog-input-label code {
  padding: 1px 4px;
  border-radius: var(--radius-xs);
  background: var(--bg-primary);
  color: var(--danger);
  font-family: var(--mono);
  font-size: var(--text-xs);
}

.danger-dialog-input {
  display: block;
  width: 100%;
  box-sizing: border-box;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  font-family: var(--font-ui);
  font-size: var(--text-sm);
  outline: none;
  transition: border-color var(--transition-fast);
}

.danger-dialog-input:focus {
  border-color: var(--danger);
}

.danger-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-6);
}
</style>
