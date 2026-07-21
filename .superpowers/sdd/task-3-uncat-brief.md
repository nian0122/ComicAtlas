# Task 3: 前端管理端 ComicListPage 新增选项 + 互斥

> 来源:`docs/superpowers/plans/2026-07-19-uncategorized-untagged-filter.md` Task 3。本文件是你的完整需求。

## Global Constraints

- 提交信息中文(conventional)
- 哨兵值 `_NONE`
- 只改: `frontend/src/views/management/ComicListPage.vue`
- vue-tsc EXIT=0

## Steps

- [ ] **Step 1: 模板 —— 分类 el-select 追加**

在第 22-29 行,`<el-select v-model="filters.category"` 内,`v-for="c in categoryStore.list"` 的 `<el-option>` **之前**插入:

```html
<el-option label="未分类" value="_NONE" />
```

- [ ] **Step 2: 模板 —— 标签 el-select 追加**

在第 33-49 行标签 `<el-select v-model="filters.tags"` 内,现有 `v-for="t in tagStore.list"` option 之后、`</el-select>` 之前追加:

```html
<el-option label="无标签" value="_NONE" />
```

- [ ] **Step 3: script —— import 追加 watch, nextTick**

将 `import { ref, reactive, computed, onMounted } from 'vue'` 改为:

```typescript
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
```

- [ ] **Step 4: script —— 互斥 watch**

在 `applyFilters()` 函数之前追加(管理端 `filters.tags` 是 reactive 属性,watch 用 getter):

```typescript
watch(() => filters.tags, (val) => {
  if (!val.includes('_NONE')) return
  if (val.length > 1) {
    nextTick(() => {
      filters.tags = val.filter(v => v !== '_NONE')
    })
  }
}, { deep: true })
```

逻辑与阅读端一致:仅 `_NONE` 存在时干预,多选正常标签时不进入过滤分支避免无限循环;`nextTick` 处理 el-select 内部状态时序。管理端用 `() => filters.tags` getter(因为 tags 是 reactive 对象属性,非 ref)。

- [ ] **Step 5: vue-tsc**

```bash
npx vue-tsc -b --noEmit
```
Expected: EXIT=0

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/management/ComicListPage.vue
git commit -m "feat(管理端): 漫画列表支持未分类与无标签筛选,无标签与正常标签互斥"
```
