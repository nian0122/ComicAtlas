# refresh-metadata-api - Work Plan

## TL;DR (For humans)

**What you'll get:** 编辑页「重建元数据」按钮现在只重建当前选中漫画，不再是全局恢复。点击后从 metadata.json 刷新目录/章节/页面，comic.id 不变，阅读历史/标签/分类不丢。

**Why this approach:** 新增 RestorePolicy 策略模式区分「导入时全量覆盖」和「编辑页仅刷新解析数据」，在现有 restoreComic 逻辑上加一层策略——title/author/category 默认保留用户编辑值，目录/章节/页面执行替换。

**What it will NOT do:** 不生成封面，不支持 HQ Package 恢复，不异步执行，不提供选择性覆盖选项——这些都留给 Phase 2。

**Effort:** Short
**Risk:** Low — 纯增量 API，现有调用方零改动
**Decisions to sanity-check:** CAS 锁释放只能用 LambdaUpdateWrapper set status，不能用 updateById；RestorePolicy 放 common 包而非 admin 包。

Your next move: 批准此 plan 或先运行 Momus 审查。完整执行细节见下方。

---

> TL;DR (machine): Short, Low, 12-todo 6-wave REST endpoint for single-comic metadata refresh from metadata.json

## Scope
### Must have
- P1-P7 from spec §2: metadata.json source, comic.id preserved, user fields preserved, Replace semantics, RestorePolicy/Context orthogonal, idempotent, concurrent-safe
- REST endpoint `POST /api/admin/comics/{comicId}/refresh-metadata` returning 200 with 7-field JSON
- 6 failure scenarios from spec §3 (404/409×2/422×2/500)
- Field recovery matrix from spec §5: title/author/category/tags/cover preserved; catalog/chapter/page replaced; pageCount/fileSize/hqSize written
- CAS lock: LambdaUpdateWrapper acquire on READY, release status-only in finally
- restoreComic old signature preserved as compatibility wrapper
- Frontend: `adminApi.refreshMetadata(id)` in api.ts + edit page button

### Must NOT have (guardrails, anti-slop, scope boundaries)
- Phase 2: HQ Package support, async task model, selective field override — all deferred per spec §8
- No changes to ImportEventHandler, ImportServiceImpl, or import pipeline
- No changes to rebuildFromHq/scanRecover callers (old restoreComic signature preserved)
- No Redis lock — DB CAS only
- No new DB migrations or schema changes

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after + curl integration tests (no JUnit — plan is curl-verified)
- Evidence: `.omo/evidence/task-<N>-refresh-metadata-api.log`
- Each todo QA: curl + JSON path assertions (happy path) + error scenario (failure path)

## Execution strategy
### Parallel execution waves
- **Wave 1** (C1): 5 model files — all independent, spawn 1 agent
- **Wave 2** (C2 + C4): AdminService interface + restoreComic refactor — 2 parallel agents
- **Wave 3** (C3): refreshMetadata + helpers — 1 agent (depends on C1+C2+C4)
- **Wave 4** (C5): Controller endpoint — 1 agent (depends on C3)
- **Wave 5** (C6): Frontend — 2 parallel agents (API + button)
- **Wave 6**: Integration verification — 1 agent

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| T1 RestorePolicy | — | T3,T7 | T2,T4,T5 |
| T2 RestoreSource | — | T3,T7 | T1,T4,T5 |
| T3 RestoreContext | T1,T2 | T7,T8 | — |
| T4 RefreshMetadataResult | — | T8,T9 | T1,T2,T5 |
| T5 ComicStatus.REFRESHING | — | T7,T8 | T1,T2,T4 |
| T6 AdminService interface | T4 | T8 | T7 |
| T7 restoreComic refactor | T1,T2,T3,T5 | T8 | T6 |
| T8 refreshMetadata+helpers | T3,T4,T5,T6,T7 | T9 | — |
| T9 Controller endpoint | T4,T8 | T10,T11 | — |
| T10 Frontend API | T9 | — | T11 |
| T11 Frontend button | T9 | — | T10 |
| T12 Integration test | T9,T10,T11 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [x] 1. Create RestorePolicy enum
  What to do: Create `api-service/src/main/java/com/comicatlas/api/common/RestorePolicy.java` with IMPORT + REFRESH_METADATA values per spec §4.1. Each value documented with Javadoc.
  Must NOT do: No boolean flags, no numeric codes.
  Parallelization: Wave 1 | Blocked by: — | Blocks: T3,T7
  References: spec §4.1 (lines 73-86), existing ComicStatus.java for enum style
  Acceptance criteria: File exists, compiles, two enum values
  QA scenarios: `mvn compile -pl api-service -q` exits 0
  Commit: Y | feat(common): add RestorePolicy enum for metadata refresh

