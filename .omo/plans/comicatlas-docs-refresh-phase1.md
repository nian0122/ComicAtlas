# comicatlas-docs-refresh-phase1 - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** Four architecture documents (system overview, import pipeline, storage model, and an ADR), an updated API document with import/scan endpoints, and a new database schema document covering all core tables and enums.

**Why this approach:** We first establish canonical architecture documents that describe the system as it is actually built, then point the API and database docs at those canonical explanations instead of repeating the import/storage details. This keeps docs consistent and makes future changes easier to propagate.

**What it will NOT do:** It will not change any code, create fictional classes like `ImportHandlerFactory`, rename existing enums, update frontend/README/coding-style docs, or add endpoints that do not exist.

**Effort:** Medium
**Risk:** Low - this is documentation-only work; no runtime behavior changes.
**Decisions to sanity-check:** (1) Whether you are OK documenting `DIRECTORY` as an alias for `REGISTER` in the worker, since the public enum only has `ZIP/REGISTER/EHENTAI`. (2) Whether `metadata/` and `thumbs/` should stay outside the `StorageRoot` concept, since only `HQ` and `LQ` are configured as roots today.

Your next move: approve the plan and let the agents execute Wave 1 (the four architecture docs in parallel). Full execution detail follows below.

---

> TL;DR (machine): <1 line - effort, risk, deliverables>

## Scope
### Must have
- Create `docs/architecture/01-system-overview.md` with module responsibility table and high-level data-flow diagram.
- Create `docs/architecture/02-import-pipeline.md` documenting the unified import pipeline: `Acquire → ImportTask → Handler routing → DirectoryParser → DirectoryTree → MetadataAssembler → ComicMetadata → StorageService → metadata.json → API Consumer → Database`. Include the ImportTask state machine and a sequence diagram.
- Create `docs/architecture/03-storage.md` covering StoragePolicy, MANAGED file layout, StorageService/LocalStorageService responsibilities, Page storage fields, and abstract URL generation.
- Create `docs/architecture/adr/0001-unified-import-pipeline.md` with the architectural decision and rejected alternatives.
- Update `docs/api.md`: document `/api/tasks/import` (create/list/detail/status/cancel/retry), `/api/admin/storage/scan-recover`, ImportTask state machine, and add cross-links to architecture docs.
- Create `docs/database/schema.md` with ER diagram and field tables for `comic`, `catalog`, `chapter`, `page`, `import_task`, plus status/value enums sourced from code.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- No code changes or refactors to create `ImportHandlerFactory`, `StorageManager`, `/api/tasks/scan`, or rename enum values.
- No updates to `docs/frontend/`, `docs/worker/`, `docs/reader/`, `docs/admin/`, `README.md`, or coding-style docs.
- No speculative future detail beyond a one-line acknowledgment for `EXTERNAL`/`OBJECT_STORAGE` policies or Torrent/EHentai-future handlers.
- Do not document `{globalOrder}` as the physical path segment; actual layout uses `{chapterId}`.
- Do not document `PROCESSING`/`COMPLETED` as ImportTask states; actual enum uses `IMPORTING`/`SUCCESS`.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: none (documentation only)
- Evidence: `.omo/evidence/task-<N>-comicatlas-docs-refresh-phase1.<ext>`
- Automated checks: `Test-Path`, `grep` for required sections, `grep -i` for banned terms, exact enum-literal diff against Java enums, cross-link presence checks.

## Execution strategy
### Parallel execution waves
- **Wave 1**: Create architecture docs (01-system-overview, 02-import-pipeline, 03-storage, ADR). These are independent and can be drafted in parallel, but should be reviewed together for consistency.
- **Wave 2**: Update `docs/api.md` with import/scan endpoints, state machine, and architecture cross-links.
- **Wave 3**: Create `docs/database/schema.md` based on `schema.sql` and Java entities/enums.
- **Wave 4 (Final verification)**: Run automated doc checks and fix any gaps.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1. 01-system-overview | — | — | 2, 3, 4 |
| 2. 02-import-pipeline | — | 5. api.md import section | 1, 3, 4 |
| 3. 03-storage | — | 5. api.md storage URL refs | 1, 2, 4 |
| 4. ADR | — | — | 1, 2, 3 |
| 5. Update api.md | 2, 3 | 7. final verification | 6. schema.md |
| 6. Create schema.md | — | 7. final verification | 5. api.md |
| 7. Final verification | 5, 6 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

