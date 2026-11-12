<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { AppSearch } from '@/shared/ui/search'
import { comicApi, type Term } from '@/entities/comic'
import { managementCommands as commands } from '@/features/manage-items'
import { getApiErrorMessage } from '@/shared/api/http'
import { ManagementDialog } from '@/shared/ui/management'
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const kind = ref<'categories' | 'tags'>('categories')
const items = ref<Term[]>([]),
  keyword = ref(''),
  busy = ref(false),
  loading = ref(false),
  error = ref(''),
  notice = ref('')
const selected = ref<Term | null>(null),
  name = ref(''),
  deleting = ref(false)
const filtered = computed(() =>
  items.value.filter((t) => t.name.toLocaleLowerCase().includes(keyword.value.toLocaleLowerCase())),
)
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await (kind.value === 'categories' ? comicApi.categories() : comicApi.tags())
    if (current === generation) items.value = result.items
  } catch (e) {
    if (current === generation) error.value = getApiErrorMessage(e, '加载词条失败')
  } finally {
    if (current === generation) loading.value = false
  }
}
function reset() {
  selected.value = null
  name.value = ''
  deleting.value = false
}
watch(
  () => [props.open, kind.value],
  () => {
    if (props.open) {
      reset()
      items.value = []
      keyword.value = ''
      notice.value = ''
      void load()
    } else {
      generation++
      loading.value = false
    }
  },
  { immediate: true },
)
function refresh() {
  reset()
  void load()
}
function choose(term: Term) {
  selected.value = { ...term }
  name.value = term.name
  deleting.value = false
  error.value = ''
  notice.value = ''
}
async function submit() {
  if (busy.value || loading.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    if (deleting.value && selected.value)
      await commands.deleteTerm(kind.value, selected.value.id, selected.value.version)
    else {
      if (!name.value.trim()) throw new Error('请填写词条名称')
      await commands.saveTerm(kind.value, selected.value?.id ?? null, name.value.trim(), selected.value?.version ?? 0)
    }
    notice.value = deleting.value ? '词条已删除' : '词条已保存'
    reset()
    emit('changed')
    await load()
  } catch (e) {
    error.value = getApiErrorMessage(e, '词条操作失败')
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <ManagementDialog :open="open" title="分类与标签" :busy="busy" placement="right" @close="emit('close')">
    <p class="mw-muted">维护作品使用的资料词条。已关联作品的词条需要先解除关联才能删除。</p>
    <div class="mw-inline-actions" role="group" aria-label="词条类型">
      <button
        v-for="entry in [
          ['categories', '分类'],
          ['tags', '标签'],
        ] as const"
        :key="entry[0]"
        class="mw-button"
        :class="{ 'mw-button-primary': kind === entry[0] }"
        :disabled="busy"
        @click="kind = entry[0]"
      >
        {{ entry[1] }}
      </button>
    </div>
    <p v-if="error" class="mw-error" role="alert">
      {{ error }}
      <button class="mw-button" :disabled="busy" @click="refresh">刷新词条</button>
    </p>
    <p v-if="notice" class="mw-notice" role="status">{{ notice }}</p>
    <label class="mw-field"
      >查找词条<AppSearch v-model="keyword" label="查找词条" :disabled="loading || busy" placeholder="输入名称筛选"
    /></label>
    <div class="term-list" :aria-busy="loading">
      <p v-if="loading" class="mw-muted">正在加载…</p>
      <template v-else
        ><button
          v-for="term in filtered"
          :key="term.id"
          class="mw-button"
          :aria-pressed="selected?.id === term.id"
          :disabled="busy"
          @click="choose(term)"
        >
          {{ term.name }}
        </button>
        <p v-if="!filtered.length" class="mw-muted">没有匹配的词条</p></template
      >
    </div>
    <form @submit.prevent="submit">
      <fieldset :disabled="busy || loading" class="term-editor">
        <legend>{{ selected ? '编辑词条' : '新建词条' }}</legend>
        <template v-if="deleting && selected"
          ><p>确认删除「{{ selected.name }}」？</p>
          <p class="mw-muted">仅删除未被使用的词条；不会删除漫画、章节或文件。</p></template
        >
        <label v-else class="mw-field">词条名称<input v-model="name" required maxlength="200" /></label>
        <footer class="mw-dialog-footer">
          <button
            v-if="selected && !deleting"
            type="button"
            class="mw-button mw-button-danger"
            @click="deleting = true"
          >
            删除词条
          </button>
          <button v-if="selected" type="button" class="mw-button" @click="reset">取消编辑</button>
          <button type="submit" class="mw-button mw-button-primary" :disabled="busy || loading">
            {{ busy ? '正在提交…' : deleting ? '确认删除词条' : selected ? '保存修改' : '新建词条' }}
          </button>
        </footer>
      </fieldset>
    </form>
  </ManagementDialog>
</template>
<style scoped>
.term-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-height: 240px;
  overflow: auto;
  margin-bottom: 24px;
}
.term-list [aria-pressed='true'] {
  border-color: var(--atlas-accent);
  color: var(--atlas-text);
}
.term-editor {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.term-editor legend {
  margin-bottom: 12px;
  font-weight: 600;
}
</style>
