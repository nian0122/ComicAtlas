# api-service 实体类按阿里开发规范整理实施计划

**状态**: 待执行

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `com/comicatlas/api` 下 18 个 MyBatis-Plus 实体类补齐类级/字段级 Javadoc，标注 `Comic.category` 过渡遗留字段，并按阿里规范 DO/DTO/VO 分层统一接口实体命名。纯注释/命名变更，零业务逻辑。

**Architecture:** 按业务域拆 5 批（comic / management+outbox / importer+export+reader+upload / dto 命名统一 / category+Service 标注），每批独立提交 + 编译门禁 + 覆盖检查。

**Design spec:** `docs/superpowers/specs/2026-08-08-api-entity-javadoc-design.md`

## Global Constraints

- **不改任何业务逻辑**：实体字段、类型、Lombok 注解、DB 迁移一律不动；只新增/升级 Javadoc 注释与 dto 类命名。
- **注释规范**（阿里规范）：
  - 类级：职责一句话 + 关键约束（枚举存储方式、关联关系、树结构等）+ **数据库实体分层边界声明**（"数据库实体，禁止直接暴露给接口；对外使用 `dto/` 包对应 DTO/VO"）。
  - 字段级：单行 `/** 说明 */`，解释业务语义/单位/可选性/枚举存储，不重复字段名。
  - 已有字段注释保留；已有类注释保留（追加分层边界声明）。
  - 中文注释；不补作者/日期；不新增 `//` 行内注释。
- **dto 重命名映射**（唯一权威来源，同步更新全部引用方含测试）：
  - `ComicDeleteStats` → `ComicDeleteStatsDTO`（admin.dto）
  - `ComicTranscodeStatus` → `ComicTranscodeStatusVO`（admin.dto）
  - `RecoveryProgress` → `RecoveryProgressVO`（admin.dto）
  - `RefreshMetadataResult` → `RefreshMetadataResultDTO`（admin.dto）
  - `MediaItemInfo` → `MediaItemInfoDTO`（comic.dto）
  - `OperationSubmitResult` → `OperationSubmitResultDTO`（management.dto）
  - `BatchOperationPayload` → `BatchOperationPayloadDTO`（management.batch.dto）
  - `BatchSelection` → `BatchSelectionVO`（management.batch.dto）
- **Service 返回 entity 方法**（3 处，仅加 Javadoc 边界说明不改签名）：`ManagementTaskService.findByIdempotencyKey`、`ManagementTaskService.findActiveItem`、`UploadSessionService.getBySessionId`
- `Comic.category`：只加 Javadoc 标注过渡遗留，不删除字段、不改 MetadataExporter、不动 DB。
- 每批 `git status` 确认只含本批文件；提交信息中文"动作 + 内容"。
- 字段语义来源：`db/migration/V*.sql`、Mapper 使用、Controller/Service 消费方——读取后再写注释，不臆造。

---

### Task 1: comic 域实体（7 个）

**Files (Modify):**
- `comic/entity/Comic.java`（有类注释，追加分层声明 + 补字段注释；category 字段本批先不动）
- `comic/entity/Catalog.java`（缺类注释 + 6 字段全缺）
- `comic/entity/Category.java`（缺类注释 + 5 字段全缺）
- `comic/entity/Chapter.java`（有类注释，追加分层声明 + 补 2 字段）
- `comic/entity/ComicTag.java`（缺类注释 + 2 字段全缺）
- `comic/entity/Media.java`（有类注释，追加分层声明 + 补 3 字段）
- `comic/entity/Tag.java`（缺类注释 + 3 字段全缺）

- [ ] **Step 1: 阅读 7 个实体与相关迁移（V1/V2/V3）理解字段语义**
- [ ] **Step 2: 补类 Javadoc（Catalog/Category/ComicTag/Tag，含分层边界声明）+ 13 个已有类注释实体追加分层声明 + 全字段注释**
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/comic/entity
git commit -m "补充实体注释：comic 域实体 Javadoc（含分层边界声明）"
```

---

### Task 2: management + outbox 域实体（4 个）

**Files (Modify):**
- `management/entity/ManagementTask.java`（有类注释，追加分层声明 + 补 16 字段）
- `management/entity/ManagementTaskItem.java`（有类注释，追加分层声明 + 补 11 字段）
- `outbox/entity/OutboxMessage.java`（有类注释，追加分层声明 + 补 13 字段）
- `outbox/entity/InboxReceipt.java`（有类注释，追加分层声明 + 补 5 字段）

- [ ] **Step 1: 阅读 4 个实体与迁移（V11 管理任务、V13 outbox/inbox）理解字段语义**
- [ ] **Step 2: 追加分层边界声明 + 补全字段注释**（ManagementTask 已有部分注释可作风格基准）
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/management/entity api-service/src/main/java/com/comicatlas/api/outbox/entity
git commit -m "补充实体注释：management/outbox 域实体 Javadoc（含分层边界声明）"
```

---

### Task 3: importer + export + reader + upload 域实体（7 个）