- [x] 1. Create `docs/architecture/01-system-overview.md`
  What to do / Must NOT do: Write the system overview doc with system layers, module responsibility table, high-level data-flow diagram, and core design principles. Must NOT use fictional class names (`ImportHandlerFactory`, `StorageManager`) without noting the current implementation mapping.
  Parallelization: Wave 1 | Blocked by: — | Blocks: —
  References (executor has NO interview context - be exhaustive): `docs/superpowers/specs/2026-07-16-project-docs-update-phase1-design.md` §3.1; `AGENTS.md` STRUCTURE/IMPORT FLOW; `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java`; `worker-service/src/main/java/com/comicatlas/worker/file/storage/StorageService.java`; `worker-service/src/main/java/com/comicatlas/worker/file/storage/LocalStorageService.java`; `api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventHandler.java`.
  Acceptance criteria (agent-executable):
    - `Test-Path docs/architecture/01-system-overview.md` returns `$true`.
    - `Select-String -Path docs/architecture/01-system-overview.md -Pattern "^## "` returns at least 4 sections.
    - `Select-String -Path docs/architecture/01-system-overview.md -Pattern "ImportTaskHandler|StorageService|LocalStorageService|ImportEventHandler"` returns at least one match.
  QA scenarios (name the exact tool + invocation): happy: `Test-Path docs/architecture/01-system-overview.md`; failure: missing file triggers re-write. Evidence `.omo/evidence/task-1-1-comicatlas-docs-refresh-phase1.md`.
  Commit: Y | docs(architecture): 新增系统 overview 文档

- [x] 2. Create `docs/architecture/02-import-pipeline.md`
  What to do / Must NOT do: Document the unified import pipeline, DirectoryParser/DirectoryTree/MetadataAssembler/ComicMetadata flow, ImportTask state machine (`PENDING → PARSING → IMPORTING → SUCCESS` with `FAILED`/`CANCELLED` terminal), and sequence diagram. Must NOT state `PROCESSING`/`COMPLETED` as enum values. Must note that `"DIRECTORY"` is handled as an alias for `REGISTER` in `ImportTaskHandler`.
  Parallelization: Wave 1 | Blocked by: — | Blocks: 5
  References (executor has NO interview context - be exhaustive): `docs/superpowers/specs/2026-07-16-project-docs-update-phase1-design.md` §3.2; `worker-service/src/main/java/com/comicatlas/worker/file/parse/DirectoryParser.java`; `worker-service/src/main/java/com/comicatlas/worker/file/parse/MetadataAssembler.java`; `worker-service/src/main/java/com/comicatlas/worker/file/handler/DirectoryImportHandler.java`; `worker-service/src/main/java/com/comicatlas/worker/file/handler/ZipImportHandler.java`; `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java:55-67`; `api-service/src/main/java/com/comicatlas/api/common/enums/ImportTaskStatus.java`; `api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventHandler.java`.
  Acceptance criteria (agent-executable):
    - `Test-Path docs/architecture/02-import-pipeline.md` returns `$true`.
    - `Select-String -Path docs/architecture/02-import-pipeline.md -Pattern "PROCESSING|COMPLETED"` returns empty.
    - `Select-String -Path docs/architecture/02-import-pipeline.md -Pattern "PENDING|PARSING|IMPORTING|SUCCESS|FAILED|CANCELLED"` returns at least one match.
    - `Select-String -Path docs/architecture/02-import-pipeline.md -Pattern "DirectoryTree|MetadataAssembler|ComicMetadata"` returns at least one match.
  QA scenarios (name the exact tool + invocation): happy: required states present and banned states absent; failure: banned state found triggers correction. Evidence `.omo/evidence/task-2-comicatlas-docs-refresh-phase1.md`.
  Commit: Y | docs(architecture): 新增导入流水线文档

- [x] 3. Create `docs/architecture/03-storage.md`
  What to do / Must NOT do: Document StoragePolicy (`MANAGED` only in use; `EXTERNAL`/`OBJECT_STORAGE` future), MANAGED file layout using `{chapterId}` (NOT `{globalOrder}`), StorageService/LocalStorageService responsibilities, Page storage fields (`hq_root`/`hq_path`/`lq_root`/`lq_path`/`hq_status`/`lq_status`/`fileSize`/`lqSize`/`width`/`height`), and abstract URL generation. Must NOT present `metadata/` or `thumbs/` as `StorageRoot`s; only `HQ` and `LQ` are configured roots.
  Parallelization: Wave 1 | Blocked by: — | Blocks: 5
  References (executor has NO interview context - be exhaustive): `docs/superpowers/specs/2026-07-16-project-docs-update-phase1-design.md` §3.3; `AGENTS.md` STORAGE/URL 规范; `api-service/src/main/java/com/comicatlas/api/common/storage/DefaultStorageLayout.java:9`; `worker-service/src/main/java/com/comicatlas/worker/file/storage/StorageProperties.java`; `worker-service/src/main/resources/application.yml:47-54`; `api-service/src/main/java/com/comicatlas/api/comic/entity/Page.java`; `worker-service/src/main/java/com/comicatlas/worker/file/storage/LocalStorageService.java`.
  Acceptance criteria (agent-executable):
    - `Test-Path docs/architecture/03-storage.md` returns `$true`.
    - `Select-String -Path docs/architecture/03-storage.md -Pattern "\{globalOrder\}"` returns empty.
    - `Select-String -Path docs/architecture/03-storage.md -Pattern "\{chapterId\}"` returns at least one match.
    - `Select-String -Path docs/architecture/03-storage.md -Pattern "hqSize"` returns empty.
    - `Select-String -Path docs/architecture/03-storage.md -Pattern "StorageService|LocalStorageService"` returns at least one match.
  QA scenarios (name the exact tool + invocation): happy: layout uses chapterId and no banned terms; failure: banned term found triggers correction. Evidence `.omo/evidence/task-3-comicatlas-docs-refresh-phase1.md`.
  Commit: Y | docs(architecture): 新增存储模型文档

