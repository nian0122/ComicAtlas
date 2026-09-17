<template>
  <div class="comic-card" @click="emit('click', comic.id)">
    <div class="cover-wrapper">
      <el-image :src="comic.coverUrl" fit="cover" lazy class="cover-image">
        <template #error>
          <div class="cover-placeholder">
            <el-icon :size="36"><PictureFilled /></el-icon>
          </div>
        </template>
      </el-image>

      <div v-if="showProgress" class="progress-bar">
        <div class="progress-fill" :style="{ width: `${comic.progressPercent}%` }" />
      </div>

      <div class="card-overlay">
        <AppButton v-if="canContinue" @click.stop="emit('continue', comic.id)"> 继续阅读 </AppButton>
        <AppButton v-else @click.stop="emit('click', comic.id)">查看详情</AppButton>
      </div>

      <ComicStatusTag v-if="comic.status !== 'READY'" class="comic-card__status" :status="comic.status" />
    </div>

    <div class="card-info">
      <p class="comic-title" :title="comic.title">{{ comic.title }}</p>
      <p class="comic-meta">
        <span>{{ comic.author || '未知作者' }}</span>
        <span v-if="comic.pageCount > 0">· {{ comic.pageCount }} 页</span>
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import ComicStatusTag from './ComicStatusTag.vue'
import { computed } from 'vue'
import { PictureFilled } from '@element-plus/icons-vue'
import type { ComicListVO } from '@/entities/comic/model/types'

const props = defineProps<{
  comic: ComicListVO
}>()

const emit = defineEmits<{
  click: [id: number]
  continue: [id: number]
}>()

const showProgress = computed(() => props.comic.progressPercent > 0 && props.comic.progressPercent < 100)

const canContinue = computed(() => props.comic.lastReadChapterId && props.comic.lastReadChapterId > 0)
</script>

<style scoped>
.comic-card {
  cursor: pointer;
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--surface);
  transition: transform 200ms ease;
}

.comic-card:hover {
  transform: scale(1.03);
}

.comic-card:hover .card-overlay {
  opacity: 1;
}

.cover-wrapper {
  position: relative;
  aspect-ratio: 2 / 3;
  overflow: hidden;
  background: var(--surface-elevated);
}

.cover-image {
  width: 100%;
  height: 100%;
  display: block;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}

.progress-bar {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: rgba(255, 255, 255, 0.2);
}

.progress-fill {
  height: 100%;
  background: var(--accent);
}

.card-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.6);
  opacity: 0;
  transition: opacity 200ms ease;
}

.comic-card__status {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: 11px;
  font-weight: 700;
}

.card-info {
  padding: 10px 0;
}

.comic-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-h);
  margin: 0 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.comic-meta {
  font-size: 12px;
  color: var(--text-muted);
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.comic-meta span + span {
  margin-left: 6px;
}
</style>
