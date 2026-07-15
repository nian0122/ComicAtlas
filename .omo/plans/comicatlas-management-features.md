# ComicAtlas 管理功能综合实施计划

**对应 Spec**: `docs/superpowers/specs/2026-07-15-comicatlas-management-features-design.md`  
**实施顺序**: A0 → A1 → A2 → B → C → A3 → D  
**策略**: 后端优先，前端随后；每个 Phase 完成后验证再进入下一 Phase。

---

## 1. 总体策略

- **后端优先**：每个 Phase 先完成后端 API、Service、测试，再开始前端页面。
- **并行化机会**：A0 之后，A1/A2 后端可串行；A1 后端完成后前端可立即跟上。A2 后端和 A1 前端理论上可部分并行，但考虑到 `ComicEditPage` 会复用 A2 的标签组件，建议 A1 前端先独立完成基础表单，A2 前端再加入标签区块。
- **Subagent 分工**：
  - 每个后端 Phase 分配一个 `quick` 或 `unspecified-low` subagent。
  - 每个前端 Phase 分配一个 `visual-engineering` subagent。
  - Playwright E2E 测试分配一个 `unspecified-high` subagent（可加载 `playwright-skill`）。
  - 最终集成验证由 orchestrator 亲自执行。
- **验收门槛**：每个 Phase 必须通过后端测试 + 前端构建 + Playwright 相关场景，才能进入下一 Phase。

---

## 2. Phase A0：基础检查

**目标**：确认现有数据库、实体、Mapper、Service、前端 store 的现状，输出状态报告。

### A0.1 数据库检查
- [ ] 检查 `tag` 表结构（id, name[, type]）。
- [ ] 检查 `comic_tag` 表结构（comic_id, tag_id）。
- [ ] 检查 `comic.category` 字段。
- [ ] 检查 `comic.cover_path` 字段。

### A0.2 后端检查
- [ ] 检查 `Tag` 实体、`TagMapper`。
- [ ] 检查 `ComicTag` 实体、`ComicTagMapper`。
- [ ] 检查 `ComicService` / `ComicServiceImpl` 结构。
- [ ] 检查 `ComicController` 现有方法。
- [ ] 检查异常类（`BusinessException`）。

### A0.3 前端检查
- [ ] 检查 `tagApi` 是否已存在。
- [ ] 检查 `ComicDetailPage` 是否已展示 tags。
- [ ] 检查路由是否支持新增 `/comics/:id/edit`。

### A0.4 输出
- [ ] 写入 `.omo/plans/comicatlas-management-features-a0-report.md`。

**Subagent**: `explore` 或 `quick`，只读检查。

---

## 3. Phase A1：漫画元数据编辑

### A1.1 后端

**Files to create/modify**:
- `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicMetadataDTO.java` (new)
- `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicMetadataUpdateDTO.java` (new)
- `api-service/src/main/java/com/comicatlas/api/comic/service/ComicService.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/comic/controller/ComicController.java` (modify)
- `api-service/src/test/java/com/comicatlas/api/comic/controller/ComicMetadataControllerTest.java` (new)
- `api-service/src/test/java/com/comicatlas/api/comic/service/impl/ComicMetadataServiceTest.java` (new)

**Tasks**:
1. Create `ComicMetadataDTO` with `title`, `author`.
2. Create `ComicMetadataUpdateDTO` with Bean Validation.
3. Add `getMetadata(Long id)` and `updateMetadata(Long id, dto)` to `ComicService` / `ComicServiceImpl`.
4. Add endpoints `GET /api/comics/{id}/metadata` and `PUT /api/comics/{id}/metadata` to `ComicController`.
5. Write controller and service tests.

**Verification**:
- `mvn -pl api-service test` passes.

### A1.2 前端

**Files to create/modify**:
- `frontend/src/types/index.ts` (modify)
- `frontend/src/services/api.ts` (modify)
- `frontend/src/router/index.ts` (modify)
- `frontend/src/pages/ComicEditPage.vue` (new)
- `frontend/src/pages/ComicDetailPage.vue` (modify)
- `frontend/e2e/comic-metadata-edit.spec.cjs` (new)

**Tasks**:
1. Add `ComicMetadataDTO` / `ComicMetadataUpdateDTO` types.
2. Extend `comicApi` with `getMetadata` and `updateMetadata`.
3. Add `/comics/:id/edit` route.
4. Create `ComicEditPage.vue` with title/author form and save/cancel actions.
5. Add "编辑信息" button to `ComicDetailPage`.
6. Write Playwright E2E test.

