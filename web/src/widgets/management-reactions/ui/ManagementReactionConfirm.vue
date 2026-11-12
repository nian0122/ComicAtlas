<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  reactionKey,
  reactionTypes,
  type ReactionTarget,
  type ReactionValue,
  type SavedReaction,
} from '@/entities/managed-reaction'
import { saveReactions } from '@/features/manage-reactions'
import { getApiErrorMessage } from '@/shared/api/http'
import { ManagementDialog, ManagementImage } from '@/shared/ui/management'
const props = defineProps<{ open: boolean; items: ReactionTarget[]; scope: 'active' | 'management' }>()
const emit = defineEmits<{ close: []; saved: [result: SavedReaction[]] }>()
const choice = ref<ReactionValue | ''>(''),
  busy = ref(false),
  error = ref('')
watch(
  () => props.open,
  (open) => {
    if (open) {
      choice.value = ''
      error.value = ''
    }
  },
)
async function submit() {
  if (busy.value || !choice.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await saveReactions(props.items, choice.value, props.scope)
    emit('saved', result)
  } catch (e) {
    error.value = getApiErrorMessage(e, '标记保存失败，请刷新后重新核对')
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <ManagementDialog :open="open" title="修改所选标记" :busy="busy" @close="emit('close')">
    <p>本次 {{ items.length }} 项。漫画、章节和媒体的标记相互独立，不会向下批量继承。</p>
    <p class="mw-muted">
      修改或清除只影响标记，不删除文件，也不恢复回收内容。任一对象已不存在或正在变更状态时，整批拒绝。
    </p>
    <p v-if="error" class="mw-error" role="alert">{{ error }}</p>
    <ul class="rating-confirm-items">
      <li v-for="item in items" :key="reactionKey(item)">
        <ManagementImage
          :src="item.hidden ? null : (item.previewUrl ?? null)"
          :alt="item.hidden ? '回收内容不可预览' : item.title"
        />
        <div>
          <strong>{{ item.title }}</strong
          ><small
            >{{ reactionTypes[item.targetType] }}{{ item.comicTitle ? ' · ' + item.comicTitle : ''
            }}{{ item.hidden ? ' · 回收内容' : '' }}</small
          >
        </div>
      </li>
    </ul>
    <form @submit.prevent="submit">
      <label class="mw-field"
        >标记操作<select v-model="choice" :disabled="busy" required>
          <option value="" disabled>请选择操作</option>
          <option value="like">标记喜欢</option>
          <option value="dislike">标记不喜欢</option>
          <option value="none">清除所选标记</option>
        </select></label
      >
      <footer class="mw-dialog-footer">
        <button type="button" class="mw-button" :disabled="busy" @click="emit('close')">取消</button
        ><button class="mw-button mw-button-primary" :disabled="busy || !choice || items.some((x) => !x.canChange)">
          {{ busy ? '正在保存…' : '确认修改标记' }}
        </button>
      </footer>
    </form>
  </ManagementDialog>
</template>
<style scoped>
.rating-confirm-items {
  list-style: none;
  padding: 0;
  max-height: 300px;
  overflow: auto;
}
.rating-confirm-items li {
  display: flex;
  gap: 12px;
  align-items: center;
  border-bottom: 1px solid var(--atlas-line);
  padding: 10px 0;
}
.rating-confirm-items :deep(img),
.rating-confirm-items :deep(.mw-image-empty) {
  width: 42px;
  height: 56px;
  object-fit: contain;
}
.rating-confirm-items div {
  min-width: 0;
}
.rating-confirm-items strong {
  display: block;
  overflow-wrap: anywhere;
}
.rating-confirm-items small {
  display: block;
  color: var(--atlas-muted);
  margin-top: 6px;
}
</style>
