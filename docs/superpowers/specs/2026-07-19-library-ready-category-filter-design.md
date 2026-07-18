# 阅读端漫画库:移除状态筛选 + 新增分类筛选

**日期**: 2026-07-19
**状态**: 已确认

## 背景

阅读端(ReadingLayout 下的 LibraryPage / HomePage)展示的应该都是导入成功的漫画:

- 当前后端 `/api/comics` 只排除 `PLACEHOLDER` / `DELETED`,导入中、失败的漫画仍会出现在阅读端列表里
- LibraryPage 的状态筛选(全部/已就绪/导入中/等待中/失败)对阅读端没有意义
- LibraryPage 缺少分类筛选,而后端与管理端均已具备分类能力

## 目标

1. 阅读端列表数据强制只显示 `READY` 漫画(LibraryPage 与 HomePage 一并生效)
2. LibraryPage 移除状态筛选 UI
3. LibraryPage 新增分类筛选

## 方案

**改动范围:纯前端,后端零改动**(后端已支持 `category` 按分类名筛选、`status` 精确匹配)。

### 1. 阅读端强制 READY — `frontend/src/stores/comic-store.ts`

`fetchList()` 发请求时固定合并 `status: 'READY'`:

```ts
const res = await comicApi.list({ ...state.query, status: 'READY' })
```

- 硬编码在请求处而非 query 初始值,任何 `search()` patch 都无法覆盖
- LibraryPage / HomePage 共用此 store,自动生效(HomePage 经 `comicStore.search({ sort: 'createdAt' })` 触发,不传 status,无冲突)
- 管理端使用独立的 `stores/management/comic.ts`(store id `'management-comic'`,阅读端为 `'comic'`),不受影响

### 2. LibraryPage 筛选调整 — `frontend/src/views/reading/LibraryPage.vue`

**删除**:

- `statusFilter` ref
- 状态筛选下拉 UI(`.status-select` 块)
- `onSearch` 中的 `status` 参数
- 桌面端 `.status-select { order: 2 }` 样式

**新增**:分类筛选下拉,放在原状态筛选的位置(移动端筛选行第一个,桌面端 order 2):

- 原生 `<select>`,与现有筛选控件样式一致:`全部分类` + 各分类选项
- 数据来源:`categoryApi.list()`,**从 `@/services/management` import**(`services/reading.ts` 未导出 categoryApi;跟随 LibraryPage 现有 `tagApi` 同源先例),加载模式与现有 `loadTags()` 一致(直调 API,失败时置空数组)
- 选中后 `onSearch` 传 `category: 分类名`(后端按 `cat.name` 精确匹配),空值传 `undefined`

### 3. 类型与接口

- 前端 `ComicListQuery` 已有 `category?: string`,无需改动
- `CategoryDTO` 类型已存在
- 后端 `ComicListQuery.category` + `ComicMapper.selectPage` 的 category 条件已就绪

## 不做的事(YAGNI)

- 不加"未分类"选项(后端不支持按 `category_id IS NULL` 筛选,有需要时另行设计)
- 不动 HomePage 的展示逻辑、管理端页面、后端接口
- 不修 `poster-status.ts` 的 `toPosterStatus`:其 `SUCCESS` 分支是死代码(后端只写 `READY`),`READY` 目前靠 `default` 兜底返回 `'ready'`,行为正确;本次不顺带重构,实施时也不要误改此文件

## 权衡记录

- **后端加 READY 默认过滤?** 否。`/api/comics` 是管理端共用接口,管理端需要看到全部状态;前端阅读端 store 层固定是最小且正确的切入点。
- **分类筛选用 el-select?** 否。跟随 LibraryPage 现有筛选控件模式(原生 select),视觉一致;管理端的 el-select 模式仅作参数传递参考(传分类名)。

## 验收标准

1. 阅读端漫画库与首页列表只出现 `READY` 状态的漫画
2. 漫画库工具栏不再有状态筛选下拉
3. 漫画库出现分类下拉,选择分类后列表只显示该分类的漫画,"全部分类"恢复全量
4. 分类筛选与关键词、标签、排序可叠加使用
5. 管理端漫画列表行为不变(仍可按状态筛选、可见全部状态)