**Verification**:
- `npm run build` passes.
- `npx vue-tsc --noEmit` passes.
- Playwright test passes.

**Subagent**: Backend `quick`, Frontend `visual-engineering`.

---

## 4. Phase A2：手动标签管理

### A2.1 后端

**Files to create/modify**:
- `api-service/src/main/java/com/comicatlas/api/comic/dto/TagDTO.java` (new)
- `api-service/src/main/java/com/comicatlas/api/comic/service/TagService.java` (new interface)
- `api-service/src/main/java/com/comicatlas/api/comic/service/impl/TagServiceImpl.java` (new)
- `api-service/src/main/java/com/comicatlas/api/comic/controller/TagController.java` (new)
- `api-service/src/main/java/com/comicatlas/api/comic/service/ComicService.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/comic/controller/ComicController.java` (modify)
- Tests for TagController, TagService, Comic tag binding.

**Tasks**:
1. Create `TagDTO`.
2. Create `TagService` / `TagServiceImpl` with list/create/delete.
3. Create `TagController` with `GET /api/tags`, `POST /api/tags`, `DELETE /api/tags/{id}`.
4. Add `getTags(Long comicId)` and `updateTags(Long comicId, tagIds)` to `ComicService` / `ComicServiceImpl`.
5. Add `GET /api/comics/{id}/tags` and `PUT /api/comics/{id}/tags` to `ComicController`.
6. Write tests.

**Verification**:
- `mvn -pl api-service test` passes.

### A2.2 前端

**Files to create/modify**:
- `frontend/src/types/index.ts` (modify)
- `frontend/src/services/api.ts` (modify)
- `frontend/src/pages/ComicEditPage.vue` (modify)
- Tests.

**Tasks**:
1. Add `TagDTO`, `TagCreateDTO`, `ComicTagUpdateDTO` types.
2. Extend `tagApi` and `comicApi`.
3. Add tag selector block to `ComicEditPage`:
   - Display current tags.
   - Search/add existing tags.
   - Create new tag inline.
   - Remove tag.
4. Write Playwright E2E test.

**Verification**:
- Build, type check, Playwright pass.

**Subagent**: Backend `quick`, Frontend `visual-engineering`.

---

## 5. Phase B：搜索增强

### B.1 后端

**Files to create/modify**:
- `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicListQuery.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicMapper.java` (possibly custom XML)
- Tests.

**Tasks**:
1. Extend `ComicListQuery` with `tags` and `tagMode`.
2. Implement multi-tag search using subquery (AND with HAVING COUNT, OR with EXISTS).
3. Extend `keyword` search to `title`, `author`, `tag.name` (and `description` if A3 done).
4. Write tests.

**Verification**:
- `mvn -pl api-service test` passes.

### B.2 前端

**Files to create/modify**:
- `frontend/src/stores/comic-store.ts` (modify)
- `frontend/src/pages/ComicListPage.vue` (modify)
- Tests.

**Tasks**:
1. Add tag multi-select filter to `ComicListPage`.
2. Add `tagMode` toggle (AND/OR).
3. Update search hints.
4. Write Playwright E2E test.

**Verification**:
- Build, type check, Playwright pass.

**Subagent**: Backend `quick`, Frontend `visual-engineering`.

---

## 6. Phase C：封面管理

### C.1 后端

**Files to create/modify**:
- `api-service/src/main/java/com/comicatlas/api/comic/dto/CoverUpdateDTO.java` (new)
- `api-service/src/main/java/com/comicatlas/api/comic/service/ComicService.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/comic/controller/ComicController.java` (modify)
- Tests.

**Tasks**:
1. Create `CoverUpdateDTO` with `pageId` (reserve `crop` for future).
2. Add `updateCover(Long comicId, dto)` to `ComicService`.
3. Add `PUT /api/comics/{id}/cover`.
4. Optional: generate thumb cover image.
5. Write tests.

**Verification**:
- `mvn -pl api-service test` passes.

### C.2 前端

**Files to create/modify**:
- `frontend/src/pages/ComicEditPage.vue` (modify)
- `frontend/src/services/api.ts` (modify)
- `frontend/src/types/index.ts` (modify)
- Tests.

**Tasks**:
1. Add cover block to `ComicEditPage`.
2. Create cover picker dialog with paginated page thumbnails.
3. Update `comicApi` with `updateCover`.
4. Write Playwright E2E test.