- [x] 2. Create RestoreSource enum
  What to do: Create `api-service/src/main/java/com/comicatlas/api/common/RestoreSource.java` with METADATA, HQ_PACKAGE, IMPORT, SCAN per spec §4.2.
  Must NOT do: No default value behavior in enum.
  Parallelization: Wave 1 | Blocked by: — | Blocks: T3,T7
  References: spec §4.2 (lines 89-97)
  Acceptance criteria: File exists, compiles, four enum values
  QA scenarios: `mvn compile -pl api-service -q` exits 0
  Commit: Y | feat(common): add RestoreSource enum

- [x] 3. Create RestoreContext record
  What to do: Create `api-service/src/main/java/com/comicatlas/api/common/RestoreContext.java` with fields (Long comicId, boolean comicExists, RestorePolicy policy, RestoreSource source) per spec §4.3.
  Must NOT do: No default constructor, no setters.
  Parallelization: Wave 1 | Blocked by: T1,T2 | Blocks: T7,T8
  References: spec §4.3 (lines 100-108)
  Acceptance criteria: Record compiles, all four fields accessible
  QA scenarios: `mvn compile -pl api-service -q` exits 0
  Commit: Y | feat(common): add RestoreContext record

- [x] 4. Create RefreshMetadataResult record
  What to do: Create `api-service/src/main/java/com/comicatlas/api/admin/dto/RefreshMetadataResult.java` with fields per spec §4.4: Long comicId, String status, int catalogs, int chapters, int pages, long durationMs, LocalDateTime refreshedAt.
  Must NOT do: No custom serialization, no business logic.
  Parallelization: Wave 1 | Blocked by: — | Blocks: T8,T9
  References: spec §4.4 (lines 111-122), existing ComicDeleteStats.java for DTO style
  Acceptance criteria: Record compiles, all fields accessible, JSON serializable by ObjectMapper
  QA scenarios: `mvn compile -pl api-service -q` exits 0
  Commit: Y | feat(admin): add RefreshMetadataResult DTO

- [x] 5. Add REFRESHING to ComicStatus enum
  What to do: Add `REFRESHING` after `READY` in `api-service/src/main/java/com/comicatlas/api/common/enums/ComicStatus.java` per spec §4.5. Verify EnumTypeHandlers.ComicStatusHandler auto-compatible via `Enum.valueOf`.
  Must NOT do: No DB migration, no TypeHandler changes (safeValueOf handles new values).
  Parallelization: Wave 1 | Blocked by: — | Blocks: T7,T8
  References: spec §4.5 (lines 125-137), existing ComicStatus.java:3, EnumTypeHandlers.java:28-33
  Acceptance criteria: Enum compiles, `ComicStatus.valueOf("REFRESHING")` returns the constant
  QA scenarios: `mvn compile -pl api-service -q` exits 0
  Commit: Y | feat(common): add REFRESHING to ComicStatus enum

- [x] 6. Add refreshMetadata to AdminService interface
  What to do: Add `RefreshMetadataResult refreshMetadata(Long comicId)` method signature to `api-service/src/main/java/com/comicatlas/api/admin/service/AdminService.java`. Import RefreshMetadataResult.
  Must NOT do: No default method, no extra params.
  Parallelization: Wave 2 | Blocked by: — | Blocks: T8
  References: spec §6.1, AdminService.java (current 14 lines)
  Acceptance criteria: Interface compiles, method visible in AdminServiceImpl
  QA scenarios: `mvn compile -pl api-service -q` exits 0
  Commit: Y | feat(admin): add refreshMetadata to AdminService interface

