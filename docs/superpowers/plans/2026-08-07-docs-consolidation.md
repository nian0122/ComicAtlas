# 项目核心文档全面整理实施计划

**状态**: 历史归档

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 整理 66 篇核心文档（根 3 + docs/ 63）——更新过时内容、统一格式、归档冗余，按阿里规范。

**Architecture:** 5 个批次（AGENTS+索引 / architecture / api / guide+operations+frontend / 归档收尾），每批独立提交 + grep 校验旧路径清零。

**Design spec:** `docs/superpowers/specs/2026-08-07-docs-consolidation-design.md`

## Global Constraints

- **不动** `.omo/drafts` 草稿；**不改写** `architecture/adr/` 决策内容（仅同步交叉引用）。
- 旧包路径映射（机械替换时逐处核对语义）：`file/parse/DirectoryParser`→`importer/DirectoryParser`、`file/parse/MediaAnalyzer`→`media/MediaAnalyzer`、`file/parse/ComicMetadata`→`media/ComicMetadata`、`file/parse/ImportContext`→`importer/ImportContext`、`file/parse/MetadataAssembler`→`importer/MetadataAssembler`、`file/handler/*`→`importer/*`、`file/storage/*`→`storage/*`、`LocalStorageService`→`StorageService`、`FilePathBuilder`（已删，删除相关描述）。
- 事件数量：`16 个 record` → `37 个 record`（`ComicEvent` sealed 接口 + 各域事件）。
- 文档头部模板统一：`# 标题` + `**更新日期**/**状态**/**维护者**`。
- 提交信息中文"动作 + 内容"；每批 `git status` 确认只含本批文档。

---