**Verification**:
- Build, type check, Playwright pass.

**Subagent**: Backend `quick`, Frontend `visual-engineering`.

---

## 7. Phase A3：扩展元数据

### A3.1 后端

**Files to create/modify**:
- Flyway / manual migration script.
- `ComicMetadataDTO`, `ComicMetadataUpdateDTO` (modify).
- `ComicServiceImpl` (modify).
- `Comic` entity (modify).
- Tests.

**Tasks**:
1. Add `description` (TEXT) and `title_jpn` (VARCHAR) columns.
2. Update DTOs and entity.
3. Update update logic.
4. Write tests.

### A3.2 前端

**Files to create/modify**:
- `ComicEditPage.vue` (modify)
- `ComicDetailPage.vue` (modify)
- Types / API.
- Tests.

**Tasks**:
1. Add `titleJpn` and `description` fields to edit form.
2. Display description on detail page (collapsible).
3. Write Playwright test.

**Subagent**: Backend `quick`, Frontend `visual-engineering`.

---

## 8. Phase D：存储扫描恢复

### D.1 后端

**Files to create/modify**:
- `api-service/src/main/java/com/comicatlas/api/admin/dto/ScanRecoverResultDTO.java` (new)
- `api-service/src/main/java/com/comicatlas/api/admin/service/AdminService.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/admin/service/impl/AdminServiceImpl.java` (modify)
- `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminController.java` (modify)
- Tests.

**Tasks**:
1. Define `ScanRecoverResultDTO`.
2. Implement scan logic:
   - Scan HQ root for `{comicId}/{chapterId}/*.jpg`.
   - Reconcile with DB.
   - Create full comic if metadata.json exists.
   - Create `PLACEHOLDER` comic if no metadata.json, isolated from normal listing.
3. Add `POST /api/admin/storage/scan-recover`.
4. Write tests.

### D.2 前端

**Files to create/modify**:
- `frontend/src/services/api.ts` (modify)
- `frontend/src/pages/DashboardPage.vue` (modify)
- Tests.

**Tasks**:
1. Add Dashboard "存储扫描恢复" section.
2. Show result stats and placeholder list.
3. Allow jump to placeholder edit or delete.
4. Write Playwright test.

**Subagent**: Backend `unspecified-high` (more complex), Frontend `visual-engineering`.

---

## 9. 集成与最终验证

### F1. 全量测试
- [ ] `mvn -pl api-service test` 全绿。
- [ ] `cd frontend && npm run build` 成功。
- [ ] `cd frontend && npx vue-tsc --noEmit` 无错误。

### F2. E2E 测试
- [ ] 所有新增 Playwright 测试通过。
- [ ] 核心用户旅程：导入 → 编辑元数据 → 加标签 → 搜索 → 换封面 → 查看详情。

### F3. 代码审查
- [ ] 无 `as any`、无 `@ts-ignore`、无空 catch。
- [ ] 无 oversized 组件（ComicEditPage 超过 400 行需拆分）。
- [ ] 后端无 file deletion 等越界操作。

### F4. 文档更新
- [ ] 更新 `docs/api.md` 或相关 API 文档。
- [ ] 更新 `.omo/boulder.json` 状态。

---

## 10. Subagent 分配概览

| Phase | Backend Subagent | Frontend Subagent | E2E Subagent |
|-------|------------------|-------------------|--------------|
| A0 | explore/quick | — | — |
| A1 | quick | visual-engineering | unspecified-high |
| A2 | quick | visual-engineering | unspecified-high |
| B | quick | visual-engineering | unspecified-high |
| C | quick | visual-engineering | unspecified-high |
| A3 | quick | visual-engineering | unspecified-high |
| D | unspecified-high | visual-engineering | unspecified-high |

---

## 11. 风险与缓冲

1. **A0 发现不一致**：如果 `tag`/`comic_tag` 表结构与假设不符，需要调整后续设计。缓冲 1 轮反馈。
2. **A2 标签唯一性**：中文标签同名判断可能引发争议，第一版按字符串相等处理，保留调整空间。
3. **B 搜索分页**：多标签子查询与 MyBatis Plus 分页 count 结合可能需调试，预留 1 轮测试修复。
4. **D 扫描恢复风险高**：放在最后，且 PLACEHOLDER 隔离设计降低污染风险。

---

## 12. 下一步

Orchestrator 确认本计划后，按 Phase A0 → A1 → A2 → B → C → A3 → D 顺序启动 subagent 执行。