- [x] 4. Create `docs/architecture/adr/0001-unified-import-pipeline.md`
  What to do / Must NOT do: Write the ADR describing the problem, decision, rejected alternative (per-source services), and consequences. Must keep it focused on the design rationale.
  Parallelization: Wave 1 | Blocked by: — | Blocks: —
  References (executor has NO interview context - be exhaustive): `docs/superpowers/specs/2026-07-16-project-docs-update-phase1-design.md` §3.4; `docs/superpowers/specs/2026-07-01-ComicAtlas-core-architecture.md` §2; `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java`; `worker-service/src/main/java/com/comicatlas/worker/file/handler/DirectoryImportHandler.java`.
  Acceptance criteria (agent-executable):
    - `Test-Path docs/architecture/adr/0001-unified-import-pipeline.md` returns `$true`.
    - `Select-String -Path docs/architecture/adr/0001-unified-import-pipeline.md -Pattern "^## "` returns at least 4 sections (Problem / Decision / Alternatives / Consequences).
  QA scenarios (name the exact tool + invocation): happy: file exists with required sections; failure: missing section triggers re-write. Evidence `.omo/evidence/task-4-comicatlas-docs-refresh-phase1.md`.
  Commit: Y | docs(architecture): 新增统一导入流水线 ADR

- [x] 5. Update `docs/api.md`
  What to do / Must NOT do: Add/refresh import task endpoints (`POST /api/tasks/import`, `GET /api/tasks/import`, `GET /api/tasks/import/{id}`, `GET /api/tasks/import/{id}/status`, `POST /api/tasks/import/{id}/cancel`, `POST /api/tasks/import/{id}/retry`), document `/api/admin/storage/scan-recover`, document ImportTask state machine with actual enum values, and add cross-links to `docs/architecture/02-import-pipeline.md` and `03-storage.md`. Must NOT add `/api/tasks/scan`.
  Parallelization: Wave 2 | Blocked by: 2, 3 | Blocks: 7
  References (executor has NO interview context - be exhaustive): `docs/api.md` current content; `docs/superpowers/specs/2026-07-16-project-docs-update-phase1-design.md` §4; `api-service/src/main/java/com/comicatlas/api/importer/controller/ImportController.java`; `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminController.java`; `api-service/src/main/java/com/comicatlas/api/importer/dto/ImportRequest.java`; `api-service/src/main/java/com/comicatlas/api/importer/dto/ImportTaskVO.java`; `api-service/src/main/java/com/comicatlas/api/common/enums/ImportTaskStatus.java`.
  Acceptance criteria (agent-executable):
    - `Select-String -Path docs/api.md -Pattern "/api/tasks/scan"` returns empty.
    - `Select-String -Path docs/api.md -Pattern "/api/admin/storage/scan-recover"` returns at least one match.
    - `Select-String -Path docs/api.md -Pattern "02-import-pipeline"` returns at least one match.
    - `Select-String -Path docs/api.md -Pattern "03-storage"` returns at least one match.
    - `Select-String -Path docs/api.md -Pattern "IMPORTING|SUCCESS|FAILED|CANCELLED|PARSING|PENDING"` returns at least one match.
  QA scenarios (name the exact tool + invocation): happy: all required endpoints and cross-links present, banned endpoint absent; failure: missing link or banned endpoint triggers correction. Evidence `.omo/evidence/task-5-comicatlas-docs-refresh-phase1.md`.
  Commit: Y | docs(api): 同步导入、扫描恢复接口与状态机