### Task 1: AGENTS.md + docs/README.md 索引

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/README.md`

- [ ] **Step 1: 更新 AGENTS.md**

- STRUCTURE 部分：worker-service 行已含模块化包（复查完整）；comic-common 行补充 `constant/dto/enums/metadata/mq/util` 包描述。
- WHERE TO LOOK 表：
  - "事件 DTO"行：`16 个 record` → `37 个 record`（含 ComicEvent sealed + 各域事件）
  - 补充 `comic-common/.../constant/`（MqExchanges/MqQueues/MqRoutingKeys）、`comic-common/.../metadata/`（MetadataV3/MetadataJsonBuilder）、`comic-common/.../mq/`（MqConsumerSupport）、`comic-common/.../util/`（ImageDimensionsReader）、`comic-common/.../enums/`、`comic-common/.../dto/` 条目
  - worker 模块化路径复查（command/importer/media/storage 应已正确，逐条核对）
- RABBITMQ 表、IMPORT FLOW、CONFIG/ENV、DB SCHEMA 部分核对（新增事件/常量是否遗漏）。

- [ ] **Step 2: 更新 docs/README.md 索引**

- 修正目录描述：`decisions/`（ADR 位于 `architecture/adr`）、`superpowers/`（specs/plans 历史归档）、`issues/`（BUG 记录）、新增 `development/`（java-naming）。

- [ ] **Step 3: 验证 + 提交**

```bash
# grep 确认 AGENTS.md 无 "16 个 record" 残留
Select-String AGENTS.md -Pattern "16 个 record"
git add AGENTS.md docs/README.md
git commit -m "整理核心索引文档：AGENTS.md 事件数/新包/模块化路径同步，docs/README.md 目录修正"
```

---

### Task 2: architecture/（12 篇）

**Files (Modify):**
- `docs/architecture/00-index.md`、`01-system-overview.md`、`02-import-pipeline.md`、`03-storage.md`、`04-management.md`、`05-domain.md`、`06-api.md`、`07-frontend.md`、`08-migration.md`、`architecture/adr/0001-unified-import-pipeline.md`

- [ ] **Step 1: 更新旧包路径引用（3 篇重点）**

- `01-system-overview.md`（L65/70/160）、`02-import-pipeline.md`（L165/189/237）、`03-storage.md`（L61/81/83）：按 Global Constraints 映射更新 `file/parse`→`importer`/`media`、`file/storage`→`storage`、`LocalStorageService`→`StorageService`。
- `02-import-pipeline.md` 流程图核对（DirectoryParser/MetadataAssembler/StorageService 现所在包）。

- [ ] **Step 2: 核对 04-08 篇 + ADR 交叉引用**

- `04-management`/`05-domain`/`06-api`/`07-frontend`/`08-migration`：抽查引用新结构/接口，修正过时描述。
- `architecture/adr/0001-unified-import-pipeline.md`（L170-173）：**仅同步交叉引用**（包路径），不改写决策内容与结论。

- [ ] **Step 3: 验证 + 提交**

```bash
# grep 确认 architecture/ 无旧路径残留（除 ADR 历史保留的决策描述）
Get-ChildItem docs/architecture -Recurse -Filter *.md | Select-String -Pattern "file/parse|file/handler|file/storage|LocalStorageService"
git add docs/architecture/
git commit -m "整理 architecture 文档：旧包路径更新为 importer/media/storage，ADR 仅同步交叉引用"
```

---

### Task 3: docs/api.md + architecture/06-api.md

**Files (Modify):**
- `docs/api.md`
- `docs/architecture/06-api.md`

- [ ] **Step 1: 核对 docs/api.md**

- 接口核对：`/api/storage` 统一端点（LQ/HQ/导出/转码/统计/刷新元数据）、回收站、DLQ、批量操作、上传分块。
- 事件表：`16 个 record` → `37 个`（与 AGENTS.md 一致）。
- 修正已删除的旧端点（LQ/HQ 旧控制器、Admin 旧接口）。

- [ ] **Step 2: 同步 architecture/06-api.md**

- 与 api.md 的端点/事件描述保持一致。

- [ ] **Step 3: 验证 + 提交**

```bash
Select-String docs/api.md,docs/architecture/06-api.md -Pattern "16 个 record|/api/lq|/api/hq|/admin/storage"
git add docs/api.md docs/architecture/06-api.md
git commit -m "整理 API 文档：/api/storage 统一端点与 37 个事件表同步"
```

---

### Task 4: guide + operations + frontend + testing + troubleshooting

**Files (Modify):**
- `docs/development-guide.md`
- `docs/development/java-naming.md`
- `docs/operations/management.md`
- `docs/frontend/` 9 篇（01-09）
- `docs/testing/`（beta-v0.1-checklist.md、release-checklist.md）
- `docs/troubleshooting/storage-stats-504.md`

- [ ] **Step 1: development + operations**

- `development-guide.md`：git 流程/分支/提交规范核对（与 AGENTS.md 一致）。
- `development/java-naming.md`（L104 旧路径）：映射更新。
- `operations/management.md`：worker 只读账号、MANGA_ROOT、存储约定核对。

- [ ] **Step 2: frontend 9 篇**

- 路由（14 routes）/接口（api.ts）/页面核对；修正与后端新结构不一致的描述。

- [ ] **Step 3: testing + troubleshooting**

- 核对清单引用（接口/流程）与现状一致。

- [ ] **Step 4: 验证 + 提交**

```bash
Get-ChildItem docs/development,docs/operations,docs/frontend,docs/testing,docs/troubleshooting -Recurse -Filter *.md | Select-String -Pattern "file/parse|file/handler|file/storage|LocalStorageService|16 个 record"
git add docs/development docs/operations docs/frontend docs/testing docs/troubleshooting
git commit -m "整理开发/运维/前端/测试/排障文档：同步模块化结构与统一接口"
```

---

### Task 5: 归档清理 + 收尾验证

**Files (Modify):**
- `docs/issues/BUG-001.md` ~ `BUG-007.md`（状态标注）
- `docs/superpowers/specs/*.md`、`docs/superpowers/plans/*.md`（历史归档标记）
- `DESIGN.md`（核对或标注归档）

- [ ] **Step 1: issues 状态标注**

- 每个 BUG 文件头部加状态行（如 `**状态**: 已解决` 或 `**状态**: 待验证`），依据文件名/内容推断；无法确定的标"待验证"。

- [ ] **Step 2: superpowers 历史归档标记**

- `docs/superpowers/specs/` 与 `plans/` 各文档头部加 `**状态**: 历史归档`（保留内容，标注为已完成 brainstorming/计划产物）。

- [ ] **Step 3: DESIGN.md 核对**

- 核对与现状一致性；若为历史设计文档，加 `**状态**: 历史归档`。

- [ ] **Step 4: 收尾验证 + 提交**

```bash
# 全库旧路径残留检查（应仅剩 ADR 历史决策描述，若需保留则白名单）
Get-ChildItem AGENTS.md,README.md,DESIGN.md,docs -Recurse -Filter *.md | Select-String -Pattern "file/parse|file/handler|file/storage|LocalStorageService|FilePathBuilder|16 个 record"
# docs/README.md 索引链接有效性（抽查架构/前端/运维等入口可打开）
git add docs/issues docs/superpowers DESIGN.md
git commit -m "归档整理：issues 状态标注、superpowers 历史归档标记、DESIGN.md 核对"
```
