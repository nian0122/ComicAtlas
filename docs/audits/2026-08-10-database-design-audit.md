# ComicAtlas 数据库设计审核报告

**审核日期：** 2026-08-10  
**审核范围：** `api-service` 全部 18 个 MyBatis-Plus 持久化实体、生效 Flyway V1-V18、Mapper SQL、Service 写入链路、测试 schema  
**证据优先级：** 运行中 MySQL 结构与存量数据 → 实体字段与注解 → Mapper/Service 实际读写 → 生效 Flyway 目标结构；项目文档只用于识别描述偏差，不作为数据库设计事实源  
**结论：** **不批准直接按现状长期演进；核心模型可保留，但必须先修复 P1 数据完整性问题。**

## 1. 执行摘要

当前设计的主方向是合理的：

- API 是唯一数据库写入方，Worker 只读数据库。
- `comic → catalog/chapter → page` 支持目录树、全书顺序和 IMAGE/VIDEO 混排。
- `page.file_size` 表示当前 HQ 媒体文件字节数，图片和视频共用。
- `page.lq_size` 表示图片 LQ 产物字节数，视频不生成 LQ。
- `global_order` 已具备 `(comic_id, global_order)` 唯一约束。
- 管理任务、Outbox/Inbox 和 Flyway 的总体分层正确。

但当前 schema 仍存在会破坏可靠消息、跨漫画归属和统计可信度的缺口。审核共确认：

| 严重度 | 数量 | 结论 |
|---|---:|---|
| P0 | 0 | 未发现必然导致数据库立即不可用的问题 |
| P1 | 5 | 合入后续数据库功能前必须修复 |
| P2 | 7 | 建议在同一数据库治理版本处理 |
| P3 | 4 | 命名、文档与长期维护问题 |

## 2. 审核方法与验证

### 2.1 静态核对

- 枚举并逐个读取全部 18 个持久化实体，而不是从架构文档反推表结构。
- 核对每个实体的 `@TableName`、`@TableId`、`@Version`、显式 `@TableField` 和全部 Java 字段。
- 读取全部 11 个生效迁移：V1、V2、V10-V18，并将实体字段映射到迁移后的最终列。
- 核对全部 `BaseMapper`、`MediaMapper.xml`、`StorageMapper.xml` 和注解 SQL。
- 追踪 `file_size/hq_size/lq_size` 在导入、LQ、转码、刷新、删除、恢复和统计中的读写。
- 比较 Flyway、`db/schema.sql`、`src/test/resources/sql/init-test-db.sql`。

本报告不使用 `docs/` 中的字段说明证明任何结论。主要代码证据位于：

- `api-service/src/main/java/com/comicatlas/api/**/entity/*.java`
- `api-service/src/main/java/com/comicatlas/api/**/mapper/*Mapper.java`
- `api-service/src/main/resources/mapper/*.xml`
- `api-service/src/main/java/com/comicatlas/api/**/service/**/*.java`
- `api-service/src/main/java/com/comicatlas/api/**/event/*.java`
- `api-service/src/main/resources/db/flyway/*.sql`

### 2.2 真实 MySQL 验证

执行：