- [x] 6. Create `docs/database/schema.md`
  What to do / Must NOT do: Create schema doc with ER diagram (ASCII/Mermaid) and field tables for `comic`, `catalog`, `chapter`, `page`, `import_task`. Include status/value enums sourced from actual Java enums: `ComicStatus`, `HqStatus`, `LqStatus`, `ImportTaskStatus`, `SourceType`. Note `CANCELLED`/`DOWNLOADING` as used string values not in enum. Must use actual field names from `schema.sql`/entities (e.g. Page has `fileSize` and `lqSize`, no `hqSize`).
  Parallelization: Wave 3 | Blocked by: — | Blocks: 7
  References (executor has NO interview context - be exhaustive): `api-service/src/main/resources/db/schema.sql`; `api-service/src/main/java/com/comicatlas/api/comic/entity/Comic.java`; `api-service/src/main/java/com/comicatlas/api/comic/entity/Catalog.java`; `api-service/src/main/java/com/comicatlas/api/comic/entity/Chapter.java`; `api-service/src/main/java/com/comicatlas/api/comic/entity/Page.java`; `api-service/src/main/java/com/comicatlas/api/importer/entity/ImportTask.java`; `api-service/src/main/java/com/comicatlas/api/common/enums/ComicStatus.java`; `api-service/src/main/java/com/comicatlas/api/common/enums/HqStatus.java`; `api-service/src/main/java/com/comicatlas/api/common/enums/LqStatus.java`; `api-service/src/main/java/com/comicatlas/api/common/enums/ImportTaskStatus.java`; `api-service/src/main/java/com/comicatlas/api/common/enums/SourceType.java`.
  Acceptance criteria (agent-executable):
    - `Test-Path docs/database/schema.md` returns `$true`.
    - `Select-String -Path docs/database/schema.md -Pattern "ComicStatus|HqStatus|LqStatus|ImportTaskStatus|SourceType"` returns at least one match.
    - `Select-String -Path docs/database/schema.md -Pattern "hqSize"` returns empty.
    - `Select-String -Path docs/database/schema.md -Pattern "catalog|chapter" -CaseSensitive` returns at least one match.
  QA scenarios (name the exact tool + invocation): happy: required enum sections present, banned field absent, catalog/chapter mentioned; failure: missing content triggers correction. Evidence `.omo/evidence/task-6-comicatlas-docs-refresh-phase1.md`.
  Commit: Y | docs(database): 新增数据库 schema 与状态枚举文档

- [x] 7. Final verification and fixes
  What to do / Must NOT do: Run all automated checks across produced docs and fix any failures. Must NOT expand scope to update other docs.
  Parallelization: Wave 4 | Blocked by: 5, 6 | Blocks: —
  References (executor has NO interview context - be exhaustive): All files produced in todos 1–6; `api-service/src/main/java/com/comicatlas/api/common/enums/*.java`; `api-service/src/main/resources/db/schema.sql`; `AGENTS.md`.
  Acceptance criteria (agent-executable):
    - All `Test-Path` checks for new docs return `$true`.
    - `grep -rni "HandlerFactory\|StorageManager" docs/architecture docs/api.md docs/database` returns empty.
    - `grep -rni "/api/tasks/scan" docs/api.md` returns empty.
    - `grep -rni "{globalOrder}" docs/architecture` returns empty.
    - `grep -rni "PROCESSING\|COMPLETED" docs/architecture docs/database` returns empty.
    - `Select-String -Path docs/api.md -Pattern "02-import-pipeline|03-storage"` returns at least 2 matches.
  QA scenarios (name the exact tool + invocation): happy: all grep checks pass; failure: any check fails triggers targeted fix. Evidence `.omo/evidence/task-7-comicatlas-docs-refresh-phase1.md`.
  Commit: Y | docs: 文档重构第一阶段最终校验与修正

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [x] F1. Plan compliance audit: Verify every todo produced its deliverable and acceptance criteria pass.
- [x] F2. Code/doc quality review: Run banned-term grep and required-section grep; no contradictions remain.
- [x] F3. Real manual QA: Open each new/updated doc in a markdown previewer and confirm headings, tables, and diagrams render correctly.
- [x] F4. Scope fidelity: Confirm only architecture/api/database docs were touched; no frontend/worker/reader/admin/README changes.

## Commit strategy
- One commit per architecture doc (1.1–1.4) for clear history.
- One commit for `docs/api.md` update.
- One commit for `docs/database/schema.md`.
- One final commit for verification fixes if any.
- All commit messages in Chinese per project convention.

## Success criteria
- All 4 architecture docs exist and contain required sections.
- `docs/api.md` documents import/scan endpoints, state machine, and links to architecture docs.
- `docs/database/schema.md` documents all 5 core tables and all status/value enums aligned with code.
- No banned terms (`HandlerFactory`, `StorageManager`, `/api/tasks/scan`, `{globalOrder}`, `PROCESSING`, `COMPLETED`, `hqSize`) appear in the new/updated docs.
- Cross-links from `docs/api.md` to architecture docs are present.
