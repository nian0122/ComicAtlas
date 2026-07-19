# refresh-metadata-phase2 - Work Plan

## TL;DR (For humans)

**What you'll get:** 编辑页「重建元数据」不再依赖旧 metadata.json——直接扫描 HQ 图片文件获取宽高/尺寸，与 DB 现有章节做增量同步（新增/更新/删除），完成后自动导出最新 metadata.json 快照。

**Why this approach:** DB 给结构（章节名/层级不丢），HQ 给图片数据（实时读取），page 层增量 CRUD 而非全量替换——并发安全、无旧快照依赖。

**What it will NOT do:** 不碰 catalog/chapter 结构，不重命名章节，不从 HQ 目录名推断元数据，不重构 Worker 的 DirectoryImportHandler——Phase 2 范围严格限定在 api-service。

**Effort:** Short
**Risk:** Low — 内部逻辑替换，API 端点不变，前端无感知
**Decisions to sanity-check:** export 在事务提交后执行（失败不回滚 DB），unlock 无条件覆盖状态（无超时），webp-imageio 需在 api-service 中验证

Your next move: 批准此 plan 或先运行 Momus 审查。完整执行细节见下方。

---

## Scope
### Must have
- **C1 MetadataExporter**: 新组件，从 DB 导出 catalog/chapter/page 到 `D:/manga/metadata/{comicId}.json`，JSON 结构与 Worker `writeMetadata()` 一致 (version=2)，parentIndex 由 parent_id 映射还原，imageName 从 hq_path 提取
- **C2 AdminServiceImpl.refreshMetadata() 重写**: 移除 metadata.json 输入依赖，从 DB 读 chapter 结构 + 扫 HQ 图片数据，page 层增量 CRUD（UPDATE/INSERT/DELETE），完成后调用 MetadataExporter.export()
- **C3 HQ 扫描**: 在 api-service 中实现 `getImageDimensions(Path)`（复用 javax.imageio.ImageReader），图片白名单 `.jpg/.jpeg/.png/.webp/.gif/.bmp`，按文件名自然排序，跳过隐藏文件

### Must NOT have (guardrails, anti-slop, scope boundaries)
- **C4 deferred**: 不重构 DirectoryImportHandler.writeMetadata()
- catalog/chapter 结构不创建/不删除/不重排——仅读取用于匹配 HQ 目录
- 不从 HQ 目录名推断章节标题/目录名
- 不修改前端代码（API 端点不变，响应格式不变）
- 不修改 ImportEventHandler、ImportServiceImpl、import pipeline
- 不在 refreshMetadata 中调用 replaceCatalogChapterPage()（该方法保留用于 rebuild/scanRecover）
- 不根据 HQ 新增 {globalOrder}/ 目录自动创建章节（已知限制 §7）

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after + curl integration tests
- Evidence: `.omo/evidence/task-<N>-refresh-metadata-phase2.log`
- Key scenarios: happy path (JPEG/PNG/WebP), idempotent double-refresh, corrupt image files, missing HQ directories, zero-byte files

## Execution strategy
### Parallel execution waves
- **Wave 1** (C1 + C3): MetadataExporter + HQ scan utilities — independent, 2 parallel agents
- **Wave 2** (C2): AdminServiceImpl.refreshMetadata() rewrite — 1 agent (depends on C1+C3)
- **Wave 3**: Integration verification — 1 agent (curl-based, requires running service)

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| T1 MetadataExporter | — | T4 | T2,T3 |
| T2 webp-imageio dependency | — | T3 | T1,T3 |
| T3 HQ scan utilities | T2 | T4 | T1,T2 |
| T4 refreshMetadata rewrite | T1,T3 | T5 | — |
| T5 Integration verification | T4 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [x] 1. Create MetadataExporter
  What to do: Create `api-service/src/main/java/com/comicatlas/api/admin/service/MetadataExporter.java`:
  - `@Component`, inject ComicMapper, CatalogMapper, ChapterMapper, PageMapper, ObjectMapper
  - `@Value("${MANGA_ROOT:D:/manga}") String mangaRoot`
  - `public Path export(Long comicId) throws IOException`:
    1. SELECT comic by id → not found throw RuntimeException
    2. SELECT all catalogs by comicId → List<Catalog>
    3. Build `id → index` map for parentIndex restoration
    4. SELECT all chapters by comicId, ordered by globalOrder
    5. For each chapter: SELECT pages by chapterId, ordered by pageNumber
    6. Extract imageName = hq_path last segment after '/'
    7. Assemble Map<String, Object> structure matching Worker writeMetadata() JSON format (version=2, comic/catalogs/chapters)
    8. Create dirs if needed, write via objectMapper to `Path.of(mangaRoot, "metadata", comicId + ".json")`
  Must NOT do: No dependency on ComicMetadataDTO or worker-service models — use raw Map/Object serialization (same as DirectoryImportHandler.writeMetadata()).
  Must NOT do: No parentIndex for root-level catalogs (parentIndex=null in JSON when parentId is null).
  Parallelization: Wave 1 | Blocked by: — | Blocks: T4
  References: spec §4.4, DirectoryImportHandler.writeMetadata() lines 66-114 for JSON format reference, Catalog.java for parentId/level/sortOrder fields
  Acceptance criteria:
  - File created at `api-service/src/main/java/com/comicatlas/api/admin/service/MetadataExporter.java`
  - `mvn compile -pl api-service -q` exits 0
  - Export produces valid JSON with keys: version, comic, catalogs, chapters
  - parentIndex correctly maps parent_id → list index (null for root)
  QA scenarios:
  - Happy: `mvn compile -pl api-service -q` exits 0
  - Failure: Missing mangaRoot dir → IOException caught by caller
  - Evidence: .omo/evidence/task-1-refresh-metadata-phase2.log
  Commit: Y | feat(admin): add MetadataExporter to export DB state to metadata.json