```powershell
./mvnw.cmd -pl api-service -am '-Dtest=DatabaseMigrationTest,EntitySchemaContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：**10 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**。

该结果证明 V1-V18 可以在 MySQL 8 从空库迁移到 v18，并且 Spring/MyBatis 可以加载现有映射；它没有证明全部实体字段都完成 CRUD，更没有证明主键、跨漫画归属、冗余统计和测试 schema 一致性正确。`EntitySchemaContractTest` 实际只枚举 11 个核心实体，主要验证类加载和上下文启动，没有覆盖 Outbox、Inbox、ManagementTask、UploadSession 等实体的逐字段 CRUD，也未覆盖本报告的关键负向约束。

测试过程中还出现 `commons-compress` 与 `commons-lang3` 的 `NoSuchMethodError` 后台异常；容器最终仍启动并通过测试。它不是本次数据库设计结论的一部分，但应在依赖治理中单独处理。

### 2.3 运行中数据库只读审计

通过正在运行的 `comicatlas-api` 容器现有数据库配置连接 MySQL，仅执行 `SELECT`、`information_schema` 和 `SHOW` 类只读查询。未输出密码，未执行 DDL/DML。

运行环境：

```text
database       = comic_atlas
MySQL          = 8.0.27
current user   = comicatlas@%
Flyway current = V17
source target  = V18
```

真实行数（查询时点）：

| 表 | 行数 |
|---|---:|
| `comic` | 95 |
| `catalog` | 67 |
| `chapter` | 589 |
| `page` | 90,055 |
| `import_task` | 101 |
| `management_task` | 22 |
| `outbox_message` | 149 |

真实数据库不是当前 Flyway 从空库创建后的理想结构。该库通过 `<< Flyway Baseline >>` 记为 V1，随后应用 V2、V10-V17，因此旧库遗留列不会被当前 V1 自动删除。实体与真实列的自动比对结果为：

| 实体/表 | 实体未映射的真实列 |
|---|---|
| `Comic/comic` | `cover_path`, `lq_status`, `root_key`, `relative_path` |
| `Catalog/catalog` | `path`, `level` |
| `Media/page` | `hq_size` |
| `ImportTask/import_task` | `current_page`, `downloaded_bytes` |
| 无实体 | `operation_log` 整表 |

实体字段反向核对没有发现“实体字段在真实表中不存在”的情况。也就是说，当前应用能运行，但真实库包含 9 个 ORM 不管理的遗留列和 1 张无实体表。

存量数据核对结果：

- IMAGE 87,410 行，VIDEO 2,645 行，证明图片和视频确实共用 `page`。
- `page.hq_size` 只有 41 行非零，且 41 行全部等于同一行的 `file_size`；没有 VIDEO 行使用它。
- `page.file_size` 有 76,345 行非零，`page.lq_size` 有 27,120 行非零，二者均为活跃字段。
- `comic.file_size/hq_size/lq_size` 与明细聚合分别存在 2/3/3 本不一致，其中 READY 漫画分别为 2/2/3 本。
- Outbox 当前没有重复 `event_id`，但表结构仍没有主键或唯一约束。
- 发现 4 条阅读历史跨漫画引用，记录 ID 为 22、25、52、55；这已经不是理论风险。
- catalog 父子跨漫画、chapter/catalog 跨漫画、悬空分类、分类镜像不一致当前均为 0。
- 根目录重名、根章节编号重复、NULL 类型标签重名当前均为 0，但对应唯一约束仍不能防止未来并发写入。
- 没有负数大小/尺寸/时长、非法媒体类型、图片转码状态冲突或视频 LQ 冲突。
- 由于 V18 尚未执行，当前有 490 条 VIDEO 仍为 `NOT_NEEDED`，但按代码中的兼容矩阵应重分类为 `REQUIRED`。

## 3. 表级设计结论

| 表 | 结论 | 说明 |
|---|---|---|
| `comic` | 需整改 | 三个 size 汇总字段无统一口径；`category` 与 `category_id` 双写；`cover_path` 已无生产代码使用 |
| `catalog` | 需整改 | 支持多级树，但数据库未保证 parent 属于同一 comic；根级唯一约束受 NULL 语义影响 |
| `chapter` | 需整改 | `global_order` 设计正确；catalog 归属未由 DB 保证；根章节唯一约束受 NULL 影响 |
| `page` | 核心合理但需清遗留列 | 实体虽叫 Media、物理表仍叫 page；IMAGE/VIDEO 共表合理；真实库额外存在实体未映射的 `hq_size` |
| `tag` | 需小修 | `(name,type)` 在 type 为 NULL 时允许重复 |
| `comic_tag` | 合理 | 复合主键和级联删除正确；MyBatis-Plus 无单一 `@TableId` 警告属于复合键限制 |
| `category` | 需整改 | 自身结构合理，但 `comic.category_id` 没有外键 |
| `import_task` | 可保留 | 与统一管理任务构成扩展表，但一对一关系未约束；存在仅展示不更新的进度字段 |
| `recovery_task` | 需整改 | V10 将旧值迁移到 QUEUED，但列默认值仍是枚举不存在的 PENDING |
| `directory_scan_task` | 可保留 | 独立结果载体合理；management 关联未约束 |
| `export_task` | 可保留 | 任务产物载体合理；是否允许漫画删除后保留任务需明确为有意的弱引用 |
| `reading_history` | 需整改 | 两个外键分别合法，但 DB 不保证 chapter 属于 comic |
| `management_task` | 合理 | 聚合任务、幂等键和乐观锁方向正确 |
| `management_task_item` | 合理但需约束 | 多态 target 无法普通 FK；lock_key 唯一策略合理 |
| `outbox_message` | 严重缺陷 | `event_id` 被实体和注释当主键，但 Flyway 没有 PRIMARY KEY/UNIQUE |
| `inbox_receipt` | 合理 | `event_id` 主键正确支持消费幂等 |
| `upload_session` | 需整改 | comic/chapter/replace_media 缺外键和同漫画归属约束 |
| `upload_file` | 需整改 | session FK 正确；media_id 只有索引，没有 FK/删除策略 |

### 3.1 实体逐项核对结果

以下不是文档推断，而是从实体源码读取出的实际映射：

| 实体 | `@TableName` | 实体字段数 | 关键核对结论 |
|---|---|---:|---|
| `Comic` | `comic` | 22 | 实体确有 `fileSize/hqSize/lqSize`；真实表另有 4 个未映射遗留列 |
| `Catalog` | `catalog` | 6 | `comicId/parentId` 同时存在；真实表另有未映射的 `path/level` |
| `Chapter` | `chapter` | 12 | `comicId/catalogId/globalOrder/status/version` 均实际使用 |
| `Media` | `page` | 24 | 明确映射 `page`；同时包含 IMAGE/VIDEO 通用字段及视频专属元数据；真实表另有未映射 `hq_size` |
| `Tag` | `tag` | 3 | `type` 可空，与 MySQL NULL 唯一语义存在冲突 |
| `ComicTag` | `comic_tag` | 2 | 实体没有单一 `@TableId`，与数据库复合主键一致；当前通过条件 Wrapper 操作，可接受 |
| `Category` | `category` | 5 | 实体注释称被 `comic.categoryId` 引用，但数据库没有对应 FK |
| `ImportTask` | `import_task` | 21 | 真实表另有未映射的 `current_page/downloaded_bytes`，其余进度字段也存在低使用率 |
| `RecoveryTask` | `recovery_task` | 14 | Java 状态枚举不含数据库默认值 `PENDING` |
| `DirectoryScanTask` | `directory_scan_task` | 10 | 与统一任务是一对一语义，但实体/数据库均不强制唯一关联 |
| `ExportTask` | `export_task` | 11 | 同样未强制 `managementTaskId` 一对一 |
| `ReadingHistory` | `reading_history` | 6 | 同时保存 `comicId/chapterId`；当前 Service 写入前也没有验证二者归属 |
| `ManagementTask` | `management_task` | 23 | `@Version`、幂等键和聚合计数均有映射，模型完整 |
| `ManagementTaskItem` | `management_task_item` | 17 | 多态目标使用 `targetType/targetId`，无法用普通 FK，必须由服务与任务锁约束 |
| `OutboxMessage` | `outbox_message` | 15 | `eventId` 标注 `@TableId(INPUT)`，但生效迁移没有主键或唯一键 |
| `InboxReceipt` | `inbox_receipt` | 7 | `eventId` 的实体主键与数据库主键一致 |
| `UploadSession` | `upload_session` | 11 | `comicId/chapterId/replaceMediaId` 均为真实字段，Service 有归属校验但数据库无 FK |
| `UploadFile` | `upload_file` | 13 | `sessionId` 有 FK；`mediaId` 只有普通索引 |

### 3.2 `Media` 实体与 `page` 表专项结论

`Media.java` 的实际字段确认如下；注意最后一行的真实库遗留列不属于实体：

```text
通用身份：id, chapterId, pageNumber, originalPageNumber
HQ：hqRoot, hqPath, hqStatus, fileSize
LQ：lqRoot, lqPath, lqStatus, lqSize
通用元数据：width, height, mediaType
视频元数据：duration, container, videoCodec, audioCodec, transcodeStatus
生命周期：status, trashedAt, version, createdAt
真实库遗留：hq_size（Media 实体无该字段）
```

因此：

- 运行库的 `page` 表存在 `hq_size`，但 `Media` 实体、导入批量 Mapper 和当前 Service 均不读写它。它是旧库 Baseline 后遗留的冗余列。
- 真实数据中 `hq_size` 仅 41 行非零，且全部等于 `file_size`，因此应在迁移预检和备份后删除，而不是继续纳入实体。
- `page.file_size` 不是死字段。导入批量插入、上传、恢复、转码完成、媒体刷新、metadata 导出和存储统计都在使用。
- `page.lq_size` 不是死字段。LQ 完成事件写入该字段，存储统计按 `lq_status=READY` 聚合该字段。
- IMAGE 和 VIDEO 共用 `Media/page` 是合理的单表继承模型：二者共享章节、顺序、HQ、尺寸、生命周期和回收逻辑；视频专属列对 IMAGE 允许为空。
- 当前导入已经通过 `MediaMapper.insertImportBatch` 使用 MySQL 多值 INSERT，不再是每页一次 `mediaMapper.insert`。数据库审核不应再以旧实现判断现行导入性能。
- 问题不在 `page.file_size/lq_size`；问题在 `page.hq_size` 遗留冗余以及 `comic.file_size/hq_size/lq_size` 的汇总语义不一致。

## 4. P1 必须修复

### DB-P1-01：Outbox 事件 ID 没有主键

**证据：**

- `V13__create_outbox_inbox.sql` 把 `event_id` 注释为“事件 UUID（PK）”，但只声明 `NOT NULL`。
- `OutboxMessage.eventId` 使用 `@TableId(type = IdType.INPUT)`。
- Outbox 的更新、重试和确认全部用 `WHERE event_id = ?`。

**影响：**

- 同一事件被重复 enqueue 时数据库允许多行相同 `event_id`。
- confirm、重试和失败更新可能一次修改多行。
- Outbox 不能提供事件级唯一性，和 Inbox 主键幂等模型不对称。
- 可靠消息的正确性依赖 UUID“通常不重复”，而不是数据库约束。

**必须调整：**

1. 迁移前查询重复值：

   ```sql
   SELECT event_id, COUNT(*)
   FROM outbox_message
   GROUP BY event_id
   HAVING COUNT(*) > 1;
   ```

2. 若存在重复，按业务事件 payload、状态和时间人工/脚本化归并，禁止直接任意删除。
3. 新增 Flyway 迁移：

   ```sql
   ALTER TABLE outbox_message ADD PRIMARY KEY (event_id);
   ```

4. 增加重复 enqueue、重试和并发 relay 测试。

### DB-P1-02：漫画存储大小存在三个不可靠汇总字段

**现行字段：**

- 明细事实：`page.file_size`、`page.lq_size`。
- 漫画汇总：`comic.file_size`、`comic.hq_size`、`comic.lq_size`。

**核对结果：**

- `page.file_size` 被导入、转码、上传、媒体刷新、恢复、导出和统计使用，必须保留。
- `page.lq_size` 在 LQ 完成结果中写入，并被存储统计使用，必须保留。
- `comic.hq_size` 在多个服务中重算，但过滤条件并不一致。
- `comic.file_size` 导入/恢复时通常与 HQ 大小相同；HQ 删除后通常不更新；媒体刷新又把它覆盖成当前 HQ 大小，语义冲突。
- 未发现正常生产链持续更新 `comic.lq_size`；它会长期为默认值或历史值。
- `StorageMapper` 的部分 HQ 聚合只统计 IMAGE，另一些聚合统计 IMAGE+VIDEO，视频空间会在不同页面得到不同结果。

**统一语义：**

```text
page.file_size = 当前 HQ 媒体文件字节数，适用于 IMAGE 和 VIDEO
page.lq_size   = 当前 LQ 图片文件字节数，仅适用于 IMAGE

