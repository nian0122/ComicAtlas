# 03 — 用户流程

> 用户怎么完成一个核心任务。每条流程从入口到出口，只记录关键步骤。

---

## 流程 1：首次阅读

```
打开 ComicAtlas
    │
    ▼
Library（漫画列表）
    │ 浏览封面 / 搜索
    ▼
Comic Detail（漫画详情）
    │ 查看信息 / 展开 Catalog
    ▼
Reader（阅读）
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
    ├── Library → 进度标识 → 点击卡片 → Reader（恢复位置）
    │
    └── History → 选择漫画 → Reader（恢复位置）
```

---

## 流程 3：导入漫画

```
打开 ComicAtlas
    │
    ▼
Import（导入页面）
    │ 选择 ZIP 或 DIRECTORY
    │ 输入文件路径
    │ 点击"开始导入"
    ▼
Task Center（任务中心）
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
Library（漫画列表）
    │ 输入关键词
    │ 300ms 自动搜索
    ▼
结果列表（实时更新）
    │ 点击结果
    ▼
Comic Detail → Reader
```

---

## 流程 5：生成 LQ

```
Comic Detail
    │ 点击"生成LQ"
    ▼
任务提交 → Task Center 可查看进度
    │ 完成后 Status → READY
    ▼
Reader 中自动使用 LQ（如果开启）
```

---

## 流程 6：删除漫画

```
Comic Detail
    │ 点击"删除漫画" → 确认
    ▼
返回 Library（漫画已移除）
```

---

## 页面跳转总览

```
                    ┌──────────┐
                    │ Library  │ ←──────┐
                    └────┬─────┘        │
                         │              │
                    ┌────▼──────┐    ┌──┴──────┐
                    │Comic Detail│    │ History │
                    └────┬──────┘    └──┬──────┘
                         │              │
                    ┌────▼───┐          │
                    │ Reader │←─────────┘
                    └────────┘

┌──────────┐    ┌───────────┐
│  Import  │───→│Task Center│───→ Library
└──────────┘    └───────────┘
```

核心用户路径只有两条：
- **Library → Detail → Reader**（探索 + 阅读）
- **History → Reader**（快速恢复）
