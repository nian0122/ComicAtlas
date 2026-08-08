# 接口固定与前端对接实施计划

**状态**: 历史归档

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 前端全部存储操作迁移到 `/api/storage/*` 统一端点，后端删除全部旧端点，接口最终固定为存储域统一形态（含导出 download/open 新端点）。

**Architecture:** 先补后端缺口（导出 download/open 新端点）→ 前端服务层与调用方迁移（api.ts / storage.ts / 3 个 vue）→ 后端删除旧端点（LqController、HqDeleteController、AdminController 旧方法、AdminStorageController.transcode-videos、ExportController 全部）→ 文档与全量验证。

**Tech Stack:** Vue3/TypeScript、Spring Boot 3、axios。

**Spec:** `docs/superpowers/specs/2026-08-06-api-convergence-modularization-design.md`（§7 兼容迁移策略与"后续"节）

## Global Constraints

- 前端类型用 `readonly` 标注；注释、提交信息用中文
- 后端新端点已存在（Task 1 仅补 download/open 两个）；旧端点一律删除（用户决策：前端迁移后删除）
- `adminApi.storageComics/storageChapters`（存储查询 `/admin/storage/comics*`）**不在**收敛范围——后端无新端点、未标 deprecated，前端保持不动
- 后端 `AdminService.getStorageStats` / `StorageQueryService` 等 Service 保留（新端点继续用）；仅删旧 Controller 方法与旧 Controller
- 删除旧 Controller 时同步清理不再被引用的 DTO/Service（如 `LqService`/`HqDeleteService` 已无注入方——Task 3 接口收敛时就已成孤儿 Bean）
- 门禁：后端 `.\mvnw clean verify`（Checkstyle 0）+ 前端 `pnpm build` + 全仓 grep 旧端点零残留
- PowerShell 环境；Maven `-D` 参数需引号包裹

---

### Task 1: 后端补导出 download/open 新端点

