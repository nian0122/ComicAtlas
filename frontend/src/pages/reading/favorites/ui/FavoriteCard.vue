<template>
  <article class="favorite-card">
    <router-link class="cover" :to="destination" :aria-label="item.title">
      <el-image :src="item.previewUrl || item.coverUrl" fit="cover" lazy>
        <template #error
          ><div class="placeholder">
            <el-icon><Picture /></el-icon></div
        ></template>
      </el-image>
      <span v-if="item.mediaType === 'VIDEO'" class="video-label">
        <MaterialSymbolIcon name="play" /> {{ durationLabel }}
      </span>
    </router-link>
    <div class="card-info">
      <router-link class="title" :to="destination"
        >{{ item.title }}<span v-if="item.targetType === 'MEDIA'"> · 第 {{ item.pageNumber }} 页</span></router-link
      >
      <router-link
        v-if="item.targetType !== 'COMIC'"
        class="source"
        :to="{ name: 'comic-detail', params: { id: item.comicId } }"
        >{{ item.comicTitle }}</router-link
      >
      <span v-else class="source">{{
        item.lastReadChapterId ? `最近读至第 ${item.lastReadPageNumber || 1} 页` : '尚未阅读'
      }}</span>
      <div class="card-actions">
        <router-link
          v-if="item.targetType !== 'MEDIA' && item.lastReadChapterId"
          class="continue"
          :to="{
            name: 'reader',
            params: { chapterId: item.lastReadChapterId },
            query: { page: item.lastReadPageNumber || 1 },
          }"
          >继续阅读</router-link
        >
        <AppButton
          variant="ghost"
          size="sm"
          icon-only
          :disabled="busy"
          aria-label="取消喜欢"
          aria-pressed="true"
          title="取消喜欢"
          @click="emit('remove', item)"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 20.2 4.7 13a4.8 4.8 0 0 1 6.8-6.8L12 6.7l.5-.5A4.8 4.8 0 0 1 19.3 13L12 20.2Z" />
          </svg>
        </AppButton>
      </div>
    </div>
  </article>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { Picture } from '@element-plus/icons-vue'
import type { FavoriteItem } from '@/features/favorites'
import { AppButton } from '@/shared/ui/button'
import { MaterialSymbolIcon } from '@/shared/ui/icon'
const props = defineProps<{ item: FavoriteItem; busy: boolean }>()
const emit = defineEmits<{ remove: [item: FavoriteItem] }>()
const destination = computed(() => {
  const item = props.item
  if (item.targetType === 'COMIC') return { name: 'comic-detail', params: { id: item.comicId } }
  return {
    name: item.targetType === 'MEDIA' ? 'chapter-videos' : 'reader',
    params: { chapterId: item.chapterId },
    query: { page: item.targetType === 'MEDIA' ? item.pageNumber || 1 : item.lastReadPageNumber || 1 },
  }
})
const durationLabel = computed(() => {
  const seconds = Math.floor(props.item.duration || 0)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
})
</script>
<style scoped>
.favorite-card {
  min-width: 0;
}
.cover {
  position: relative;
  display: block;
  aspect-ratio: 2 / 3;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--surface-elevated);
}
.cover .el-image {
  width: 100%;
  height: 100%;
}
.placeholder {
  display: grid;
  place-items: center;
  height: 100%;
  color: var(--text-muted);
  font-size: 32px;
}
.video-label {
  position: absolute;
  bottom: var(--space-2);
  right: var(--space-2);
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--color-overlay-scrim);
  color: var(--color-on-brand);
  font-size: var(--text-xs);
}
.video-label svg {
  width: 16px;
  height: 16px;
}
.card-info {
  padding-top: var(--space-3);
}
.title,
.source {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.title {
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-weight: 600;
}
.source {
  margin-top: var(--space-1);
  color: var(--text-muted);
  font-size: var(--text-xs);
}
.card-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 40px;
}
.continue {
  color: var(--accent);
  font-size: var(--text-xs);
}
.card-actions .app-button {
  margin-left: auto;
  color: var(--accent);
}
.card-actions svg {
  width: 20px;
  height: 20px;
  fill: currentColor;
}
</style>
