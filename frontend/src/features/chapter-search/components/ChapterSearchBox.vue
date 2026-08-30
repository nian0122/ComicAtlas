<template>
  <div class="chapter-search-box">
    <el-icon class="search-icon" :size="16"><Search /></el-icon>
    <input
      ref="inputElement"
      v-model="modelValue"
      class="unstyled-input"
      type="search"
      placeholder="搜索章节编号、标题或目录"
      aria-label="搜索章节"
      @keydown.esc="clear"
    >
    <button v-if="modelValue" type="button" class="clear-button" aria-label="清空搜索" @click="clear">
      <el-icon :size="14"><Close /></el-icon>
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Close, Search } from '@element-plus/icons-vue'

const modelValue = defineModel<string>({ required: true })
const inputElement = ref<HTMLInputElement | null>(null)

function clear() {
  modelValue.value = ''
  inputElement.value?.focus()
}
</script>

<style scoped>
.chapter-search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: min(320px, 100%);
  height: 38px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--bg-surface) 78%, transparent);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}
.chapter-search-box:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-bg); }
.search-icon { color: var(--text-muted); flex: 0 0 auto; }
input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text-primary); font: inherit; font-size: 13px; }
input::placeholder { color: var(--text-muted); }
input::-webkit-search-cancel-button { display: none; }
.clear-button { display: inline-flex; align-items: center; border: 0; background: transparent; color: var(--text-muted); cursor: pointer; }
.clear-button:hover { color: var(--text-primary); }
@media (max-width: 640px) { .chapter-search-box { min-width: 0; width: 100%; } }
</style>