HQ bytes = 生命周期有效 AND hq_status=READY 的 IMAGE+VIDEO file_size 之和
LQ bytes = 生命周期有效 AND media_type=IMAGE AND lq_status=READY 的 lq_size 之和
```

**推荐调整：**

- 保留 `page.file_size` 与 `page.lq_size`。
- 业务查询改为统一 SQL 聚合，并使用 Redis 缓存结果。
- 分阶段停止读取/写入 `comic.file_size/hq_size/lq_size`，完成一致性比对后再删除三个列。
- 如果未来实测聚合成为瓶颈，再建立独立 `comic_storage_stats` 表；该表必须只有一个更新服务并支持全量重算，不能继续散落在多个事件处理器中。
- 如果确实需要“导入源文件大小”，新增语义明确且不可变的 `source_size_bytes`，不要复用 `file_size`。

### DB-P1-03：数据库允许跨漫画目录、章节和阅读历史关联

**当前可被数据库接受的非法数据：**

- catalog A 的 `parent_id` 指向另一漫画的 catalog。
- chapter 的 `comic_id` 属于漫画 A，但 `catalog_id` 属于漫画 B。
- reading_history 的 `comic_id` 为 A，`chapter_id` 却属于 B。
- upload_session 的 comic、chapter、replace media 可互不归属。

应用服务部分入口有归属校验，但数据库无法抵御并发、遗漏入口、恢复脚本和未来代码缺陷。

运行库已经存在 4 条非法阅读历史：`reading_history.id` 为 22、25、52、55 的 `comic_id` 与其 `chapter_id` 实际所属漫画不一致。`HistoryServiceImpl.upsertHistory` 直接保存请求中的 `chapterId`，写入前没有查询章节并校验所属漫画，因此这里同时存在应用校验缺口和数据库约束缺口。

**推荐约束：**

- 为 `catalog` 建立可被复合外键引用的 `(id, comic_id)` 唯一键。
- catalog parent 使用 `(parent_id, comic_id) → catalog(id, comic_id)`。
- chapter catalog 使用 `(catalog_id, comic_id) → catalog(id, comic_id)`。
- chapter 建立 `(id, comic_id)` 唯一键，reading_history 使用 `(chapter_id, comic_id) → chapter(id, comic_id)`。
- upload_session 至少增加 comic、chapter、replace_media 外键；是否级联删除必须按“上传会话是否保留审计记录”明确决定。

迁移前必须先执行异常数据查询并修复，不能直接添加外键导致生产迁移中断。

### DB-P1-04：Flyway、schema.sql、测试初始化 SQL 多源漂移

**证据：**

- Flyway 应是目标结构的唯一事实源，但仓库同时人工维护 `db/schema.sql` 和 `src/test/resources/sql/init-test-db.sql`。
- `schema.sql` 缺少 V12 的多个 `management_task_id`、V1/V2 的 `batch_id/category` 等现行列。
- `schema.sql` 中多个状态列仍为 VARCHAR(16)，Flyway V2 已放宽为 VARCHAR(32)。
- 三份定义都遗漏了 Outbox 主键，因此现有测试无法发现 DB-P1-01。
- 多个 IT 禁用 Flyway，实际验证的是手写测试 schema，不是生产 schema。
- 真实库包含实体与当前 V1 均不再声明的 `comic.root_key/relative_path/lq_status`、`catalog.path/level`、`page.hq_size` 和 `operation_log`，证明旧库 baseline 后缺少显式清理迁移。

**推荐调整：**

- MySQL 测试全部通过 Flyway 建库，禁止复制生产 schema。
- H2 确实需要时，将文件明确命名为 `schema-h2.sql`，仅承担 H2 兼容测试，不宣称等价生产 schema。
- `init-test-db.sql` 只保留测试数据和账号授权，不再重复建全部业务表。
- `DatabaseMigrationTest` 增加主键、外键、唯一约束、索引、默认值和列类型断言。

### DB-P1-05：运行库停留在 V17，当前 V18 数据迁移尚未执行

真实 `flyway_schema_history` 的最高成功版本是 V17，而源码已包含 `V18__classify_video_transcode_status.sql`。

只读复算 V18 条件后确认：当前有 **490 条 VIDEO** 的 `transcode_status` 仍为 `NOT_NEEDED`，但按 `VideoCompatibilityPolicy` 的兼容矩阵应为 `REQUIRED`。这会让这些视频无法进入“手动转码不标准视频”的正常选择链路。

这不是修改 V18 文件可以解决的问题。应先确认当前运行 API 镜像是否包含 V18，再按标准部署流程让 Flyway 执行；禁止手工复制 V18 SQL 绕过 `flyway_schema_history`。执行后必须验证：

```sql
SELECT version, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

