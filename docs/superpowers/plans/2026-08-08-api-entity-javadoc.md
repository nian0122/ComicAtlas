# api-service 实体类按阿里开发规范整理实施计划

**状态**: 待执行

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `com/comicatlas/api` 下 18 个 MyBatis-Plus 实体类补齐类级/字段级 Javadoc，标注 `Comic.category` 过渡遗留字段。纯注释变更，零业务逻辑。

**Architecture:** 按业务域拆 4 批（comic / management+outbox / importer+export+reader+upload / category 标注），每批独立提交 + 编译门禁 + 覆盖检查。

**Design spec:** `docs/superpowers/specs/2026-08-08-api-entity-javadoc-design.md`

## Global Constraints

- **不改任何业务逻辑**：实体字段、类型、Lombok 注解、DB 迁移一律不动；只新增/升级 Javadoc 注释。
- **注释规范**（阿里规范）：
  - 类级：职责一句话 + 关键约束（枚举存储方式、关联关系、树结构等）。
  - 字段级：单行 `/** 说明 */`，解释业务语义/单位/可选性/枚举存储，不重复字段名。
  - 已有字段注释保留；已有类注释保留。
  - 中文注释；不补作者/日期；不新增 `//` 行内注释。
- `Comic.category`：**只加 Javadoc 标注过渡遗留，不删除字段、不改 MetadataExporter、不动 DB**。
- 每批 `git status` 确认只含本批文件；提交信息中文"动作 + 内容"。
- 字段语义来源：`db/migration/V*.sql`、Mapper 使用、Controller/Service 消费方——读取后再写注释，不臆造。

---

### Task 1: comic 域实体（7 个）

**Files (Modify):**
- `comic/entity/Comic.java`（有类注释，补字段注释 21/22；category 字段本批先不动）
- `comic/entity/Catalog.java`（缺类注释 + 6 字段全缺）
- `comic/entity/Category.java`（缺类注释 + 5 字段全缺）
- `comic/entity/Chapter.java`（有类注释，补 2 字段）
- `comic/entity/ComicTag.java`（缺类注释 + 2 字段全缺）
- `comic/entity/Media.java`（有类注释，补 3 字段）
- `comic/entity/Tag.java`（缺类注释 + 3 字段全缺）

- [ ] **Step 1: 阅读 7 个实体与相关迁移（V1/V2/V3）理解字段语义**
- [ ] **Step 2: 补类 Javadoc（Catalog/Category/ComicTag/Tag）+ 全字段注释**
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/comic/entity
git commit -m "补充实体注释：comic 域实体 Javadoc（Comic/Catalog/Category/Media/Tag 等）"
```

---

### Task 2: management + outbox 域实体（4 个）

**Files (Modify):**
- `management/entity/ManagementTask.java`（有类注释，补 16 字段）
- `management/entity/ManagementTaskItem.java`（有类注释，补 11 字段）
- `outbox/entity/OutboxMessage.java`（有类注释，补 13 字段）
- `outbox/entity/InboxReceipt.java`（有类注释，补 5 字段）

- [ ] **Step 1: 阅读 4 个实体与迁移（V11 管理任务、V13 outbox/inbox）理解字段语义**
- [ ] **Step 2: 补全字段注释**（ManagementTask 已有部分注释可作风格基准）
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/management/entity api-service/src/main/java/com/comicatlas/api/outbox/entity
git commit -m "补充实体注释：management/outbox 域实体 Javadoc（Task/Message 等）"
```

---

### Task 3: importer + export + reader + upload 域实体（7 个）

**Files (Modify):**
- `importer/entity/ImportTask.java`（补 1 字段）
- `importer/entity/DirectoryScanTask.java`（补 1 字段）
- `importer/entity/RecoveryTask.java`（补 1 字段）
- `export/entity/ExportTask.java`（补 1 字段）
- `reader/entity/ReadingHistory.java`（缺类注释 + 6 字段全缺）
- `upload/entity/UploadFile.java`（13 字段全缺）
- `upload/entity/UploadSession.java`（11 字段全缺）

- [ ] **Step 1: 阅读 7 个实体与相关迁移理解字段语义**
- [ ] **Step 2: 补类 Javadoc（ReadingHistory）+ 全字段注释**
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/importer/entity api-service/src/main/java/com/comicatlas/api/export/entity api-service/src/main/java/com/comicatlas/api/reader/entity api-service/src/main/java/com/comicatlas/api/upload/entity
git commit -m "补充实体注释：importer/export/reader/upload 域实体 Javadoc"
```

---

### Task 4: Comic.category 过渡遗留标注

**Files (Modify):**
- `comic/entity/Comic.java`（仅 category 字段加 Javadoc）

- [ ] **Step 1: 为 category 字段加标注**
```java
/** 分类名（V3 迁移前的过渡遗留列，已由 {@link #categoryId} 取代；保留供 MetadataExporter 导出历史数据） */
private String category;
```
- [ ] **Step 2: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/comic/entity/Comic.java
git commit -m "标注 Comic.category 过渡遗留字段说明"
```

---

### Task 5: 收尾验证

- [ ] **Step 1: 覆盖检查**
- 脚本统计 18 实体：类级 Javadoc 覆盖率 + 字段注释覆盖率，应 100%。
```powershell
$entities = Get-ChildItem api-service/src/main/java/com/comicatlas/api -Recurse -Filter *.java | Select-String -Pattern "@TableName" -List | Select-Object -ExpandProperty Path
foreach ($e in $entities) { $head = (Get-Content $e -TotalCount 40) -join "`n"; if ($head -notmatch "/\*\*") { "缺类注释: $($e | Split-Path -Leaf)" } }
```
- [ ] **Step 2: 全量门禁**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests   # exit 0
git log --oneline -5
git status --short   # 干净
```
- [ ] **Step 3: 汇总**
- 输出 4 批提交清单 + 覆盖率统计 + 编译结果到最终报告。