`StorageOperationController` 缺导出下载与打开目录两个端点（Task 6 时刻意保留在旧 ExportController）。本次补齐，供前端迁移使用。

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/storage/controller/StorageOperationController.java`

**Interfaces:**
- Consumes: `ExportOperationService.getTask(Long) → ExportTaskVO`（含 `physicalPath`）、`ExportService.getTask(Long)`（下载/打开需物理路径）
- Produces: `GET /api/storage/export/tasks/{taskId}/download`（StreamingResponseBody 流式下载）、`POST /api/storage/export/tasks/{taskId}/open`（Desktop 打开目录）

- [ ] **Step 1: 写失败测试**

在 `api-service/src/test/java/com/comicatlas/api/storage/controller/StorageOperationControllerTest.java` 追加：
```java
    @Test
    void downloadExport_返回文件流() throws Exception {
        ExportTaskVO vo = new ExportTaskVO();
        vo.setId(1L);
        vo.setPhysicalPath(System.getProperty("java.io.tmpdir").replace("\\", "/") + "/export-test.zip");
        try (var f = java.nio.file.Files.createTempFile("export-test", ".zip")) {
            vo.setPhysicalPath(f.toString().replace("\\", "/"));
            java.nio.file.Files.writeString(f, "hello");
            when(exportOperationService.getTask(1L)).thenReturn(vo);
            mvc.perform(get("/api/storage/export/tasks/1/download"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
        }
    }

    @Test
    void openDirExport_文件不存在返回404() throws Exception {
        ExportTaskVO vo = new ExportTaskVO();
        vo.setId(1L);
        vo.setPhysicalPath("/nonexistent/dir");
        when(exportOperationService.getTask(1L)).thenReturn(vo);
        mvc.perform(post("/api/storage/export/tasks/1/open"))
                .andExpect(status().isNotFound());
    }
```
> 测试需注入 `ExportOperationService` mock 到 `StorageOperationControllerTest` 的构造器（新增 mock 参数）；`get`/`post` import 已存在或补充。

- [ ] **Step 2: 运行确认失败**

```bash
.\mvnw -pl api-service -am "-Dtest=StorageOperationControllerTest" test "-Dsurefire.failIfNoSpecifiedTests=false"
```
Expected: 编译失败（端点不存在）或测试失败。

- [ ] **Step 3: 实现两个端点**

`StorageOperationController.java` 在导出区段（L101 `getExportTask` 之后）追加：
```java
    /**
     * 下载导出文件（流式）。
     */
    @GetMapping("/export/tasks/{taskId}/download")
    public ResponseEntity<StreamingResponseBody> downloadExport(@PathVariable Long taskId) {
        ExportTaskVO task = exportOperationService.getTask(taskId);
        String physicalPath = task.getPhysicalPath();
        if (physicalPath == null) {
            return ResponseEntity.notFound().build();
        }
        Path filePath = Path.of(physicalPath.replace("/", java.io.File.separator));
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }
        String filename = filePath.getFileName().toString();
        StreamingResponseBody stream = outputStream -> {
            try {
                Files.copy(filePath, outputStream);
                outputStream.flush();
            } catch (IOException e) {
                log.error("下载导出文件失败: taskId={}, path={}", taskId, physicalPath, e);
                throw new RuntimeException("下载失败: " + e.getMessage(), e);
            }
        };
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .body(stream);
    }

    /**
     * 打开导出文件所在目录（Windows/Linux/macOS 通用，Desktop API；失败回退 501）。
     */
    @PostMapping("/export/tasks/{taskId}/open")
    public ResponseEntity<?> openExportDir(@PathVariable Long taskId) {
        ExportTaskVO task = exportOperationService.getTask(taskId);
        String physicalPath = task.getPhysicalPath();
        if (physicalPath == null) {
            return ResponseEntity.notFound().build();
        }
        Path dirPath = Path.of(physicalPath.replace("/", java.io.File.separator)).getParent();
        if (dirPath == null || !Files.exists(dirPath)) {
            return ResponseEntity.notFound().build();
        }
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().open(dirPath.toFile());
                return ResponseEntity.ok().build();
            } catch (IOException e) {
                log.warn("Desktop.open 失败: dir={}, error={}", dirPath, e.getMessage());
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body("无法打开文件资源管理器，目录: " + dirPath);
    }
```
新增 import：`org.springframework.http.MediaType`、`org.springframework.http.ResponseEntity`、`org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody`、`java.awt.Desktop`、`java.io.IOException`、`java.nio.file.Files`、`java.nio.file.Path`、`lombok.extern.slf4j.Slf4j`（若类无 `@Slf4j` 则加）、`com.comicatlas.api.export.dto.ExportTaskVO`。

- [ ] **Step 4: 测试与提交**

```bash
.\mvnw -pl api-service -am "-Dtest=StorageOperationControllerTest" test "-Dsurefire.failIfNoSpecifiedTests=false"
git add -A
git commit -m "存储域补齐导出下载与打开目录端点：/api/storage/export/tasks/{id}/download 与 /open"
```

---

### Task 2: 前端服务层迁移到 /api/storage/*

`api.ts` 中 lq/hq/export/admin 的存储操作全部改指向新端点；补 `OperationSubmitResult` 类型定义。

**Files:**
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/types/index.ts`（新增 OperationSubmitResult）

**Interfaces:**
- Consumes: 后端新端点（全部已存在或 Task 1 补齐）
- Produces: `frontend/src/services/api.ts` 中 `lqApi`/`hqApi`/`exportApi`/`adminApi.transcodeVideos`/`refreshMetadata`/`stats` 指向 `/api/storage/*`；`OperationSubmitResult` 类型导出

- [ ] **Step 1: types/index.ts 新增 OperationSubmitResult**

在 `frontend/src/types/index.ts` 适当位置（如导出类型区）追加：
```typescript
/** 存储操作统一提交结果（/api/storage/* 返回） */
export interface OperationSubmitResult {
  readonly taskId: number | null
  readonly operationType: string
  readonly status: string | null
  readonly itemCount: number
}
```

- [ ] **Step 2: api.ts 迁移存储操作端点**

`frontend/src/services/api.ts` 修改：
```typescript
export const lqApi = {
  generateComic: (comicId: number) => api.post(`/storage/lq/comics/${comicId}`),
  generateChapter: (chapterId: number) => api.post(`/storage/lq/chapters/${chapterId}`),
}

export const hqApi = {
  deleteComic: (comicId: number) => api.post(`/storage/delete-hq/comics/${comicId}`),
  deleteChapter: (chapterId: number) => api.post(`/storage/delete-hq/chapters/${chapterId}`),
}

export const exportApi = {
  createExport: (comicId: number) => api.post(`/storage/export/comics/${comicId}`),
  listExports: (comicId: number) => api.get<ExportTaskVO[]>(`/storage/export/comics/${comicId}/tasks`),
  getTask: (taskId: number) => api.get<ExportTaskVO>(`/storage/export/tasks/${taskId}`),
  download: (taskId: number) => api.get(`/storage/export/tasks/${taskId}/download`, { responseType: 'blob' }),
  openDir: (taskId: number) => api.post(`/storage/export/tasks/${taskId}/open`),
}

// adminApi 中：
  refreshMetadata: (id: number) => api.post(`/storage/refresh-metadata/comics/${id}`),
  stats: () => api.get('/storage/stats'),
  transcodeVideos: (comicId: number) =>
    api.post<OperationSubmitResult>(`/storage/transcode/comics/${comicId}`),
```
删除 `export type VideoTranscodeResult = {...}`（L10-18）——被 `StorageDetailPage.vue` 使用，Task 3 同步改调用方。

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "前端服务层迁移存储操作到 /api/storage 统一端点，新增 OperationSubmitResult 类型"
```

---

### Task 3: 前端调用方适配

`storage.ts` 与 3 个 vue 的调用方适配新端点返回结构（`OperationSubmitResult`，无 `submittedCount`）。

**Files:**
- Modify: `frontend/src/services/storage.ts`
- Modify: `frontend/src/views/management/storage/StorageDetailPage.vue`
- Modify: `frontend/src/views/management/TaskPage.vue`（若引用旧 export 路径）
- Modify: `frontend/src/components/management/task/ExportTaskCard.vue`（download/open 已走 exportApi，无需改 URL——仅确认）

**Interfaces:**
- Consumes: Task 2 的 `api.ts` 变更
- Produces: 调用方编译通过、行为正确

- [ ] **Step 1: storage.ts 类型修正**

`frontend/src/services/storage.ts` `transcodeVideos` 返回类型从 `VideoTranscodeResult` 改为 `OperationSubmitResult`：
```typescript
import type { ComicStorageQuery, OperationSubmitResult, StorageOperation } from '@/types'
...
  async transcodeVideos(comicId: number): Promise<OperationSubmitResult> {
    const res = await adminApi.transcodeVideos(comicId)
    return res.data
  },
```

- [ ] **Step 2: StorageDetailPage.vue 转码消息适配**

`StorageDetailPage.vue:250-251` 改为：
```typescript
    const result = await storageService.transcodeVideos(comicId)
    ElMessage.success(`已提交 ${result.itemCount} 个视频转码任务`)
```
（`OperationSubmitResult.itemCount` 替代原 `submittedCount`——后端新端点返回 `itemCount`。）

- [ ] **Step 3: 检查 TaskPage.vue 与 ExportTaskCard.vue**

`git grep -n "comics/\${.*}/exports\|/export/\|submittedCount\|VideoTranscodeResult" -- "frontend/src"` 确认无残留旧路径引用。TaskPage.vue / ExportTaskCard.vue 若使用 `exportApi` 方法（已由 Task 2 改路径）则无需改动；若有内联旧 URL 则改。

- [ ] **Step 4: 前端构建验证**

```bash
cd frontend; pnpm build; cd ..
```
Expected: 编译通过（`VideoTranscodeResult` 删除后无残留引用）。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "前端调用方适配存储统一端点：转码结果改用 OperationSubmitResult.itemCount"
```

---

### Task 4: 后端删除全部旧端点

前端已迁移完成，删除旧 Controller 与旧端点，接口固定为存储域统一形态。

**Files:**
- Delete: `api-service/src/main/java/com/comicatlas/api/importer/controller/LqController.java`
- Delete: `api-service/src/main/java/com/comicatlas/api/importer/controller/HqDeleteController.java`
- Delete: `api-service/src/main/java/com/comicatlas/api/importer/service/LqService.java` + `impl/LqServiceImpl.java`（孤儿 Bean，无注入方）
- Delete: `api-service/src/main/java/com/comicatlas/api/importer/service/HqDeleteService.java` + `impl/HqDeleteServiceImpl.java`（孤儿 Bean）
- Delete: `api-service/src/main/java/com/comicatlas/api/export/controller/ExportController.java`（全部 5 个端点已被新端点替代）
- Modify: `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminController.java`（删 `storageStats` L32-35、`refreshMetadata` L62-65）
- Modify: `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminStorageController.java`（删 `transcodeVideos` L70-74 及 `TranscodeOperationService` 注入）

**Interfaces:**
- Consumes: Task 2/3 前端已全部改用新端点（无旧端点调用者）
- Produces: 旧端点全部移除；`AdminService.getStorageStats`/`refreshMetadata` 仍被新端点 Service 使用则保留

- [ ] **Step 1: 确认前端零旧端点引用**

```bash
git grep -n "/comics/\${[^}]*}/lq\|/chapters/\${[^}]*}/lq\|delete-hq\|/export/\|/admin/storage/stats\|refresh-metadata\|transcode-videos\|/comics/\${[^}]*}/export\|/comics/\${[^}]*}/exports" -- "frontend/src"
```
Expected: 0 命中（旧端点调用已全部迁移）。

- [ ] **Step 2: 删除旧 Controller 与孤儿 Service**

```bash
git rm api-service/src/main/java/com/comicatlas/api/importer/controller/LqController.java
git rm api-service/src/main/java/com/comicatlas/api/importer/controller/HqDeleteController.java
git rm api-service/src/main/java/com/comicatlas/api/importer/service/LqService.java api-service/src/main/java/com/comicatlas/api/importer/service/impl/LqServiceImpl.java
git rm api-service/src/main/java/com/comicatlas/api/importer/service/HqDeleteService.java api-service/src/main/java/com/comicatlas/api/importer/service/impl/HqDeleteServiceImpl.java
git rm api-service/src/main/java/com/comicatlas/api/export/controller/ExportController.java
```
> 删除前确认：`LqService`/`HqDeleteService` 无任何注入方（Task 3 接口收敛时已改为 storage 域 Service 委托）；`ExportService`/`ExportOperationService` 保留（新端点使用）。若测试引用旧 Controller 则同步删/改测试。

- [ ] **Step 3: AdminController 删旧方法**

`AdminController.java` 删除 `storageStats`（L26-35）与 `refreshMetadata`（L55-65）方法及不再使用的 import（`StorageStatsDTO`、`RefreshMetadataResult`、`AdminService` 若仅被这两方法用则保留——`scanRecover`/`deleteComic` 仍用 `AdminService`）。若 `AdminService.getStorageStats`/`refreshMetadata` 不再被任何 Controller 调用，保留接口方法（新端点 `StorageStatsController`/`MetadataRefreshService` 已直接注入 `AdminService`/其实现——核对后决定保留与否）。

- [ ] **Step 4: AdminStorageController 删 transcodeVideos**

`AdminStorageController.java` 删除 `transcodeVideos`（L63-74）与 `TranscodeOperationService` 字段/构造参数（若该 Controller 其他方法不用它——`StorageQueryService` 保留，`listComics`/`listChapters`/`getComic` 是存储查询，不在收敛范围，保留）。

- [ ] **Step 5: 编译 + 全量测试**

```bash
.\mvnw -pl api-service -am -DskipTests=false test
```
Expected: BUILD SUCCESS。若测试（如 `AdminControllerTest`、`LqController` 相关 IT）引用已删端点，同步更新测试。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "删除全部旧存储操作端点，接口固定为 /api/storage 统一形态"
```

---

### Task 5: 文档更新 + 全量验证收尾

**Files:**
- Modify: `docs/api.md`（§19.2 兼容窗口表移除已删旧端点行；§20 补充 download/open 端点）

- [ ] **Step 1: api.md §20 补充 download/open 行**

`docs/api.md` §20 导出任务查询行后追加：
```markdown
| 导出下载 | `GET /api/storage/export/tasks/{taskId}/download` |
| 导出打开目录 | `POST /api/storage/export/tasks/{taskId}/open` |
```

- [ ] **Step 2: api.md §19.2 兼容窗口表清理**

移除表中已删除的旧端点行（`POST /api/comics/{id}/lq` 等存储操作旧端点行——旧端点已删，不再有兼容窗口）。

- [ ] **Step 3: 全量门禁**

```bash
git grep -n "delete-hq\|/comics/{id}/lq\|transcode-videos\|refresh-metadata\|/admin/storage/stats\|/export/\${" -- "api-service/src" "frontend/src" || Write-Output "旧端点零残留"
.\mvnw clean verify "-DskipTests=false"
cd frontend; pnpm build; cd ..
```
Expected: 后端 BUILD SUCCESS（Checkstyle 0）+ 前端构建成功 + 旧端点零残留。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "更新 API 文档：接口固定为 /api/storage 统一形态，清理兼容窗口"
```

---

## Self-Review 记录

- **Spec 覆盖**：接口收敛 spec §7（兼容迁移策略）+ "后续"节（前端迁移 + 移除旧端点）→ 本计划全部落地。用户决策：删旧端点、download/open 迁新、SDD 逐任务。
- **契约核对**：后端新端点返回 `OperationSubmitResult`（taskId/operationType/status/itemCount）；前端旧 `VideoTranscodeResult`（totalVideoPages/submittedCount）不匹配——Task 3 修正为 `itemCount`。`adminApi.storageComics/storageChapters` 不在范围（无新端点）。
- **占位符**：Task 4 Step 3/4 的"核对后决定保留与否"需 implementer 读 `AdminService`/`AdminStorageController` 实际使用情况后决定——已注明检查方法（git grep）。
- **风险**：删旧 Controller 可能牵连测试（`AdminControllerTest` 等）——Task 4 Step 5 有测试更新步骤；`LqService`/`HqDeleteService` 孤儿 Bean 删除前需确认零注入（Task 4 Step 2 注明）。