- [x] 7. Refactor restoreComic/restoreComicInternal to RestoreContext
  What to do: In `api-service/src/main/java/com/comicatlas/api/admin/service/impl/AdminServiceImpl.java`:
  - Keep old `restoreComic(Map metadata, Long comicId)` as compatibility wrapper delegating to `restoreComic(metadata, new RestoreContext(comicId, false, IMPORT, METADATA))` per spec §6.2 lines 197-200
  - New `restoreComic(Map metadata, RestoreContext ctx)` wraps TransactionTemplate
  - Refactor `restoreComicInternal` from `(Map, Long)` to `(Map, RestoreContext)`: ctx.comicExists() → INSERT vs UPDATE comic; ctx.policy() → skip title/author/category when REFRESH_METADATA
  - Return `Map.of("catalogs", catalogCount, "chapters", chCount, "pages", pgCount)` — add catalog count
  Must NOT do: Change existing callers (rebuildFromHq, scanRecover) — they call old signature.
  Must NOT do: Change insertCatalogsWithHierarchy — it already works correctly.
  Parallelization: Wave 2 | Blocked by: T1,T2,T3,T5 | Blocks: T8
  References: spec §6.2 (lines 193-215), spec §5 field matrix, current restoreComicInternal (lines 299-368)
  Acceptance criteria:
  - `restoreComic(metadata, 42L)` still compiles and calls old path
  - `restoreComic(metadata, new RestoreContext(42L, true, REFRESH_METADATA, METADATA))` skips title/author/category writes
  - `restoreComic(metadata, new RestoreContext(42L, false, IMPORT, METADATA))` writes all fields (INSERT)
  QA scenarios:
  - Happy: `mvn compile -pl api-service -q` exits 0, no compilation errors in callers
  - Failure: Verify old signature callers (rebuildFromHq/scanRecover) still resolve correctly
  Commit: Y | refactor(admin): restoreComic accepts RestoreContext with policy-driven field writes

- [x] 8. Implement refreshMetadata + replaceCatalogChapterPage + buildResult
  What to do: In `AdminServiceImpl.java`:
  - `refreshMetadata(Long comicId)`: per spec §6.1 flow — selectById check → CAS lock (LambdaUpdateWrapper eq READY, set REFRESHING) → try: read/validate metadata.json → transactionTemplate.execute(delete catalog/chapter/page + restoreComicInternal) → buildResult → finally: unlock (LambdaUpdateWrapper set READY only, NOT updateById)
  - `replaceCatalogChapterPage(Long comicId)`: DELETE page WHERE chapter_id IN (SELECT id FROM chapter WHERE comic_id=?) → DELETE chapter WHERE comic_id=? → DELETE catalog WHERE comic_id=? (in order, within transaction)
  - `buildResult(Long comicId, Map stats, long durationMs)`: construct RefreshMetadataResult with LocalDateTime.now()
  Must NOT do: Lock release must NOT use updateById(comic) — only LambdaUpdateWrapper.set(ComicStatus.READY). Metadata path: `Path.of(mangaRoot, "metadata", comicId + ".json")`.
  Must NOT do: Transaction must wrap BOTH delete and restoreComicInternal — single atomic unit.
  Parallelization: Wave 3 | Blocked by: T3,T4,T5,T6,T7 | Blocks: T9
  References: spec §6.1 (lines 161-189), spec §6.3 (lines 218-236), spec §3 failure responses, mangaRoot @Value
  Acceptance criteria:
  - 200 response when comic READY + metadata.json exists
  - 404 when comic not found
  - 409 when status ≠ READY
  - 409 when CAS lock fails (concurrent refresh)
  - 422 when metadata.json missing
  - 422 when metadata.json corrupt JSON
  - 500 when restoreComicInternal throws
  - title/author/category unchanged after refresh (REFRESH_METADATA policy)
  - catalog/chapter/page replaced (old IDs gone, new auto-increment IDs generated)
  - lock released to READY in finally even when exception thrown
  QA scenarios:
  - Happy: curl POST /api/admin/comics/{id}/refresh-metadata → 200, verify JSON shape has 7 fields
  - Failure: curl with non-existent comicId → 404
  - Failure: curl with comic not READY → 409
  - Failure: delete metadata.json, curl → 422
  - Failure: corrupt metadata.json, curl → 422
  - Evidence: .omo/evidence/task-8-refresh-metadata-api.log
  Commit: Y | feat(admin): implement single-comic metadata refresh with CAS lock

