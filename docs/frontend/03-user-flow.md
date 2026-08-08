# 03 — 用户流程

**更新日期：** 2026-08-08
**状态：** 与 v1.0 路由结构同步
**维护者：** ComicAtlas 前端组

> 用户怎么完成一个核心任务。每条流程从入口到出口，只记录关键步骤。

---

## 流程 1：首次阅读

```
打开 ComicAtlas
    │
    ▼
Home（首页）→ Library（漫画库 /library）
    │ 浏览封面 / 搜索
    ▼
Comic Detail（漫画详情 /comic/:id）
    │ 查看信息 / 展开 Catalog
    ▼
Reader（阅读器 /reader/:chapterId）
    │ 翻页阅读
    │ 自动记录进度
    │ 读完 → 下一章
    ▼
返回 Library 或 继续阅读
```

---

## 流程 2：继续阅读

```
打开 ComicAtlas
    │
    ├── Home → 最近阅读 → 点击卡片 → Reader（恢复位置）
    │
    └── History（/history）→ 选择漫画 → Reader（恢复位置）
```

---

## 流程 3：导入漫画

```
打开 ComicAtlas
    │
    ▼
管理端 Import（/manage/import）
    │ 选择来源 ZIP / REGISTER / EHENTAI
    │ 输入文件路径（EHENTAI 输入作品链接；REGISTER 输入本地目录）
    │ 点击"开始导入"（支持批量）
    ▼
任务中心（/manage/import/tasks）
    │ 看到进度条变化
    │ 等待完成 → SUCCESS
    │ 失败 → 查看错误 → 重试
    ▼
Library → 新漫画出现在列表中
    │ 点击进入 Comic Detail
    ▼
Reader
```

---

## 流程 4：搜索漫画

```
Library（漫画库 /library）
    │ 输入关键词（keyword）
    │ 自动搜索（防抖）
    ▼
结果列表（实时更新）
    │ 点击结果
    ▼
Comic Detail → Reader
```

---

## 流程 5：生成 LQ

```
Comic Detail（/comic/:id）或 管理端存储管理（/manage/storage）
    │ 点击"生成 LQ"
    ▼
任务提交（异步）→ 任务中心 / 存储管理可查看进度
    │ 完成后 lq_status → READY
    ▼
Reader 中自动使用 LQ（如果开启）
```

---

## 流程 6：删除漫画

```
Comic Detail 或 管理端漫画工作区
    │ 点击"删除" → 确认
    ▼
进入回收站流程（软删除，7 天保留期）
    │ 永久清理需在回收站经过保留期并二次确认
    ▼
返回 Library（漫画已从列表移除）
```

---

## 页面跳转总览

```
                    ┌──────────┐
                    │   Home   │ ← 首页
                    └────┬─────┘
                         │
            ┌────────────┼────────────┐
            ▼            ▼            ▼
     ┌──────────┐  ┌──────────┐  ┌──────────┐
     │ Library  │  │ History  │  │Comic Detail│
     └────┬─────┘  └────┬─────┘  └────┬─────┘
          │             │             │
          └─────────────┼─────────────┘
                        ▼
                 ┌──────────┐
                 │  Reader  │
                 └──────────┘

阅读端管理：Import（/manage/import）→ Tasks（/manage/import/tasks）→ Library
```

核心用户路径只有两条：
- **Home/Library → Detail → Reader**（探索 + 阅读）
- **History → Reader**（快速恢复）

导入路径：**/manage/import → /manage/import/tasks**（管理端任务中心）。
