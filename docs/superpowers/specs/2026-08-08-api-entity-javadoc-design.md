# api-service 实体类按阿里开发规范整理设计

**日期**: 2026-08-08
**状态**: 设计待审阅
**范围**: `com/comicatlas/api` 下 18 个 MyBatis-Plus 实体类（@TableName）的注释补齐与遗留字段标注（纯注释变更，零业务逻辑变更）

## 背景与目标

`com/comicatlas/api` 下 18 个实体类（7 个业务域）对照《阿里巴巴 Java 开发手册》存在注释覆盖不足与遗留字段问题。本设计补齐类级/字段级 Javadoc，并标注过渡遗留字段。

**目标**：全部实体类级 Javadoc + 字段注释 100% 覆盖；`Comic.category` 过渡遗留字段加说明标注；**不改变任何字段、DB 迁移、业务逻辑**。

## 现状分析（18 个实体，7 域）

| 业务域 | 实体 | 类 Javadoc | 字段注释 |
|--------|------|-----------|----------|
| comic | Catalog、Category、Chapter、Comic、ComicTag、Media、Tag | 缺 4（Catalog/Category/ComicTag/Tag） | 严重不足 |
| export | ExportTask | 有 | 10/11 |
| importer | DirectoryScanTask、ImportTask、RecoveryTask | 有 | 20/21、9/10、13/14 |
| management | ManagementTask、ManagementTaskItem | 有 | 7/23、6/17 |
| outbox | InboxReceipt、OutboxMessage | 有 | 2/7、2/15 |
| reader | ReadingHistory | 缺 1 | 6/6 全缺 |
| upload | UploadFile、UploadSession | 有 | 13/13、11/11 全缺 |

**关键数据**：类级 Javadoc 缺 5/18；字段注释 55/222（仅 25%）。

## 阿里规范对照

| 规范条款 | 现状 | 处理 |
|----------|------|------|
| 【强制】类注释必须使用 Javadoc 规范 | 5 个实体缺类注释 | 补齐 |
| 【推荐】字段必须有注释说明用途 | 167 个字段无注释 | 全补齐 |
| 【推荐】POJO 类名与字段命名规范 | 命名基本合规（无 is 前缀、无基本类型、无 *Entity 混杂） | 无需处理 |
| 【参考】历史遗留字段说明 | `Comic.category` 为 V3 迁移后的过渡遗留 | 加 Javadoc 标注 |

## 变更设计

### 变更 1：补齐类级 Javadoc（5 个）

为缺失类注释的 5 个实体补类 Javadoc（职责 + 关键约束 + 枚举存储说明）：
- `Catalog`：漫画目录树节点（多级树结构，parentId 关联）
- `Category`：漫画分类（名称唯一，sortOrder 排序）
- `ComicTag`：漫画-标签关联表（comicId + tagId 联合唯一）
- `Tag`：漫画标签（名称唯一，关联计数）
- `ReadingHistory`：阅读历史（comicId 唯一，记录最近进度）

### 变更 2：补齐字段注释（167 个）

为全部未注释字段补单行 Javadoc（`/** 说明 */`），遵循：
- 解释字段业务语义与约束（如枚举存储方式、单位、可选性）
- 已有字段注释保留（55 个），风格统一
- 参照现有规范注释（如 ManagementTask 的 taskType/status 注释风格）

字段语义来源：DB 迁移脚本（`db/migration/V*.sql`）、Mapper 使用、Controller/Service 消费方。

### 变更 3：Comic.category 过渡遗留标注

`Comic.category` 为 V3 迁移（已归档 `migration-archive/V3__add_category.sql`）引入 `category_id` 后的**过渡遗留字段**：V3 将旧字符串映射到 category_id 但保留该列，当前仅 `MetadataExporter`（L94）用于导出历史数据。

处理：字段加 Javadoc 说明——
```java
/** 分类名（V3 迁移前的过渡遗留列，已由 {@link #categoryId} 取代；保留供 MetadataExporter 导出历史数据） */
private String category;
```
**不删除**该字段（避免动 DB 迁移与导出逻辑）。

## 不做的事（YAGNI）

- 不删除 `Comic.category` 字段、不修改 DB 迁移脚本
- 不改实体字段命名、类型、Lombok 注解
- 不新增/删除实体类
- 不动 Service/Controller/Mapper/DTO

## 验证策略

1. **编译门禁**：`.\mvnw -q -pl api-service -am compile -DskipTests` exit 0
2. **覆盖检查**：脚本统计 18 实体类级 Javadoc + 字段注释覆盖率，应 100%
3. **残留检查**：`git diff` 确认仅注释变更（无字段/逻辑修改）
4. **测试**：注释变更无逻辑影响，跑全量 compile + 关键测试

## 提交规划

按业务域拆 4 批（参照既有注释任务的批次粒度）：
1. `补充实体注释：comic 域实体 Javadoc（Comic/Catalog/Category/Media/Tag 等）`
2. `补充实体注释：management/outbox 域实体 Javadoc（Task/Message 等）`
3. `补充实体注释：importer/export/reader/upload 域实体 Javadoc`
4. `标注 Comic.category 过渡遗留字段说明`

## 参考范例

- `Comic.java`（已有类 Javadoc + 部分字段注释，作为格式基准）
- `docs/superpowers/specs/2026-08-08-api-controller-javadoc-design.md`（同类注释任务的 spec 格式）