并复算不兼容视频数量，确认需要转码的历史 VIDEO 已进入 `REQUIRED`，`QUEUED/READY/FAILED` 等已有状态未被覆盖。

## 5. P2 应在数据库治理版本处理

### DB-P2-01：NULL 使根级唯一约束失效

MySQL 唯一索引允许多行 NULL：

- `catalog(comic_id, parent_id, title)` 允许同漫画多个同名根 catalog。
- `chapter(comic_id, catalog_id, chapter_no)` 允许根级章节编号重复。
- `tag(name, type)` 在 type 为 NULL 时允许重复。

建议使用非空 scope 列/生成列（例如 `COALESCE(parent_id,0)`）建立唯一键，或将 tag.type 改为 NOT NULL 明确默认类型。应用层校验仍需保留，但不能替代并发唯一约束。

### DB-P2-02：management_task_id 注释称一对一但数据库不保证

`import_task/recovery_task/export_task/directory_scan_task.management_task_id`：

- 没有 UNIQUE。
- 没有 FK。
- 没有索引。

建议添加 UNIQUE，并使用 `ON DELETE SET NULL` 的外键，允许统一任务清理后保留历史扩展记录；若产品决定两类记录必须同生共死，则改用 CASCADE，但必须统一四张表。

### DB-P2-03：媒体状态与媒体类型允许互相矛盾