- [x] 9. Add refresh-metadata endpoint to AdminController
  What to do: In `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminController.java`:
  ```java
  @PostMapping("/comics/{comicId}/refresh-metadata")
  public Result<RefreshMetadataResult> refreshMetadata(@PathVariable Long comicId) {
      return Result.success(adminService.refreshMetadata(comicId));
  }
  ```
  Must NOT do: No business logic in Controller. Exception handling via existing GlobalExceptionHandler.
  Parallelization: Wave 4 | Blocked by: T4,T8 | Blocks: T10,T11
  References: spec §6.4 (lines 239-247), AdminController.java (current 39 lines)
  Acceptance criteria: Endpoint compiles, route registered, returns Result-wrapped response
  QA scenarios: `mvn compile -pl api-service -q` exits 0; verify route via Spring Boot startup log
  Commit: Y | feat(admin): add POST /comics/{id}/refresh-metadata endpoint

- [x] 10. Add refreshMetadata to frontend API service
  What to do: In `frontend/src/services/api.ts`, add `refreshMetadata: (id: number) => api.post(\`/admin/comics/${id}/refresh-metadata\`)` to `adminApi` object.
  Must NOT do: No error handling beyond existing interceptor (already unwraps data).
  Parallelization: Wave 5 | Blocked by: T9 | Blocks: T12
  References: frontend/src/services/api.ts:80-84 (existing adminApi), spec §7
  Acceptance criteria: Function callable, returns Promise, URL matches endpoint
  QA scenarios: Verify TypeScript compilation; curl manual endpoint call
  Commit: Y | feat(frontend): add refreshMetadata to adminApi

- [x] 11. Update edit page button to call refreshMetadata
  What to do: In edit page (ComicEditPage.vue or similar), change "重建元数据" button from calling `adminApi.rebuild()` to `adminApi.refreshMetadata(comicId)`. On success, show ElMessage success with counts; on error, show error message from response.
  Must NOT do: No alert(), no console.log fallback for errors.
  Parallelization: Wave 5 | Blocked by: T9 | Blocks: T12
  References: spec §7, existing adminApi.rebuild() call site, ElMessage pattern from other pages
  Acceptance criteria: Button click → POST to new endpoint → success toast with stats → page data unchanged (comic.id preserved)
  QA scenarios:
  - Happy: click button, verify 200 response, toast appears
  - Failure: delete metadata.json, click button, verify 422 error toast
  Commit: Y | feat(frontend): wire edit page rebuild button to refreshMetadata API

- [x] 12. Integration verification
  What to do: With running API service + ready comic (has metadata.json):
  - curl POST /api/admin/comics/{id}/refresh-metadata → verify 200, 7 fields present, durationMs > 0
  - curl same again → verify 200 (idempotent)
  - Verify comic.title unchanged after refresh
  - Verify catalog/chapter/page IDs changed (old vs new auto-increment IDs)
  - Verify with missing metadata → 422
  - Verify with non-ready comic → 409
  Must NOT do: No manual DB checks beyond what curl assertions cover.
  Parallelization: Wave 6 | Blocked by: T9,T10,T11 | Blocks: —
  References: spec §3 success/failure responses, spec §5 field matrix
  Acceptance criteria: All 6 scenarios pass with expected HTTP codes and JSON shapes
  QA scenarios: Run all curl commands, capture output to .omo/evidence/task-12-refresh-metadata-api.log
  Commit: N | evidence only

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit: verify all 12 todos completed, each with evidence
- [ ] F2. Code quality review: compile check zero errors, no @SuppressWarnings added, no unused imports
- [ ] F3. Real manual QA: run T12 curl tests against running service with real comic data
- [ ] F4. Scope fidelity: confirm no Phase 2 creep (no HQ Package, no async, no selective override)

## Commit strategy
- 12 commits, one per todo (T1-T11 committed, T12 evidence-only)
- Order: T1→T2→T3→T4→T5 (data models, any order) → T6+T7 (parallel) → T8 → T9 → T10+T11 (parallel) → T12
- Each commit message in Chinese (project convention)
- No force-push, no amend on shared history

## Success criteria
- `POST /api/admin/comics/{id}/refresh-metadata` returns 200 with RefreshMetadataResult JSON
- Comic title/author/category/tags unchanged after refresh
- Catalog/chapter/page replaced with fresh data from metadata.json
- Concurrent refresh returns 409 (CAS lock)
- Missing/corrupt metadata returns 422
- Existing rebuildFromHq/scanRecover callers unaffected
- Frontend edit page button calls new API, shows success/error toast