**Files (Modify):**
- `importer/entity/ImportTask.java`（追加分层声明 + 补 1 字段）
- `importer/entity/DirectoryScanTask.java`（追加分层声明 + 补 1 字段）
- `importer/entity/RecoveryTask.java`（追加分层声明 + 补 1 字段）
- `export/entity/ExportTask.java`（追加分层声明 + 补 1 字段）
- `reader/entity/ReadingHistory.java`（缺类注释含分层声明 + 6 字段全缺）
- `upload/entity/UploadFile.java`（追加分层声明 + 13 字段全缺）
- `upload/entity/UploadSession.java`（追加分层声明 + 11 字段全缺）

- [ ] **Step 1: 阅读 7 个实体与相关迁移理解字段语义**
- [ ] **Step 2: 补类 Javadoc（ReadingHistory）+ 追加分层声明 + 全字段注释**
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/importer/entity api-service/src/main/java/com/comicatlas/api/export/entity api-service/src/main/java/com/comicatlas/api/reader/entity api-service/src/main/java/com/comicatlas/api/upload/entity
git commit -m "补充实体注释：importer/export/reader/upload 域实体 Javadoc（含分层边界声明）"
```

---

### Task 4: 统一接口实体命名（8 个 dto 重命名）

**Files (Modify):**
- `admin/dto/ComicDeleteStats.java` → `ComicDeleteStatsDTO.java`
- `admin/dto/ComicTranscodeStatus.java` → `ComicTranscodeStatusVO.java`
- `admin/dto/RecoveryProgress.java` → `RecoveryProgressVO.java`
- `admin/dto/RefreshMetadataResult.java` → `RefreshMetadataResultDTO.java`
- `comic/dto/MediaItemInfo.java` → `MediaItemInfoDTO.java`
- `management/dto/OperationSubmitResult.java` → `OperationSubmitResultDTO.java`
- `management/batch/dto/BatchOperationPayload.java` → `BatchOperationPayloadDTO.java`
- `management/batch/dto/BatchSelection.java` → `BatchSelectionVO.java`
- Modify: 全部引用方 import + 类型引用（Controller/Service/事件/测试）

- [ ] **Step 1: 按 Global Constraints 映射重命名 8 个文件与类名**（文件重命名 + `public record/class` 声明改名 + 类内自引用更新）
- [ ] **Step 2: 更新全部引用方**
- 全量 grep 旧名（`\bComicDeleteStats\b` 等），逐文件更新 import + 类型引用（含测试）。注意 `RefreshMetadataResult` 前缀会命中 `RefreshMetadataResult` 服务类——只改 DTO 引用。
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests   # 需零错误
# 残留检查: 旧名不再作为类型出现
Select-String api-service/src -Pattern "\bComicDeleteStats\b|\bComicTranscodeStatus\b|\bRecoveryProgress\b|\bRefreshMetadataResult\b|\bMediaItemInfo\b|\bOperationSubmitResult\b|\bBatchOperationPayload\b|\bBatchSelection\b"
git add api-service/src/main/java/com/comicatlas/api
git commit -m "统一接口实体命名：dto 无后缀类补 DTO/VO 后缀（8 个 + 引用方更新）"
```

---

### Task 5: category 标注 + Service 实体边界说明

**Files (Modify):**
- `comic/entity/Comic.java`（category 字段加 Javadoc 标注）
- `management/service/ManagementTaskService.java`（findByIdempotencyKey/findActiveItem 加边界说明）
- `upload/service/UploadSessionService.java`（getBySessionId 加边界说明）

- [ ] **Step 1: category 字段标注**
```java
/** 分类名（V3 迁移前的过渡遗留列，已由 {@link #categoryId} 取代；保留供 MetadataExporter 导出历史数据） */
private String category;
```
- [ ] **Step 2: 3 个 Service 方法加 Javadoc**："内部方法，返回数据库实体，禁止用于接口响应；对外使用对应 DTO/VO。"
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/comic/entity/Comic.java api-service/src/main/java/com/comicatlas/api/management/service/ManagementTaskService.java api-service/src/main/java/com/comicatlas/api/upload/service/UploadSessionService.java
git commit -m "标注 Comic.category 过渡遗留字段与 Service 内部方法实体边界说明"
```

---

### Task 6: 收尾验证

- [ ] **Step 1: 覆盖检查**
- 脚本统计 18 实体：类级 Javadoc 覆盖率 + 字段注释覆盖率，应 100%。
```powershell
$entities = Get-ChildItem api-service/src/main/java/com/comicatlas/api -Recurse -Filter *.java | Select-String -Pattern "@TableName" -List | Select-Object -ExpandProperty Path
foreach ($e in $entities) { $head = (Get-Content $e -TotalCount 40) -join "`n"; if ($head -notmatch "/\*\*") { "缺类注释: $($e | Split-Path -Leaf)" } }
```
- dto 旧名零残留（排除历史归档文档）。
- [ ] **Step 2: 全量门禁**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests   # exit 0
.\mvnw -pl api-service -am test 2>&1 | Select-String "Tests run:|BUILD"   # 关键测试含 dto 重命名引用方
git log --oneline -6
git status --short   # 干净
```
- [ ] **Step 3: 汇总**
- 输出 5 批提交清单 + 覆盖率统计 + 编译/测试结果到最终报告。
