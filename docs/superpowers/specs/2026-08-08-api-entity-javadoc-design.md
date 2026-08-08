# api-service 实体类与接口实体分层整理设计

**日期**: 2026-08-08
**状态**: 设计待审阅
**范围**: `com/comicatlas/api` 下 18 个 MyBatis-Plus 实体类（@TableName）的注释补齐、数据库实体与接口对接实体的分层规范化（纯注释/命名变更，零业务逻辑变更）

## 背景与目标

`com/comicatlas/api` 下 18 个实体类（7 个业务域）对照《阿里巴巴 Java 开发手册》存在注释覆盖不足、遗留字段、以及数据库实体（DO）与接口对接实体（DTO/VO）命名分层不清晰的问题。本设计按阿里规范分层模型（DO/DTO/VO）补齐 Javadoc、统一命名、标注过渡遗留字段。

**目标**：
1. 全部实体类级 Javadoc + 字段注释 100% 覆盖；
2. 明确数据库实体（entity 包）与接口实体（dto 包）分层——实体注释标注"数据库实体，禁止直接暴露给接口"；8 个无后缀 dto 类统一补 DTO/VO 后缀；
3. `Comic.category` 过渡遗留字段加说明标注；
4. **不改变任何字段、DB 迁移、业务逻辑**。

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
| 【强制】DO/DTO/VO 分层命名 | entity 与 dto 包已物理分离（合规）；8 个 dto 类无 DTO/VO 后缀 | 统一补后缀；实体类注释标注"数据库实体，禁止直接暴露给接口" |

## 变更设计

### 变更 1：补齐类级 Javadoc（5 个）

为缺失类注释的 5 个实体补类 Javadoc（职责 + 关键约束 + 枚举存储说明 + **"数据库实体，禁止直接暴露给接口，对外使用对应 DTO/VO"** 声明）：
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

### 变更 4：数据库实体与接口对接实体分层规范化

阿里规范分层模型：**DO**（数据对象，对应 DB 表）与 **DTO/VO**（接口传输/展示对象）明确分层。现状 entity 与 dto 已物理分离（`entity/` 包 vs `dto/` 包，7 个域一致）——这是阿里规范认可的包边界分层方式，**不改实体类名**（避免全模块重命名风险）。补齐以下规范：

1. **实体类注释声明分层边界**：18 个实体类注释统一加"数据库实体（DO），禁止直接暴露给接口；对外使用 `dto/` 包对应 DTO/VO"。已有类注释的实体（13 个）追加该声明。
2. **统一 8 个无后缀 dto 类命名**（阿里 POJO 命名规范：DTO/VO 后缀），按用途分类：
   - **DTO**（传输对象）：`ComicDeleteStatsDTO`、`RefreshMetadataResultDTO`、`OperationSubmitResultDTO`、`BatchOperationPayloadDTO`、`MediaItemInfoDTO`
   - **VO**（展示对象）：`ComicTranscodeStatusVO`、`RecoveryProgressVO`、`BatchSelectionVO`
   - 涉及包：`admin/dto`、`comic/dto`、`management/dto`、`management/batch/dto`，同步更新全部引用方（Controller/Service/事件/测试）
3. **Service 返回 entity 的方法核查**：3 处 Service 方法返回 entity（`ManagementTaskService.findByIdempotencyKey`、`findActiveItem`、`UploadSessionService.getBySessionId`）——确认仅供内部/事件/Job 使用（非 Controller 直接返回），加 Javadoc 说明"内部方法，返回数据库实体，禁止用于接口响应"。

## 不做的事（YAGNI）

- 不删除 `Comic.category` 字段、不修改 DB 迁移脚本
- 不改实体类名（不加 DO/Entity 后缀——包分离已满足分层）
- 不改实体字段命名、类型、Lombok 注解
- 不新增/删除实体类
- 不动 Controller/Service/Mapper 的业务逻辑（仅命名与注释）
- 不重构 Service 内部方法返回类型（仅注释声明边界）

## 验证策略

1. **编译门禁**：`.\mvnw -q -pl api-service -am compile -DskipTests` exit 0
2. **覆盖检查**：脚本统计 18 实体类级 Javadoc + 字段注释覆盖率，应 100%；8 个 dto 类重命名后 grep 旧名零残留（`\bComicDeleteStats\b` 等，排除历史归档文档）
3. **残留检查**：`git diff` 确认注释与命名变更（无字段/逻辑修改）
4. **测试**：重命名 dto 涉及引用方，跑全量 compile + 关键测试（`ManagementEventContractTest` 等）

## 提交规划

按业务域拆 5 批：
1. `补充实体注释：comic 域实体 Javadoc（Comic/Catalog/Category/Media/Tag 等，含分层边界声明）`
2. `补充实体注释：management/outbox 域实体 Javadoc（Task/Message 等，含分层边界声明）`
3. `补充实体注释：importer/export/reader/upload 域实体 Javadoc（含分层边界声明）`
4. `统一接口实体命名：dto 无后缀类补 DTO/VO 后缀（8 个 + 引用方更新）`
5. `标注 Comic.category 过渡遗留字段与 Service 内部方法实体边界说明`

每批独立提交 + 编译门禁 + git status 确认只含本批文件。

## 参考范例

- `Comic.java`（已有类 Javadoc + 部分字段注释，作为格式基准）
- `docs/superpowers/specs/2026-08-08-api-controller-javadoc-design.md`（同类注释任务的 spec 格式）
