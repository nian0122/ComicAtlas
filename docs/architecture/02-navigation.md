# ComicAtlas 0.2 导航与路由

**版本**: 0.2  
**日期**: 2026-07-16  
**状态**: Canonical

---

## 顶层导航

```
ComicAtlas    首页    历史    管理
```

- **首页**：阅读首页 `/`，属于 Reading Layout。
- **历史**：阅读历史 `/history`，属于 Reading Layout。
- **管理**：管理入口 `/manage/*`，切换到 Management Layout。

没有 Dashboard、没有统计、没有任务中心、没有操作日志入口。

---

## Layout 结构

```
App
├── ReadingLayout
│   ├── /
│   ├── /library
│   ├── /history
│   └── /comic/:id
├── ReaderLayout          （沉浸式，无顶部导航）
│   └── /reader/:chapterId
└── ManagementLayout
    ├── /manage
    ├── /manage/comics
    ├── /manage/comics/:id/edit
    ├── /manage/import
    ├── /manage/import/tasks
    ├── /manage/storage
    ├── /manage/metadata
    └── /manage/settings
```

---

## Reading Layout 路由

| 路由 | 名称 | 说明 |
|------|------|------|
| `/` | ReadingHome | 阅读首页：继续阅读 + 最近阅读 + 最近加入 + 漫画库 |
| `/library` | ComicLibrary | 完整漫画库：搜索、排序、分类、标签筛选 |
| `/history` | HistoryPage | 阅读历史 |
| `/comic/:id` | ComicDetail | 漫画详情，只展示消费信息，无管理按钮 |

---

## Reader Layout 路由

| 路由 | 名称 | 说明 |
|------|------|------|
| `/reader/:chapterId` | ReaderPage | 沉浸式阅读器，近全屏，隐藏顶部导航 |

### 首页与漫画库的关系

`/` 不是 `/library` 的预览，而是阅读的起点。它包含：

1. 继续阅读（如果有历史）
2. 最近阅读
3. 最近加入
4. 漫画库（第一页，可无限滚动）

点击"查看更多"进入 `/library`，获得完整的搜索、排序、筛选能力。

---

## Management Layout 路由

| 路由 | 名称 | 说明 |
|------|------|------|
| `/manage` | ManagementHome | 重定向到 `/manage/comics`，或作为简单 landing |
| `/manage/comics` | ComicListPage | 漫画列表，点击进入编辑 |
| `/manage/comics/:id/edit` | ComicEditPage | 漫画编辑：基本信息、分类、标签、封面、来源、危险操作 |
| `/manage/import` | ImportPage | 导入入口 |
| `/manage/import/tasks` | TaskPage | 导入任务列表、失败重试 |
| `/manage/storage` | StoragePage | 存储管理：统计、扫描、恢复、清理 |
| `/manage/metadata` | MetadataPage | 元数据：Category / Tag 两个 Tab |
| `/manage/settings` | SettingsPage | 系统设置 |

### 管理导航原则

导航只保留 5 项：

```
漫画    导入    存储    元数据    设置
```

- Cover 属于漫画编辑的子功能，不作为一级菜单。
- Scan / Recovery / Cleanup 属于存储模块的子功能。
- Category / Tag 合并到"元数据"模块内，通过 Tab 切换。

---

## URL 设计原则

1. **简洁**：使用 `/comic/:id` 而非 `/reading/comics/:id`。
2. **可读**：`/reader/:chapterId` 明确表达阅读器。
3. **管理隔离**：所有管理路由以 `/manage` 开头。
4. **不变更 API URL 前缀**：不要为了形式统一把 `/api/comics` 改成 `/api/reading/comics`。
