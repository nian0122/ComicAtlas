# 未分类 & 无标签筛选

**日期**: 2026-07-19
**状态**: 已确认

## 背景

阅读端漫画库和管理端漫画列表均支持按分类筛选和按标签筛选,但缺少"未分类"(category_id IS NULL)和"无标签"(无 comic_tag 关联)的筛选能力。当前数据库中有 48 部漫画未设定分类、49 部漫画未打标签(均为 READY 状态)。

## 目标

1. 分类下拉新增"未分类"选项,选中后筛选 `category_id IS NULL` 的漫画
2. 标签多选新增"无标签"选项,选中后筛选无任何标签关联的漫画
3. "无标签"与正常标签互斥——选中无标签时自动清除其他标签,反之亦然
4. 阅读端和管理端同步生效,接口签名不变

## 方案

**核心思路**:前后端用哨兵值 `_NONE` 通信,不做接口改动。SQL 层识别哨兵值走对应条件分支;前端下拉/多选里加一个选项并通过 watch 实现互斥。

### 后端 — `ComicMapper.java` SQL

文件:`api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicMapper.java`

**(a) category 条件改为 `<choose>`:**

```xml
<if test='query.category != null and query.category != ""'>
    <choose>
        <when test='query.category == "_NONE"'>
            AND c.category_id IS NULL
        </when>
        <otherwise>
            AND EXISTS (SELECT 1 FROM category cat WHERE cat.id = c.category_id AND cat.name = #{query.category})
        </otherwise>
    </choose>
</if>
```

**(b) tags 条件前插入哨兵值分支:**

```xml
<if test='query.tags != null and query.tags.size > 0'>
    <choose>
        <when test='query.tags.contains("_NONE")'>
            AND NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id = c.id)
        </when>
        <otherwise>
            <!-- 现有多 tag + tagMode 逻辑不变 -->
        </otherwise>
    </choose>
</if>
```

当 tags 含 `_NONE` 时直接走 `NOT EXISTS`,忽略 tagMode 和其他 tag 值。

### 前端阅读端 — `LibraryPage.vue`

**(a) 分类下拉**:在 `<option value="">全部分类</option>` 之后插入:

```html
<option value="_NONE">未分类</option>
```

**(b) 标签多选**:在 `v-for="tag in allTags"` 选项之后追加:

```html
<el-option label="无标签" value="_NONE" />
```

**(c) 互斥 watch**:新增 `watch(selectedTags, ...)`:

```typescript
watch(selectedTags, (val) => {
  if (val.includes('_NONE')) {
    // 选了无标签 → 清空正常标签
    selectedTags.value = ['_NONE']
  } else if (val.length > 1) {
    // 选了正常标签(可能有多个) → 移除无标签
    selectedTags.value = val.filter(v => v !== '_NONE')
  }
}, { deep: true })
```

### 前端管理端 — `ComicListPage.vue`

**(a) 分类 el-select**:`categoryStore.list` 的 option 之前插入:

```html
<el-option label="未分类" value="_NONE" />
```

**(b) 标签 el-select**:现有 tag option 之后追加:

```html
<el-option label="无标签" value="_NONE" />
```

**(c) 互斥逻辑**:与阅读端相同(在 `filters.tags` 上做 watch)。

`applyFilters()` 和 `onSearch()` 不改动——`filters.category` / `filters.tags` 原样传给后端,SQL 层处理。

## 不做的事(YAGNI)

- 不新增独立无标签 toggle/checkbox
- 不改 `ComicListQuery` 类型定义(`category`/`tags` 字段复用)
- 不新增 API 接口或后端 service 方法
- "无标签"AND 模式下的组合语义不处理(spec 明确规定哨兵值分支忽略 tagMode,无需特殊语义)
- 哨兵值 `_NONE` 不能作为真实分类名/标签名(全局约定;分类/标签创建/更新 API 已有空白检查,自然排斥下划线开头的名称虽未显式禁止,但 _NONE 不会被用户误用——分类名通常为"少年""青年"等中文,标签同理)

## 验证方式

- e2e:阅读端选"未分类"请求带 `category=_NONE`;选正常分类后切回未分类;选"无标签"请求带 `tags[]=_NONE`,验证互斥行为
- SQL 直连 MySQL 验证两条新增分支:(1)未分类条件返回 48 行,(2)无标签条件返回 49 行
- 管理端同上,手动回归现有关键词/标签/排序叠加筛选不受影响

## 验收标准

1. 阅读端漫画库分类下拉含"未分类",选中后只显示未设定分类的漫画
2. 阅读端标签多选含"无标签",选中后只显示无标签的漫画
3. "无标签"与正常标签互斥:同时只能有一个生效
4. 管理端漫画列表同步具备上述功能
5. 现有筛选功能(关键词/正常分类/正常标签/排序)不受影响
