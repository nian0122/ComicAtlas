# ComicAtlas 0.2 领域模型设计

**版本**: 0.2  
**日期**: 2026-07-16  
**状态**: Canonical

---

## 核心实体

```
Comic
├── Category（一级分类，单选）
├── Tags（内容标签，多选）
├── Catalog（目录树，可选）
├── Chapters
│   └── Pages
└── ReadingHistory
```

| 实体 | 基数 | 作用 |
|------|------|------|
| Category | 1 个 | 作品类型：漫画、本子、CG、画集、小说 |
| Tag | 0~N 个 | 内容特征：百合、校园、TS、悬疑 |
| Catalog | 0~N 个 | 漫画内部组织结构：卷 / 部 / 章节 |
| ReadingHistory | 0~1 条 | 当前阅读进度 |

---

## Category 设计

### 原则

> **Category 永远保持一级结构，不支持树形分类。**

### 数据模型

```sql
CREATE TABLE category (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(64) NOT NULL,
    sort_order  INT DEFAULT 0,
    color       VARCHAR(16) DEFAULT NULL,
    icon        VARCHAR(64) DEFAULT NULL,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_category_name (name)
);
```

### 关键约束

- **无 `parent_id`**：不支持嵌套。
- **无 `path` / `level`**：不是树。
- **Comic 单选绑定**：`comic.category_id`。
- **数量受限**：建议保持少量（默认 5 个，最多不超过 20 个）。

### 默认分类

| id | name |
|----|------|
| 1 | 漫画 |
| 2 | 本子 |
| 3 | CG |
| 4 | 画集 |
| 5 | 小说 |

### 与旧字段的关系

- 现有 `comic.category`（VARCHAR）字段废弃。
- 迁移时把旧值映射到新的 `category_id`。
- 无法映射的值归入"漫画"或"未分类"。

---

## Tag 设计

### 数据模型

```sql
CREATE TABLE tag (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(64) NOT NULL,
    color       VARCHAR(16) DEFAULT NULL,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_tag_name (name)
);

CREATE TABLE comic_tag (
    comic_id    BIGINT NOT NULL,
    tag_id      BIGINT NOT NULL,
    PRIMARY KEY (comic_id, tag_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE
);
```

### 关键约束

- Tag 是独立实体，不通过 `type` 字段承载 Category 语义。
- Comic 与 Tag 多对多绑定。
- Tag 数量开放，允许自由新增/删除。

---

## Category vs Tag

| 维度 | Category | Tag |
|------|----------|-----|
| 问题 | 这是什么类型？ | 它有什么特征？ |
| 示例 | 漫画、CG、画集 | 百合、校园、TS |
| 选择方式 | 单选 | 多选 |
| 数量 | 少，受限 | 多，开放 |
| 结构 | 一级，无树 | 扁平 |
| 生命周期 | 稳定 | 频繁变化 |
| UI | Radio | Multi-select Chip |

---

## 其他领域实体

### Comic

聚合根。新增 `category_id` 字段，废弃旧的 `category` 字符串字段。

### Catalog

目录树，负责组织 Chapter。不影响阅读顺序，阅读顺序由 `chapter.global_order` 决定。

### Chapter

可阅读单元。`global_order` 决定全书线性阅读顺序。

### Page

单页图片。通过 `hq_root` + `hq_path` 引用文件。

### ReadingHistory

阅读记录：`(comic_id, chapter_id, page_number, updated_at)`。

---

## 数据迁移

### 第一阶段（0.2 内完成）

1. 创建 `category` 表。
2. 插入默认分类。
3. 迁移 `comic.category` → `comic.category_id`。
4. 废弃 `comic.category` 字段（或保留为备注，业务层不再使用）。

### 第二阶段（后续）

- 批量把无法映射的旧 category 值修正。
- 如果用户需要，支持自定义 Category 名称/颜色/图标。