- [x] 2. Verify webp-imageio dependency in api-service
  What to do: Check `api-service/pom.xml` for `org.sejda.imageio:webp-imageio`. If missing, add:
  ```xml
  <dependency>
      <groupId>org.sejda.imageio</groupId>
      <artifactId>webp-imageio</artifactId>
      <version>0.1.6</version>
  </dependency>
  ```
  Must NOT do: No version range, no scope=provided.
  Parallelization: Wave 1 | Blocked by: — | Blocks: T3
  References: worker-service/pom.xml (existing dependency), MetadataAssembler.getImageDimensions() for ImageReader usage pattern
  Acceptance criteria: `mvn dependency:resolve -pl api-service -q` exits 0, dependency visible in classpath
  QA scenarios: Verify dependency resolves via `mvn dependency:resolve -pl api-service -q`
  Commit: Y | build(api-service): add webp-imageio dependency for WebP image dimensions

- [x] 3. Implement HQ scan utilities in AdminServiceImpl
  What to do: Add to `AdminServiceImpl.java`:
  - `private record ImageDimensions(Integer width, Integer height) {}`
  - `private ImageDimensions getImageDimensions(Path p)` — use javax.imageio.ImageIO.createImageInputStream + ImageReader.getWidth(0)/getHeight(0), dispose reader in finally
  - `private List<PageInfo> scanChapterPages(Long comicId, int globalOrder)` — list `hq/{comicId}/{globalOrder}/` directory, filter by extension (.jpg/.jpeg/.png/.webp/.gif/.bmp), natural sort by filename, skip hidden files (starts with '.'), return list of {imageName, fileSize, width, height}
  Must NOT do: No dependency on worker-service MetadataAssembler — independent implementation using javax.imageio standard API.
  Must do: `fileSize > 0 ? "READY" : "MISSING"` for hqStatus (consistent with MetadataAssembler pattern, GAP-7).
  Parallelization: Wave 1 | Blocked by: T2 | Blocks: T4
  References: spec §4.2, MetadataAssembler.getImageDimensions() lines 96-112 for ImageReader pattern, IMAGE_EXT constant for extension whitelist
  Acceptance criteria:
  - `mvn compile -pl api-service -q` exits 0
  - Methods correctly import javax.imageio (not java.awt.image)
  - Image dimensions extracted for JPEG/PNG/GIF/BMP without webp-imageio
  - Image dimensions extracted for WebP WITH webp-imageio (if dependency resolved)
  QA scenarios:
  - Happy: compile passes, method signatures visible
  - Failure: verify 0-byte file returns null dimensions (graceful degradation)
  Commit: Y | feat(admin): add HQ scan utilities for page dimensions

