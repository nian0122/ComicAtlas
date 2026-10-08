# 漫画列表筛选：阅读与管理范围

## 职责边界

阅读端的目标是找漫画与继续阅读。桌面端使用 `ReadingDesktopFilterPanel`；`LibraryPage.vue` 的移动端保留原有紧凑分类/标签按钮、横向滑动及排序弹层的布局与样式，两者使用独立于管理端的 `reading-comic-filters.ts` 状态模型，查询 `/api/comics`。后端 `ReadingComicFilterNormalizer` 与阅读列表 SQL 均限制漫画为 `READY`；请求传入其他生命周期也不能扩大阅读范围。

管理端的目标是排查生命周期、来源与文件状态，并执行批量操作。`ManagementComicFilterPanel` 和 `management-comic-filters.ts` 负责管理筛选，查询 `/api/manage/comics`。管理端独立使用 `ManagementComicListQuery`、`ManagementComicFilterNormalizer`、`ManagementComicListMapper`，不复用阅读端的查询条件或状态模型。状态未指定或清空时，默认排除已回收（`TRASHED`）及已永久删除（`DELETED`），保留导入中、导入失败等管理状态；显式选择已回收或已永久删除时仍可查询对应记录。分页总数与批量筛选快照遵循相同范围，默认按创建时间倒序。

两端各自持有筛选实例，仅共用无业务含义的 HTTP 与分页基础设施。旧的 `useLibraryFilters`、`useManagementComicFilters`、`ComicListQueryNormalizer` 已删除；旧共享 Mapper 中的管理存储筛选及批量筛选 SQL 已移至管理域。

## 管理文件筛选

管理文件筛选统计 `STAGING`、`READY` 章节中的 `STAGING`、`READY` 媒体，包含导入暂存阶段，排除已回收、永久删除与回收中的行。HQ 适用于图片和视频，LQ 仅适用于图片；视频不会被归类为“未生成 LQ”。

| 条件 | HQ 值 | LQ 值 | 匹配语义 |
| --- | --- | --- | --- |
| 全部可用 | `ALL_HQ` | `ALL_LQ` | 至少一项适用媒体，且每项对应文件状态均为 `READY` |
| 部分可用 | `PARTIAL_HQ` | `PARTIAL_LQ` | 同时存在 `READY` 与非 `READY` 的适用媒体 |
| 至少一项可用 | `HAS_HQ` | `HAS_LQ` | 存在一项文件状态为 `READY` 的适用媒体，兼容原查询值 |
| 没有可用文件 | `NO_HQ` | `NO_LQ` | 不存在文件状态为 `READY` 的适用媒体，含没有适用媒体的漫画 |
| 具体文件状态 | `PENDING` 等 | `NOT_GENERATED` 等 | 至少一项适用媒体命中所选状态 |

`QUEUED` 是兼容的“排队或生成中”条件，匹配 `QUEUED` 与 `GENERATING`；`GENERATING` 只匹配正在生成的图片。筛选基于数据库记录，不实时探测磁盘。文件丢失需要相应状态核对流程更新数据库后才能反映到列表。

管理排序提供创建时间、更新时间、标题、页数与 HQ 大小。历史请求中的 `lastReadTime` 保持后端兼容，新的管理界面不提供阅读排序。

## 标签、组合与查询一致性

- 关键词去除首尾空白；标签去空白、过滤空值、去重后再执行 `AND` 计数。
- 标签 `OR` 表示任一匹配，`AND` 表示全部匹配，`NOT` 表示排除任何所选标签。
- `_NONE` 表示未分类或无标签。无标签与普通标签互斥，界面保留最近选择；无标签的匹配模式固定为 `OR`，并清除旧单标签条件。
- 不同字段之间使用 `AND`，例如“导入失败 + ZIP 来源 + 无标签”。
- 更改筛选返回第一页，管理端同时清空本页批量选择。重置清空条件、恢复默认排序，保留当前分页大小。
- 立即应用、重置和组件卸载均取消待执行的关键词防抖请求；共享分页状态阻止旧响应覆盖新结果。
- 管理列表与 `FILTER` 批量选择使用 `ManagementComicListSqlProvider` 的同一份筛选谓词；排除项在 SQL `LIMIT` 之前应用，避免限额探测漏项。
- 阅读端将有效筛选写入 URL，可刷新或分享；无效排序回退默认值。详情返回保留已加载页数与滚动位置。
- 阅读缓存采用版本 `v2` 与长度前缀标签编码，避免带逗号的不同标签组合发生缓存碰撞。

## 验证

管理回归测试使用独立 H2 MySQL 模式执行真实 Mapper SQL，覆盖不同生命周期、完整/部分文件、导入暂存、标签组合及批量快照，不访问用户数据库。阅读回归覆盖范围限制、标签归一化和缓存键隔离。前端分别验证状态模型，并通过浏览器检查桌面管理、桌面阅读与移动阅读的请求和布局。