当前数据库允许：

- IMAGE 使用 REQUIRED/TRANSCODING。
- VIDEO 具有 READY LQ 路径和非零 lq_size。
- READY HQ 没有 hq_path/file_size。
- 负数 size、width、height、duration。

建议先清理历史数据，再增加 MySQL CHECK 约束：

- `media_type IN ('IMAGE','VIDEO')`。
- size/dimension/duration 非负。
- IMAGE 的 transcode_status 为 NOT_NEEDED。
- 当前产品不支持视频 LQ，因此 VIDEO 的 lq_path 为空、lq_size=0、lq_status=NOT_GENERATED。

状态依赖约束若过于复杂，可保留在 Service，但简单数值和枚举集合约束应落到数据库。

### DB-P2-04：recovery_task 默认值与 Java 枚举不一致

V10 只把历史 PENDING 更新为 QUEUED，没有修改列默认值。Java `RecoveryTaskStatus` 不包含 PENDING。虽然当前创建服务显式写 QUEUED，但任何漏设状态的 insert 都会产生 ORM 无法解析的数据。

建议将默认值改为 QUEUED，并增加 schema contract 测试。

### DB-P2-05：分类双写并且 category_id 没有外键

`comic.category` 是旧名称镜像，`comic.category_id` 才是规范关系。分类重命名只更新 category 表，不会同步所有 comic.category；删除分类也没有 FK 阻止悬空 ID。

