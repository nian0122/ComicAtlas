# 02 — 信息架构

> 定义整个系统有哪些模块、模块之间是什么关系、每个模块负责什么。

---

## 顶层模块

```
ComicAtlas
├── Library        # 漫画库：浏览、搜索、筛选
├── Comic Detail   # 漫画详情：信息、目录、操作
├── Reader         # 阅读器：翻页、进度、导航
├── Import         # 导入：创建任务
├── Task Center    # 任务中心：进度监控
├── History        # 阅读记录：继续阅读入口
├── Dashboard      # 仪表盘：统计概览
└── Operations     # 操作日志：运维查询
```

---

## 模块详情

### Library（漫画库）

**职责**：展示所有漫画，提供搜索和筛选入口。

```
Library
├── 搜索栏       keyword → 即时搜索（300ms 防抖）
├── 标签筛选     tag → 精确筛选
├── 排序         createdAt / updatedAt / title / pageCount / lastReadTime
├── 漫画卡片列表  cover + title + author + 阅读进度
└── 分页
```

**入口**：`/` 重定向到 `/comics`

**去向**：点击卡片 → Comic Detail

---

### Comic Detail（漫画详情）

**职责**：展示单本漫画的完整信息 + 目录结构 + 操作入口。

```
Comic Detail
├── 封面
├── 基本信息     title / titleJpn / author / sourceType / pageCount
├── 标签
├── 阅读进度
├── 操作按钮     继续阅读 / 从头开始 / 生成LQ / 删除
├── Catalog 树   Vol.1 → 第1话、第2话
└── 章节入口     点击章节 → Reader
```

**入口**：Library 点击 → `/comics/{id}`

**去向**：点击章节 → Reader；返回 → Library

---

### Reader（阅读器）

**职责**：阅读漫画的核心页面。全屏优先，最小 UI。

```
Reader
├── 工具栏       返回 / 标题 / 页码 / 上一章 / 下一章 / 设置
├── 图片区域     HQ 或 LQ，滚动查看
├── 章节导航     prevChapterId / nextChapterId
└── 进度同步     自动记录阅读位置
```

**入口**：Comic Detail 点击章节 → `/comics/{id}/read?chapterId=`

**去向**：返回 → Comic Detail；读完 → 自动跳下一章

---

### Import（导入）

**职责**：创建导入任务。

```
Import
├── 来源选择     ZIP / DIRECTORY
├── 路径输入     文件路径或目录路径
└── 开始按钮
```

**入口**：Task Center "新建导入" → `/import`

**去向**：提交后 → Task Center 查看进度

---

### Task Center（任务中心）

**职责**：监控所有任务的状态。

```
Task Center
├── 进行中       进度条 + 状态标签 + 取消按钮
├── 失败         错误信息 + 重试次数 + 重试按钮
└── 已完成       耗时信息
```

**入口**：导航 → `/tasks`

**去向**：新建导入 → Import；完成 → Library 查看结果

---

### History（阅读记录）

**职责**：展示最近阅读的漫画，快速恢复阅读。

```
History
├── 记录列表     comic + chapter + 进度 + 最后阅读时间
└── 点击         → Reader（恢复到上次位置）
```

**入口**：导航 → `/history`

**去向**：点击 → Reader

---

### Dashboard（仪表盘）

**职责**：统计数据概览。

```
Dashboard
├── 漫画数
├── 总页数
├── 标签数
├── 存储占用
└── 今日导入
```

**入口**：导航 → `/dashboard`

---

### Operations（操作日志）

**职责**：运维查询，非核心用户功能。

```
Operations
├── 筛选        module / action / businessId
├── 日志列表     traceId + 操作 + 详情 + 时间
└── 分页
```

**入口**：导航 → `/operations`

---

## 模块关系

```
                        ┌─────────┐
                        │ Library │ ← 首页
                        └────┬────┘
                             │ 点击漫画
                             ▼
                      ┌─────────────┐
                      │ Comic Detail │
                      └──────┬───────┘
                             │ 点击章节 / 继续阅读
                             ▼
                         ┌────────┐
                    ┌───→│ Reader │
                    │    └────────┘
                    │
┌──────────┐   ┌────┴─────┐
│  Import  │──→│Task Center│
└──────────┘   └──────────┘

┌─────────┐       ┌──────────┐
│ History │──────→│  Reader  │（恢复到上次位置）
└─────────┘       └──────────┘
```

- **Library → Comic Detail → Reader** 是主流程
- **Import → Task Center** 是辅助流程
- **History → Reader** 是快捷入口
- **Dashboard / Operations** 是独立工具页