- [x] 4. Rewrite refreshMetadata() with page incremental CRUD
  What to do: Rewrite `AdminServiceImpl.refreshMetadata()`:
  - Keep CAS lock pattern (lines 492-509): READY → REFRESHING, 409 if failed
  - Keep `try { ... } finally { unlock }` (lines 512-542)
  - **Remove**: metadata.json read (lines 513-522), replaceCatalogChapterPage call (line 525), restoreComicInternal call (line 527)
  - **New logic within transactionTemplate.execute()** (ONE transaction for ALL DB changes, GAP-3):
    a. Load all chapters for comicId from DB (ordered by globalOrder)
    b. For each chapter:
       - Scan `hq/{comicId}/{chapter.globalOrder}/` via scanChapterPages()
       - Load existing DB pages for this chapter keyed by imageName
       - `nextPageNumber = max existing pageNumber + 1` (or 1 if empty)
       - For each HQ image: if DB has matching page → UPDATE page SET fileSize, width, height, hqStatus='READY' WHERE id=? (do NOT touch lqStatus, pageNumber, hqRoot, hqPath — GAP-6)
       - For each HQ image: if DB has no matching page → INSERT page with hqRoot='HQ', hqPath='{comicId}/{globalOrder}/{imageName}', hqStatus=size>0?'READY':'MISSING', lqStatus='NOT_GENERATED', fileSize, width, height, pageNumber=nextPageNumber++
       - For each DB page not in HQ → DELETE page WHERE id=?
       - UPDATE chapter.pageCount = count of actual pages after sync
    c. UPDATE comic.totalPages = sum of all chapter pageCounts, comic.fileSize = sum, comic.hqSize = sum
  - **After transaction commits**: call `metadataExporter.export(comicId)`. If export throws IOException → log.error, do NOT rollback DB (export is best-effort, GAP-3)
  - **Unlock**: in finally block — unconditional `SET status='READY'` (preserve Phase 1 behavior)
  Must NOT do:
  - Do NOT call replaceCatalogChapterPage() — it deletes catalog/chapter which contradicts Phase 2 (GAP-10)
  - Do NOT call restoreComic() or restoreComicInternal() — these handle full Replace, not incremental sync
  - Do NOT wrap export in the transaction — export failure must not rollback DB changes (GAP-3)
  Parallelization: Wave 2 | Blocked by: T1,T3 | Blocks: T5
  References: spec §3 data flow, spec §4.1-4.3, current refreshMetadata() lines 492-542, Page entity fields
  Acceptance criteria:
  - `mvn compile -pl api-service -q` exits 0
  - Catalog/chapter records unchanged after refresh (same count, same titles)
  - Page fileSize/width/height updated from HQ files
  - New HQ images → INSERT new pages
  - Removed HQ images → DELETE corresponding pages
  - chapter.pageCount updated, comic.totalPages updated
  - metadata.json regenerated at `D:/manga/metadata/{comicId}.json`
  - Comic status restored to READY after completion (even on failure)
  QA scenarios:
  - Happy: `curl -X POST http://localhost:8010/api/admin/comics/91/refresh-metadata` → 200, verify page count and dimensions
  - Idempotent: second curl → same 200, same counts
  - Failure: remove some HQ files, refresh → deleted pages, reduced count
  - Failure: corrupt image → width/height=null, hqStatus='MISSING'
  - Evidence: .omo/evidence/task-4-refresh-metadata-phase2.log
  Commit: Y | feat(admin): rewrite refreshMetadata with HQ-scan driven page incremental CRUD

- [ ] 5. Integration verification (awaiting service restart)
  What to do: With running API service + comic 91 (has HQ files):
  - `curl -X POST http://localhost:8010/api/admin/comics/91/refresh-metadata` → 200
  - Verify response has catalogs/chapters/pages/durationMs/refreshedAt
  - Verify page table in DB now has width/height values for WebP files
  - `curl` same again → 200 (idempotent)
  - Verify metadata.json regenerated at `D:/manga/metadata/91.json`
  - Verify catalog/chapter records unchanged (same IDs, same titles)
  Must NOT do: No manual DB checks beyond what curl assertions cover.
  Parallelization: Wave 3 | Blocked by: T4 | Blocks: —
  References: spec §3 data flow, spec §5 compatibility matrix
  Acceptance criteria: All scenarios pass with expected HTTP codes and data changes
  QA scenarios: Run all curl commands, capture output to .omo/evidence/task-5-refresh-metadata-phase2.log
  Commit: N | evidence only

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit: verify all 5 todos completed, each with evidence
- [ ] F2. Code quality review: compile check zero errors, no unused imports, no @SuppressWarnings
- [ ] F3. End-to-end agent QA: run T5 curl tests against running service with real comic data, verify width/height present, catalog/chapter unchanged
- [ ] F4. Scope fidelity: confirm C4 deferred, no frontend changes, no replaceCatalogChapterPage() call in refreshMetadata

## Commit strategy
- 5 commits, one per todo (T1-T4 committed, T5 evidence-only)
- Order: T1+T2+T3 (Wave 1, any order) → T4 → T5
- Each commit message in Chinese
- No force-push, no amend on shared history

## Success criteria
- `POST /api/admin/comics/{id}/refresh-metadata` returns 200 without metadata.json dependency
- Page width/height/ fileSize updated from HQ files (including WebP via webp-imageio)
- New/deleted pages synced: INSERT for new files, DELETE for missing files
- catalog/chapter structure unchanged after refresh
- metadata.json regenerated at `D:/manga/metadata/{comicId}.json` after refresh
- Phase 1 Phase 1 rebuildFromHq/scanRecover callers unaffected (replaceCatalogChapterPage preserved)
- metadata.json no longer required for refresh-metadata to succeed