建议：

1. MetadataExporter 改为按 category_id 查询名称。
2. 回填并验证 category_id。
3. 为 `comic.category_id` 增加 `ON DELETE SET NULL` 外键。
4. 删除旧 `comic.category` 列和实体字段。

### DB-P2-06：核心查询缺少与访问模式匹配的复合索引

应基于真实 EXPLAIN 决定，优先候选：

- `page(chapter_id, status, page_number)`：阅读与生命周期页列表。
- `chapter(comic_id, catalog_id, sort_order)`：目录节点内章节排序。
- legacy 扩展表的 `management_task_id` 唯一索引。

不要一次为 hq/lq/transcode 每个状态建立大量低选择性单列索引；漫画级操作通常先按 chapter_id 收敛，再过滤媒体状态。

### DB-P2-07：Mapper SQL 违反项目数据库规范

- `OutboxMessageMapper.pollPending` 使用 `SELECT *`。
- `ComicMapper` 存在 `SELECT *`。
- `StorageMapper.xml` 外层使用 `SELECT *`。
- Outbox/Inbox 清理 DELETE 被标注为 `@Select`，应使用 `@Delete`。

这不全是表结构问题，但会削弱 schema 演进安全性，并违反项目约定的“查询明确列名”。

## 6. P3 长期治理项

### DB-P3-01：物理表 page 与领域实体 Media 命名不一致

IMAGE/VIDEO 共表是合理设计；问题只是历史物理名称。当前不建议为了美观立即重命名，因为会影响大量 Mapper、脚本、测试和运维 SQL。

短期在文档固定：`page` 是历史物理表名，领域实体统一叫 Media。等高优先级约束稳定后，再单独评估 `page → media` 迁移。

### DB-P3-02：运行库存在未映射遗留列和孤立表

实体与真实库自动比对确认：

- `comic`: `cover_path/lq_status/root_key/relative_path`
- `catalog`: `path/level`
- `page`: `hq_size`
- `import_task`: `current_page/downloaded_bytes`
- 无实体表：`operation_log`

真实数据中 `cover_path/lq_status/relative_path` 均无非空值，`root_key` 95 行均有旧默认值，`catalog.path/level` 各只有 1 条旧数据，`page.hq_size` 只有 41 条非零且完全等于 `file_size`。应先生成清理迁移的存量报告和备份，再删除；不能只改 V1，因为已 baseline 的数据库不会重新执行 V1。

### DB-P3-03：import_task 存在低价值或未维护字段

实体中的 `downloaded_pages` 只被返回给 VO，未发现生产写入；真实表还保留实体未映射的 `current_page/downloaded_bytes`；`source_ref/source_path` 对 DIRECTORY 场景存在重复。应先查询非空率和调用链，再通过新迁移删除或重新定义，不能仅凭字段名保留。

### DB-P3-04：Flyway 版本号存在 V2→V10 空档

Flyway 允许版本不连续，且 README 已说明 V3-V9 被归档。不要重命名已应用迁移；只需从下一个新版本继续，并保持归档目录不参与运行。

## 7. 推荐目标模型

### 7.1 媒体表

继续使用单表承载图片和视频：

```text
Media（物理表暂保留 page）
├── 通用：id, chapter_id, page_number, status
├── HQ：hq_root, hq_path, hq_status, file_size
├── LQ（仅图片）：lq_root, lq_path, lq_status, lq_size
├── 通用画面：width, height
└── 视频：duration, container, video_codec, audio_codec, transcode_status
```

明确规则：

- IMAGE 和 VIDEO 都计入 HQ 存储。
- 只有 IMAGE 生成 LQ。
- `file_size` 不是图片专用字段，也不是“原始 ZIP 大小”。
- `hq_status/lifecycle status` 共同决定文件是否计入当前存储统计。

### 7.2 统计

推荐以 page 明细为唯一事实源：

```sql
SELECT
  ch.comic_id,
  SUM(CASE WHEN p.status NOT IN ('TRASHED','DELETED')
            AND p.hq_status = 'READY'
           THEN COALESCE(p.file_size,0) ELSE 0 END) AS hq_bytes,
  SUM(CASE WHEN p.status NOT IN ('TRASHED','DELETED')
            AND p.media_type = 'IMAGE'
            AND p.lq_status = 'READY'
           THEN COALESCE(p.lq_size,0) ELSE 0 END) AS lq_bytes
FROM chapter ch
LEFT JOIN page p ON p.chapter_id = ch.id
GROUP BY ch.comic_id;
```

高频读取使用 Redis 缓存和变更后失效，不在多个 Handler 中分别维护 comic 汇总列。

### 7.3 任务

- `management_task/item` 保存统一状态、聚合、幂等和重试。
- import/recovery/export/scan 表只保存各能力特有 payload/result。
- 四个扩展表与 management_task 建立明确的一对一约束。
- Outbox 和 Inbox 都必须以 event_id 为数据库唯一键。

## 8. 推荐迁移顺序

### 第一阶段：完整性止血

1. 部署包含 V18 的 API，并确认运行库从 V17 正常迁移到 V18。
2. Outbox 重复数据预检并添加 event_id 主键。
3. 修正 recovery_task 默认值。
4. 增加迁移测试对 PK、默认值的断言。
5. MySQL IT 全部改为 Flyway 建库或增加 schema 等价性门禁。

### 第二阶段：归属与唯一约束

1. 修复已确认的 4 条跨漫画 reading_history，并输出 catalog/chapter/history/upload 完整异常清单。
2. 添加复合归属外键。
3. 修复根级 NULL 唯一约束。
4. 为 category_id 和 management_task_id 增加约束。

### 第三阶段：统计单一事实源

1. 统一 StorageMapper 的 IMAGE/VIDEO 与生命周期过滤规则。
2. 所有接口改从 page 聚合或缓存读取。
3. 在一段验证期内对比旧 comic 汇总值与新聚合值并记录差异。
4. 停止写入并最终删除 `comic.file_size/hq_size/lq_size`。

### 第四阶段：清理与命名

1. 删除经存量验证的 `page.hq_size`、`comic`/`catalog` 遗留列、`operation_log` 和确认无用的 import_task 字段。
2. 删除 `comic.category` 前先停止双写并完成 `category_id` 外键迁移。
3. 修正 `SELECT *`、`@Select(DELETE)`。
4. 依据真实 EXPLAIN 添加最小复合索引。
5. 最后再决定是否把物理表 `page` 重命名为 `media`。

## 9. 审核通过标准

完成以下条件前，数据库设计状态保持“不批准”：

- Outbox event_id 在真实 MySQL 中是 PRIMARY KEY。
- 运行库 `flyway_schema_history` 已达到源码最新版本，V18 的历史视频重分类结果已验证。
- 数据库无法插入跨漫画 catalog、chapter、reading_history 关系。
- 已确认的 4 条跨漫画阅读历史已修复且负向约束测试通过。
- IMAGE/VIDEO 的 HQ/LQ 统计只有一个定义，所有接口结果一致。
- `comic` 不再保存无人可靠维护的 size 汇总，或已迁入具备唯一更新者的统计表。
- 运行库不再包含实体未映射且无明确运维用途的遗留列/表。
- Flyway 是 MySQL schema 的唯一事实源，测试不再使用更弱或不同的复制 schema。
- migration test 对关键主键、外键、唯一键、默认值、列类型和索引进行断言。
- 所有迁移具备存量预检、回填、失败回滚说明和真实 MySQL 升级测试。

## 10. 最终判断

ComicAtlas 的领域拆分和“Media 同时承载 IMAGE/VIDEO”是正确的，不需要拆成 image/video 两张表。当前主要问题不是 ORM 选型，也不是 Flyway 不好用，而是：

1. 数据库约束弱于代码假设。
2. 冗余统计没有唯一维护者。
3. 生产与测试 schema 存在多源维护。

先完成第一、第二阶段，数据库才具备可靠演进基础；第三阶段完成后，存储统计才可被视为可信业务数据。
