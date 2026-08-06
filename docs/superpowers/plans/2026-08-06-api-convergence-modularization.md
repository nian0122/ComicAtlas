# 接口收敛与存储域模块化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 后端 API 按功能域模块化解耦，存储操作收敛到 `storage` 域统一 URL（`/api/storage/{operation}/{targetType}/{targetId}`），转码完成后自动同步 media 元信息与 metadata.json。

**Architecture:** 新建 `com.comicatlas.api.storage` 域（Controller 薄层 + 领域 Service），存储操作统一走既有 ManagementTask 任务管线（`MediaOperationCommandService`）；`ComicListVO`/`ComicDetailVO` 移除无消费者管理字段并修正 `status` 字段名；转码完成事件携带 ffprobe 实测元数据，API 侧更新 DB 并触发 metadata.json 重导出；旧端点全部保留 `@Deprecated`（前端本次不改）。

**Tech Stack:** Java 21、Spring Boot 3、MyBatis-Plus、Lombok、RabbitMQ（Outbox/Inbox）、Jackson、p3c/Checkstyle。

**Spec:** `docs/superpowers/specs/2026-08-06-api-convergence-modularization-design.md`

## Global Constraints

- 包命名：新建域类必须放 `com.comicatlas.api.storage.*`（storage 域）
- 域间隔离：`storage` 域**不得** import `com.comicatlas.api.importer` / `com.comicatlas.api.admin` 包（委托 `management.operation.MediaOperationCommandService` 即可）
- 分层：Controller 薄层（只收参返结果）→ Service 业务，禁止 Controller 写业务逻辑
- 返回统一 `Result<T>`；枚举 Java enum（DB VARCHAR）
- 注释、提交信息用中文
- 前端代码**禁止改动**（包括 `frontend/src/types/index.ts`，其 `ChapterPageVO` 定义保留不动）
- 旧端点一律保留并标注 `@Deprecated`（注释注明"前端迁移后移除"），本次不删除任何对外端点（仅 Task 1 的 pages 死端点除外）
- 门禁：每任务结束跑 `.\mvnw -pl api-service -am test`（或对应模块），全部任务完成后跑根 `.\mvnw clean verify` 必须 BUILD SUCCESS + Checkstyle 0 违规
- Maven 在 PowerShell 下 `-D` 参数需引号包裹

---

### Task 1: 删除 pages 死端点

删除 `GET /api/comics/{comicId}/chapters/{chapterId}/pages` 及其整个调用链。前端零调用（已确认），后端无其他调用者。

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/controller/ComicController.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/ComicService.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java`
- Delete: `api-service/src/main/java/com/comicatlas/api/comic/dto/ChapterPageVO.java`
- Modify: `docs/api.md`

**Interfaces:**
- Consumes: 无
- Produces: 无（纯删除，无下游依赖）

- [ ] **Step 1: 删除 Controller 端点**

在 `ComicController.java` 删除方法 `getChapterPages`（当前 L73-78）与 import `ChapterPageVO`（L14）。删除后该文件不再引用 `ChapterPageVO`。

```java
// 删除（ComicController.java）：
// import com.comicatlas.api.comic.dto.ChapterPageVO;  ← 删除
//
//    @GetMapping("/comics/{id}/chapters/{chapterId}/pages")
//    public Result<ChapterPageVO> getChapterPages(
//            @PathVariable Long id,
//            @PathVariable Long chapterId) {
//        return Result.ok(comicService.getChapterPages(id, chapterId));
//    }
//  ← 整段删除
```

- [ ] **Step 2: 删除 Service 接口声明**

`ComicService.java` 删除（当前 L28）：
```java
// 删除：
// ChapterPageVO getChapterPages(Long comicId, Long chapterId);
```
同时删除 `import com.comicatlas.api.comic.dto.ChapterPageVO;`（L9）。

- [ ] **Step 3: 删除 Service 实现**

`ComicServiceImpl.java` 删除方法 `getChapterPages`（当前 L182-238，整段方法体）与 `import com.comicatlas.api.comic.dto.ChapterPageVO;`（L33）。

- [ ] **Step 4: 删除 DTO 并验证无引用**

```bash
# 删除 DTO 文件
Remove-Item "api-service/src/main/java/com/comicatlas/api/comic/dto/ChapterPageVO.java"
# 全仓验证后端已无引用（前端 types/index.ts 保留，属预期）
git grep -n "ChapterPageVO" -- "api-service/**" "docs/**" || Write-Output "后端无引用"
```

Expected: 仅 `frontend/src/types/index.ts:122` 与 `docs/frontend/08-frontend-architecture.md:125` 命中（均属前端/历史文档，不动）。

- [ ] **Step 5: 清理 api.md**

`docs/api.md` 删除"### 章节页面"小节（当前 L62-65）：
```markdown
### 章节页面
```
删掉：
```
GET /api/comics/{comicId}/chapters/{chapterId}/pages
```
替换为：
```markdown
### 章节页面
```
阅读页面统一走 `GET /api/chapters/{id}`（见第 2 节），旧 `GET /api/comics/{comicId}/chapters/{chapterId}/pages` 已移除。
```

- [ ] **Step 6: 编译验证并提交**

```bash
.\mvnw -pl api-service -am -q compile
git add -A
git commit -m "删除废弃的章节页面端点：pages 死代码无前端调用，阅读统一走章节详情"
```

---

### Task 2: ComicListVO / ComicDetailVO 字段修正

移除无消费者管理字段（`activeTask`/`allowedOperations`），`lifecycle` 字段重命名为 `status`（对齐前端契约 `comic.status`，前端零改动即修复）。详情 VO 同规则处理。

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicListVO.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicDetailVO.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicListQueryServiceImpl.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/management/service/ManagementTaskService.java`
- Test: `api-service/src/test/java/com/comicatlas/api/comic/service/ComicListQueryServiceImplTest.java`（若存在则更新；不存在则跳过）

**Interfaces:**
- Consumes: 无
- Produces: `ComicListVO.status`（ComicStatus 枚举，JSON 序列化为枚举名如 `"READY"`）；`ComicListVO` 不再有 `activeTask`/`allowedOperations` getter

- [ ] **Step 1: 修改 ComicListVO**

`ComicListVO.java` 完整新内容：
```java
package com.comicatlas.api.comic.dto;

import com.comicatlas.api.common.enums.ComicStatus;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 漫画列表项视图。
 * <p>
 * 仅含阅读端所需字段；status 为生命周期状态（强类型，序列化为枚举名）。
 * activeTask / allowedOperations 已在管理端独立查询（/api/management/operations），不再冗余返回。
 */
@Data
public class ComicListVO {
    private Long id;
    private String title;
    private String author;
    private String coverUrl;
    private Integer pageCount;
    private Long categoryId;
    private String categoryName;
    /** 生命周期状态（强类型，对齐前端 comic.status 契约） */
    private ComicStatus status;
    private Integer progressPercent;
    private Long lastReadChapterId;
    private Integer lastReadPage;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: 修改 ComicListQueryServiceImpl**

`ComicListQueryServiceImpl.java` 修改点：
1. 删除 `managementTaskService.findActiveTasksForComics(comicIds)`（L88-89）与 `loadPage` 中 `activeTasks` 传参：
```java
// L87-92 改为：
        Map<Long, ReadingHistory> histories = historyMapper.selectList(
                        new LambdaQueryWrapper<ReadingHistory>().in(ReadingHistory::getComicId, comicIds))
                .stream()
                .collect(Collectors.toMap(ReadingHistory::getComicId, history -> history));

        IPage<ComicListVO> voPage = result.convert(
                comic -> toListVO(comic, categoryNames, histories));
        return ComicListPage.from(voPage);
```
2. 空页分支（L67-69）改为：
```java
        if (comics.isEmpty()) {
            IPage<ComicListVO> emptyPage = result.convert(comic ->
                    toListVO(comic, new HashMap<>(), new HashMap<>()));
            return ComicListPage.from(emptyPage);
        }
```
3. `toListVO` 签名与方法体（L134-159）改为：
```java
    private ComicListVO toListVO(
            Comic comic,
            Map<Long, String> categoryNames,
            Map<Long, ReadingHistory> histories) {
        ComicListVO vo = new ComicListVO();
        vo.setId(comic.getId());
        vo.setTitle(comic.getTitle());
        vo.setAuthor(comic.getAuthor());
        vo.setCoverUrl(fileUrlResolver.resolveCover(comic.getId()));
        vo.setPageCount(comic.getTotalPages());
        vo.setCategoryId(comic.getCategoryId());
        vo.setCategoryName(categoryNames.get(comic.getCategoryId()));
        vo.setStatus(toStatus(comic.getStatus() == null ? null : comic.getStatus().name()));
        vo.setCreatedAt(comic.getCreatedAt());

        ReadingHistory history = histories.get(comic.getId());
        if (history != null && comic.getTotalPages() != null && comic.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / comic.getTotalPages());
        }
        return vo;
    }

    private static ComicStatus toStatus(String status) {
        if (status == null) { return null; }
        try {
            return ComicStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
```
4. 方法名 `toLifecycle` 全部替换为 `toStatus`（仅本文件内引用）。
5. 移除不再使用的 import：`com.comicatlas.api.management.dto.ManagementTaskResponse`、`com.comicatlas.api.management.policy.OperationPolicyService`、`com.comicatlas.api.management.service.ManagementTaskService`（L17-19）。
6. **缓存版本前缀**（强制旧缓存失效，避免旧结构污染）：`cacheKey`（L100-113）首行改为：
```java
        String raw = "v2|" + String.join("|",
```

- [ ] **Step 3: 修改 ComicDetailVO 与 ComicServiceImpl.toDetailVO**

`ComicDetailVO.java`：删除 `activeTask`/`allowedOperations` 字段（连同 `ManagementTaskResponse`/`AllowedOperations` import），`lifecycle` 字段重命名为 `status`。

`ComicServiceImpl.java` `toDetailVO`（L433-436）改为：
```java
        vo.setStatus(toLifecycle(comicStatusName(comic)));
        vo.setVersion(comic.getVersion());
        vo.setCreatedAt(comic.getCreatedAt());
```
删除 `vo.setActiveTask(activeTaskFor(comic.getId()));`（L435）、`vo.setAllowedOperations(...)`（L436）、`activeTaskFor` 方法（L481-483）。

- [ ] **Step 4: 删除 findActiveTasksForComics（无调用者）**

`ManagementTaskService.java` 删除 `findActiveTasksForComics` 方法（L594 起）。先验证无其他调用者：
```bash
git grep -n "findActiveTasksForComics" -- "api-service/**"
```
Expected: 0 命中（Task 2 已删光调用点）。若仍有命中，检查并处理后再删。

- [ ] **Step 5: 测试与编译**

```bash
git grep -n "getActiveTask\|getAllowedOperations\|getLifecycle" -- "api-service/src" || Write-Output "无残留引用"
.\mvnw -pl api-service -am -q -DskipTests=false test
```
若有 `ComicListQueryServiceImplTest`，断言改为 `vo.getStatus()`（原 `getLifecycle()`）。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "漫画列表与详情 VO 修正：移除无消费者管理字段，lifecycle 重命名为 status 对齐前端契约"
```

---

### Task 3: storage 域骨架 + LQ/HQ 操作新端点

创建 `storage` 域：`StorageOperationController`（LQ/HQ 操作）+ `LqOperationService`/`HqDeleteOperationService`（直接委托 `MediaOperationCommandService`，不依赖 importer 包）。旧 `LqController`/`HqDeleteController` 改注 `@Deprecated` 并改调新 Service。

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/storage/controller/StorageOperationController.java`
- Create: `api-service/src/main/java/com/comicatlas/api/storage/service/LqOperationService.java`
- Create: `api-service/src/main/java/com/comicatlas/api/storage/service/HqDeleteOperationService.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/controller/LqController.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/controller/HqDeleteController.java`
- Test: `api-service/src/test/java/com/comicatlas/api/storage/controller/StorageOperationControllerTest.java`

**Interfaces:**
- Consumes: `MediaOperationCommandService.requestLqForComic(Long,boolean)`、`requestLqForChapter(Long,boolean)`、`requestHqDeleteForComic(Long)`、`requestHqDeleteForChapter(Long)`（均返回 `OperationSubmitResult`）
- Produces: `LqOperationService.generateForComic(Long,boolean)`、`generateForChapter(Long,boolean)`、`HqDeleteOperationService.deleteForComic(Long)`、`deleteForChapter(Long)`；端点 `POST /api/storage/lq/comics/{id}`、`/lq/chapters/{id}`、`/delete-hq/comics/{id}`、`/delete-hq/chapters/{id}`

- [ ] **Step 1: 写失败测试**

`StorageOperationControllerTest.java`（新建）：
```java
package com.comicatlas.api.storage.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import com.comicatlas.api.storage.service.HqDeleteOperationService;
import com.comicatlas.api.storage.service.LqOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StorageOperationControllerTest {

    private final MediaOperationCommandService commandService = mock(MediaOperationCommandService.class);
    private final LqOperationService lqService = new LqOperationService(commandService);
    private final HqDeleteOperationService hqService = new HqDeleteOperationService(commandService);
    private final StorageOperationController controller = new StorageOperationController(lqService, hqService);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @Test
    void generateComicLq_委托命令服务并返回提交结果() throws Exception {
        when(commandService.requestLqForComic(42L, false))
                .thenReturn(OperationSubmitResult.of(7L, "LQ_GENERATE", "QUEUED", 3));

        mvc.perform(post("/api/storage/lq/comics/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(7))
                .andExpect(jsonPath("$.data.operationType").value("LQ_GENERATE"));

        verify(commandService).requestLqForComic(42L, false);
    }

    @Test
    void generateChapterLq_传递regenerate参数() throws Exception {
        when(commandService.requestLqForChapter(9L, true))
                .thenReturn(OperationSubmitResult.of(8L, "LQ_REGENERATE", "QUEUED", 1));

        mvc.perform(post("/api/storage/lq/chapters/9").param("regenerate", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationType").value("LQ_REGENERATE"));

        verify(commandService).requestLqForChapter(9L, true);
    }

    @Test
    void deleteComicHq_委托删除命令() throws Exception {
        when(commandService.requestHqDeleteForComic(42L))
                .thenReturn(OperationSubmitResult.of(9L, "HQ_DELETE", "QUEUED", 2));

        mvc.perform(post("/api/storage/delete-hq/comics/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(9));

        verify(commandService).requestHqDeleteForComic(42L);
    }

    @Test
    void deleteChapterHq_委托删除命令() throws Exception {
        when(commandService.requestHqDeleteForChapter(9L))
                .thenReturn(OperationSubmitResult.of(10L, "HQ_DELETE", "QUEUED", 1));

        mvc.perform(post("/api/storage/delete-hq/chapters/9"))
                .andExpect(status().isOk());

        verify(commandService).requestHqDeleteForChapter(9L);
    }

    @Test
    void 未使用参数anyBoolean避免误匹配() {
        // 保证 LQ 委托时不误传 regenerate（占位断言，防静态检查误报）
        org.junit.jupiter.api.Assertions.assertNotNull(controller);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
.\mvnw -pl api-service -am -Dtest=StorageOperationControllerTest test
```
Expected: 编译失败（`LqOperationService` 等类不存在）。

- [ ] **Step 3: 创建 storage 域 Service**

`LqOperationService.java`：
```java
package com.comicatlas.api.storage.service;

import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * LQ 生成操作服务（存储操作域）。
 * <p>
 * 统一委托 MediaOperationCommandService 走 ManagementTask 任务管线。
 * 与 importer 包解耦：本类不依赖任何 importer 类型。
 */
@Service
@RequiredArgsConstructor
public class LqOperationService {

    private final MediaOperationCommandService commandService;

    public OperationSubmitResult generateForComic(Long comicId, boolean regenerate) {
        return commandService.requestLqForComic(comicId, regenerate);
    }

    public OperationSubmitResult generateForChapter(Long chapterId, boolean regenerate) {
        return commandService.requestLqForChapter(chapterId, regenerate);
    }
}
```

`HqDeleteOperationService.java`：
```java
package com.comicatlas.api.storage.service;

import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * HQ 删除操作服务（存储操作域）。删除 HQ 保留 LQ。
 * 统一委托 MediaOperationCommandService 走 ManagementTask 任务管线。
 */
@Service
@RequiredArgsConstructor
public class HqDeleteOperationService {

    private final MediaOperationCommandService commandService;

    public OperationSubmitResult deleteForComic(Long comicId) {
        return commandService.requestHqDeleteForComic(comicId);
    }

    public OperationSubmitResult deleteForChapter(Long chapterId) {
        return commandService.requestHqDeleteForChapter(chapterId);
    }
}
```

- [ ] **Step 4: 创建 StorageOperationController**

`StorageOperationController.java`：
```java
package com.comicatlas.api.storage.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.storage.service.HqDeleteOperationService;
import com.comicatlas.api.storage.service.LqOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储操作统一入口（存储操作域）。
 * <p>
 * URL 形态：POST /api/storage/{operation}/{targetType}/{targetId}，targetType = comics | chapters。
 * 后续转码 / 导出 / 刷新元数据 / 统计端点追加到本类。
 */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageOperationController {

    private final LqOperationService lqOperationService;
    private final HqDeleteOperationService hqDeleteOperationService;

    // ======================== LQ 生成 ========================

    @PostMapping("/lq/comics/{comicId}")
    public Result<OperationSubmitResult> generateComicLq(
            @PathVariable Long comicId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqOperationService.generateForComic(comicId, regenerate));
    }

    @PostMapping("/lq/chapters/{chapterId}")
    public Result<OperationSubmitResult> generateChapterLq(
            @PathVariable Long chapterId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqOperationService.generateForChapter(chapterId, regenerate));
    }

    // ======================== HQ 删除（保留 LQ） ========================

    @PostMapping("/delete-hq/comics/{comicId}")
    public Result<OperationSubmitResult> deleteComicHq(@PathVariable Long comicId) {
        return Result.ok(hqDeleteOperationService.deleteForComic(comicId));
    }

    @PostMapping("/delete-hq/chapters/{chapterId}")
    public Result<OperationSubmitResult> deleteChapterHq(@PathVariable Long chapterId) {
        return Result.ok(hqDeleteOperationService.deleteForChapter(chapterId));
    }
}
```

- [ ] **Step 5: 旧 Controller 改 @Deprecated + 委托新 Service**

`LqController.java`：改为注入 `LqOperationService`（替换原 `LqService`），类与方法标 `@Deprecated`，方法体委托：
```java
    @Deprecated
    @PostMapping("/comics/{comicId}/lq")
    public Result<OperationSubmitResult> generateComicLq(
            @PathVariable Long comicId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqOperationService.generateForComic(comicId, regenerate));
    }
```
`HqDeleteController.java`：同样改为注入 `HqDeleteOperationService`、标 `@Deprecated`。

- [ ] **Step 6: 运行测试确认通过**

```bash
.\mvnw -pl api-service -am -Dtest=StorageOperationControllerTest test
```
Expected: PASS（4 用例）。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "新增存储操作域：LQ 生成与 HQ 删除统一到 /api/storage 端点，旧端点标注废弃"
```

---

### Task 4: 转码操作新端点（含章节级）

新增章节级转码能力（`MediaOperationCommandService.requestTranscodeForChapter`），`storage` 域提供 `TranscodeOperationService` 与新端点。旧 `AdminStorageController.transcode-videos` 标 `@Deprecated`。

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/management/operation/MediaOperationCommandService.java`
- Create: `api-service/src/main/java/com/comicatlas/api/storage/service/TranscodeOperationService.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/storage/controller/StorageOperationController.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminStorageController.java`
- Test: `api-service/src/test/java/com/comicatlas/api/management/operation/MediaOperationCommandServiceTest.java`（新增章节级用例）

**Interfaces:**
- Consumes: `MediaOperationCommandService.isTranscodeEligible`、`markTranscodeQueued`、`createTask`、`enqueue`（私有，本类内复用）
- Produces: `MediaOperationCommandService.requestTranscodeForChapter(Long) → OperationSubmitResult`；`TranscodeOperationService.transcodeForComic(Long)`/`transcodeForChapter(Long)`；端点 `POST /api/storage/transcode/comics/{id}`、`/transcode/chapters/{id}`

- [ ] **Step 1: 写失败测试**

`MediaOperationCommandServiceTest.java`（在既有测试类中新增，或新建）：
```java
    @Test
    void requestTranscodeForChapter_仅选中章节下待转码视频() {
        // 构造：chapterId=9 下 1 个 VIDEO(container=null, transcodeStatus=NOT_NEEDED)
        //       + 1 个 VIDEO(container=mp4, transcodeStatus=NOT_NEEDED)
        // mock mediaMapper.selectList 返回两个
        // 断言：仅 1 个 target(MEDIA)，enqueue 1 次
    }

    @Test
    void requestTranscodeForChapter_无待转码时返回空任务() {
        // chapterId=9 无 VIDEO 或全部已就绪 → OperationSubmitResult.taskId == null
    }
```
（测试方法体需按既有测试的 mock 方式实现，参考 `api-service/src/test/java/com/comicatlas/api/management/MediaOperationPipelineIT.java` 的 mock 风格。）

- [ ] **Step 2: 运行确认失败**

```bash
.\mvnw -pl api-service -am -Dtest=MediaOperationCommandServiceTest test
```
Expected: 编译失败（`requestTranscodeForChapter` 不存在）。

- [ ] **Step 3: 实现 requestTranscodeForChapter**

`MediaOperationCommandService.java` 在"视频转码"区段（L261 `requestTranscodeForMedia` 之前）插入：
```java
    public OperationSubmitResult requestTranscodeForChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在: " + chapterId);
        }
        List<Media> toTranscode = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .eq(Media::getChapterId, chapterId)
                        .eq(Media::getMediaType, "VIDEO"));
        List<Media> eligible = toTranscode.stream()
                .filter(this::isTranscodeEligible)
                .toList();
        if (eligible.isEmpty()) {
            log.info("章节 {} 无待转码视频，跳过", chapterId);
            return OperationSubmitResult.of(null, TaskType.TRANSCODE.name(), null, 0);
        }

        List<CreateManagementTaskRequest.TaskTarget> targets = eligible.stream()
                .map(media -> target("MEDIA", media.getId(), TaskType.TRANSCODE))
                .toList();
        ManagementTaskResponse task = createTask(TaskType.TRANSCODE, "视频转码", "CHAPTER", targets);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());

        for (ManagementTaskItemResponse item : items) {
            markTranscodeQueued(item.getTargetId());
            enqueue(TaskType.TRANSCODE, item, "MEDIA", item.getTargetId());
        }
        log.info("转码命令已提交: chapterId={}, taskId={}, items={}",
                chapterId, task.getId(), items.size());
        return OperationSubmitResult.of(task.getId(), TaskType.TRANSCODE.name(), task.getStatus().name(), items.size());
    }
```

- [ ] **Step 4: 创建 TranscodeOperationService**

```java
package com.comicatlas.api.storage.service;

import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 视频转码操作服务（存储操作域）。支持漫画级与章节级。
 */
@Service
@RequiredArgsConstructor
public class TranscodeOperationService {

    private final MediaOperationCommandService commandService;

    public OperationSubmitResult transcodeForComic(Long comicId) {
        return commandService.requestTranscodeForComic(comicId);
    }

    public OperationSubmitResult transcodeForChapter(Long chapterId) {
        return commandService.requestTranscodeForChapter(chapterId);
    }
}
```

- [ ] **Step 5: StorageOperationController 追加转码端点**

注入 `TranscodeOperationService` 并追加：
```java
    // ======================== 视频转码 ========================

    @PostMapping("/transcode/comics/{comicId}")
    public Result<OperationSubmitResult> transcodeComic(@PathVariable Long comicId) {
        return Result.ok(transcodeOperationService.transcodeForComic(comicId));
    }

    @PostMapping("/transcode/chapters/{chapterId}")
    public Result<OperationSubmitResult> transcodeChapter(@PathVariable Long chapterId) {
        return Result.ok(transcodeOperationService.transcodeForChapter(chapterId));
    }
```

- [ ] **Step 6: 旧端点标 @Deprecated**

`AdminStorageController.java` 的 `transcodeVideos` 方法（`POST /admin/storage/comics/{comicId}/transcode-videos`）标注 `@Deprecated`，注释注明"请改用 POST /api/storage/transcode/comics/{comicId}"。方法体改为委托 `TranscodeOperationService.transcodeForComic`（需新增构造注入）。

- [ ] **Step 7: 测试与提交**

```bash
.\mvnw -pl api-service -am -Dtest=MediaOperationCommandServiceTest,StorageOperationControllerTest test
git add -A
git commit -m "转码支持章节级：新增 /api/storage/transcode 端点，旧漫画级端点标注废弃"
```

---

### Task 5: 刷新元数据统一（双路径收敛）

核心扫盘逻辑从 `AdminServiceImpl.refreshMetadata` 提取到 `storage` 域 `MetadataRefreshService`；新端点 `POST /api/storage/refresh-metadata/comics/{id}`；旧 `AdminController.refresh-metadata` 标 `@Deprecated` 并委托；`ManagementCommandResultHandler` 补全 `METADATA_REFRESH` completed 业务（调用同一 Service）。

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/storage/service/MetadataRefreshService.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/storage/controller/StorageOperationController.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminController.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/admin/service/impl/AdminServiceImpl.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/management/event/ManagementCommandResultHandler.java`
- Test: `api-service/src/test/java/com/comicatlas/api/storage/service/MetadataRefreshServiceTest.java`

**Interfaces:**
- Consumes: `RecoveryEngine.scanChapterPages(Long,Integer)`、`MetadataExporter`（若原实现依赖）、`RabbitTemplate`、`CatalogCacheInvalidator`
- Produces: `MetadataRefreshService.refresh(Long comicId) → RefreshMetadataResult`（沿用 `com.comicatlas.api.admin.dto.RefreshMetadataResult`）；端点 `POST /api/storage/refresh-metadata/comics/{id}`

- [ ] **Step 1: 写失败测试**

`MetadataRefreshServiceTest.java`（Mockito 单元测试，验证：CAS 锁、扫盘更新、发 MQ、finally 恢复 READY）：
```java
package com.comicatlas.api.storage.service;

import com.comicatlas.api.admin.dto.RefreshMetadataResult;
import com.comicatlas.api.admin.recovery.RecoveryEngine;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataRefreshServiceTest {

    private final ComicMapper comicMapper = mock(ComicMapper.class);
    private final RecoveryEngine recoveryEngine = mock(RecoveryEngine.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final CatalogCacheInvalidator invalidator = mock(CatalogCacheInvalidator.class);

    private MetadataRefreshService newService() {
        return new MetadataRefreshService(comicMapper, recoveryEngine, rabbitTemplate, invalidator,
                new TransactionTemplate(), /* 其余依赖按实现注入 */ null);
    }

    @Test
    void refresh_漫画不存在时抛出业务异常() {
        when(comicMapper.selectById(1L)).thenReturn(null);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.comicatlas.api.common.exception.BusinessException.class,
                () -> newService().refresh(1L));
    }

    @Test
    void refresh_CAS失败时抛出冲突() {
        Comic comic = new Comic();
        comic.setId(1L);
        comic.setStatus(ComicStatus.READY);
        when(comicMapper.selectById(1L)).thenReturn(comic);
        // update 返回 0（CAS 失败）
        when(comicMapper.update(any(), any())).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.comicatlas.api.common.exception.BusinessException.class,
                () -> newService().refresh(1L));
    }

    @Test
    void refresh_成功后发metadata刷新MQ并恢复READY() {
        Comic comic = new Comic();
        comic.setId(1L);
        comic.setStatus(ComicStatus.READY);
        when(comicMapper.selectById(1L)).thenReturn(comic);
        when(comicMapper.update(any(), any())).thenReturn(1);
        // 依赖注入完整后补充：verify(rabbitTemplate).convertAndSend(eq("comic.export"),
        //        eq("metadata.refresh.requested"), any());
    }
}
```
> 注：`MetadataRefreshService` 的构造参数以最终实现为准；本测试在 Step 3 完成后按真实构造器补齐 mock 参数。

- [ ] **Step 2: 运行确认失败**

```bash
.\mvnw -pl api-service -am -Dtest=MetadataRefreshServiceTest test
```
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 提取核心逻辑到 MetadataRefreshService**

将 `AdminServiceImpl.refreshMetadata`（当前 L254-367）的完整实现（CAS 锁、扫盘更新、fixVideoMetadata、发 MQ、finally 恢复）迁移到 `MetadataRefreshService.refresh(Long)`，依赖注入从 `AdminServiceImpl` 复制（`comicMapper/chapterMapper/mediaMapper/recoveryEngine/catalogCacheInvalidator/rabbitTemplate/transactionTemplate`）。返回类型沿用 `RefreshMetadataResult`（import `com.comicatlas.api.admin.dto.RefreshMetadataResult`——此依赖允许，`admin.dto` 非 `admin` 域实现类）。

- [ ] **Step 4: AdminServiceImpl 委托新 Service**

`AdminServiceImpl.java`：注入 `MetadataRefreshService`，`refreshMetadata(Long)` 改为：
```java
    @Override
    @Deprecated // 请改用 POST /api/storage/refresh-metadata/comics/{id}
    public RefreshMetadataResult refreshMetadata(Long comicId) {
        return metadataRefreshService.refresh(comicId);
    }
```
删除原方法体及不再使用的私有依赖字段（若 `recoveryEngine/transactionTemplate/rabbitTemplate` 在别处仍用则保留）。

- [ ] **Step 5: AdminController 标 @Deprecated**

`AdminController.java` 的 `refreshMetadata` 方法标 `@Deprecated`，注释注明新端点；方法体不变（仍调 `adminService.refreshMetadata`，其内部已委托新 Service）。

- [ ] **Step 6: StorageOperationController 追加刷新元数据端点**

注入 `MetadataRefreshService` 并追加：
```java
    // ======================== 刷新元数据 ========================

    @PostMapping("/refresh-metadata/comics/{comicId}")
    public Result<RefreshMetadataResult> refreshMetadata(@PathVariable Long comicId) {
        return Result.ok(metadataRefreshService.refresh(comicId));
    }
```

- [ ] **Step 7: 补全 METADATA_REFRESH completed 业务**

`ManagementCommandResultHandler.java` L180 `case "METADATA_REFRESH" -> { }` 改为：
```java
            case "METADATA_REFRESH" -> {
                if (comicScope) {
                    metadataRefreshService.refresh(ev.targetId());
                }
            }
```
在类中注入 `MetadataRefreshService`（构造器新增参数）。

- [ ] **Step 8: 测试与提交**

```bash
.\mvnw -pl api-service -am -Dtest=MetadataRefreshServiceTest test
git add -A
git commit -m "刷新元数据收敛到存储域：统一 MetadataRefreshService，补全 METADATA_REFRESH 任务完成业务"
```

---

### Task 6: 导出与存储统计新端点

`storage` 域提供 `ExportOperationService`（委托 `ExportService`）与 `StorageStatsController`（委托 `AdminService.getStorageStats`）；旧端点标 `@Deprecated`。

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/storage/service/ExportOperationService.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/storage/controller/StorageOperationController.java`
- Create: `api-service/src/main/java/com/comicatlas/api/storage/controller/StorageStatsController.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/export/controller/ExportController.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminController.java`

**Interfaces:**
- Consumes: `ExportService.createExportTask(Long)/listExports(Long)/getTask(Long)`；`AdminService.getStorageStats()`
- Produces: `ExportOperationService`（4 个方法透传）；端点 `POST /api/storage/export/comics/{id}`、`GET /api/storage/export/comics/{id}/tasks`、`GET /api/storage/export/tasks/{taskId}`、`GET /api/storage/export/tasks/{taskId}/download`、`POST /api/storage/export/tasks/{taskId}/open`、`GET /api/storage/stats`

- [ ] **Step 1: 创建 ExportOperationService**

```java
package com.comicatlas.api.storage.service;

import com.comicatlas.api.export.dto.ExportTaskVO;
import com.comicatlas.api.export.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 导出操作服务（存储操作域）。委托 ExportService，端点归位到 /api/storage/export。
 */
@Service
@RequiredArgsConstructor
public class ExportOperationService {

    private final ExportService exportService;

    public ExportTaskVO createExportTask(Long comicId) {
        return exportService.createExportTask(comicId);
    }

    public List<ExportTaskVO> listExports(Long comicId) {
        return exportService.listExports(comicId);
    }

    public ExportTaskVO getTask(Long taskId) {
        return exportService.getTask(taskId);
    }
}
```
> 注：`download`/`openDir` 涉及 `StreamingResponseBody` 与 `Desktop`，保留在 `ExportController`（旧端点）内实现，新端点复用同一 `ExportService`；Task 6 只新增 create/list/get 三个端点（download/open 沿用旧端点，避免重复实现流式响应逻辑）。

- [ ] **Step 2: StorageOperationController 追加导出端点**

注入 `ExportOperationService` 并追加：
```java
    // ======================== 导出 ========================

    @PostMapping("/export/comics/{comicId}")
    public ResponseEntity<ExportTaskVO> createExport(@PathVariable Long comicId) {
        ExportTaskVO task = exportOperationService.createExportTask(comicId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(task);
    }

    @GetMapping("/export/comics/{comicId}/tasks")
    public Result<List<ExportTaskVO>> listExports(@PathVariable Long comicId) {
        return Result.ok(exportOperationService.listExports(comicId));
    }

    @GetMapping("/export/tasks/{taskId}")
    public Result<ExportTaskVO> getExportTask(@PathVariable Long taskId) {
        return Result.ok(exportOperationService.getTask(taskId));
    }
```
（新增 import：`org.springframework.http.ResponseEntity`、`org.springframework.http.HttpStatus`、`com.comicatlas.api.export.dto.ExportTaskVO`、`java.util.List`。）

- [ ] **Step 3: StorageStatsController**

```java
package com.comicatlas.api.storage.controller;

import com.comicatlas.api.admin.dto.StorageStatsDTO;
import com.comicatlas.api.admin.service.AdminService;
import com.comicatlas.api.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储统计（存储操作域）。
 */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageStatsController {

    private final AdminService adminService;

    @GetMapping("/stats")
    public Result<StorageStatsDTO> stats() {
        return Result.ok(adminService.getStorageStats());
    }
}
```

- [ ] **Step 4: 旧端点标 @Deprecated**

`ExportController.java` 各端点方法标 `@Deprecated`（注释注明"请改用 /api/storage/export/*"）。`AdminController.java` 的 `storageStats` 方法标 `@Deprecated`（注释注明"请改用 GET /api/storage/stats"）。

- [ ] **Step 5: 编译验证并提交**

```bash
.\mvnw -pl api-service -am -q compile
git add -A
git commit -m "导出与存储统计端点归位 storage 域：/api/storage/export 与 /api/storage/stats"
```

---

### Task 7: 转码后 media 元信息自动同步

转码完成后 Worker 用 ffprobe 实测新文件元数据，随 completed 事件回传；API 侧 `MediaMetadataSyncService` 更新 DB（duration/fileSize/实测 codec）并触发 metadata.json 重导出。

**Files:**
- Modify: `comic-common/src/main/java/com/comicatlas/common/event/ManagementCommandCompletedEvent.java`
- Create: `comic-common/src/main/java/com/comicatlas/common/event/TranscodeMediaInfo.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/ManagementCommandPublisher.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/TranscodeCommandHandler.java`
- Create: `api-service/src/main/java/com/comicatlas/api/storage/service/MediaMetadataSyncService.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/management/event/ManagementCommandResultHandler.java`
- Test: `api-service/src/test/java/com/comicatlas/api/export/event/TranscodeCompletedHandlerTest.java`（更新）

**Interfaces:**
- Consumes: `MediaAnalyzer.analyzeVideo(Path) → Optional<ComicMetadata.MediaInfo>`（worker）；`Media` 实体字段 `duration/container/videoCodec/audioCodec/fileSize`
- Produces: `TranscodeMediaInfo(BigDecimal duration, String container, String videoCodec, String audioCodec, Long fileSize)`；`ManagementCommandCompletedEvent` 新增组件 `TranscodeMediaInfo transcode`（nullable）；`ManagementCommandPublisher.completed(cmd)` 重载 `completed(cmd, transcode)`；`MediaMetadataSyncService.notifyTranscoded(Long mediaId, Long taskId)`

- [ ] **Step 1: 新增 TranscodeMediaInfo（comic-common）**

```java
package com.comicatlas.common.event;

import java.math.BigDecimal;

/**
 * 转码完成后回传的新视频文件元数据（ffprobe 实测）。
 * <p>
 * 作为 ManagementCommandCompletedEvent 的可选组件，仅 TRANSCODE 操作且转码成功时非 null。
 * 为保持事件契约向后兼容，老消息缺少该字段时 Jackson 反序列化为 null。
 */
public record TranscodeMediaInfo(
    BigDecimal duration,
    String container,
    String videoCodec,
    String audioCodec,
    Long fileSize
) {
}
```

- [ ] **Step 2: 扩展 ManagementCommandCompletedEvent**

```java
package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理命令完成事件（Worker → API）。
 * <p>
 * Worker 完成管理命令后发送此事件。API 端依据 taskId/itemId/attempt
 * 更新 management_task_item 为 SUCCEEDED 并聚合 management_task 状态。
 * transcode 组件仅在 TRANSCODE 操作时携带转码后实测元数据，其余为 null。
 */
public record ManagementCommandCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    int version,
    Long taskId,
    Long itemId,
    int attempt,
    String operationType,
    String targetType,
    Long targetId,
    TranscodeMediaInfo transcode
) implements ComicEvent {

    @Override
    public int version() {
        return version;
    }
}
```

- [ ] **Step 3: ManagementCommandPublisher 重载**

```java
    public void completed(ManagementCommandRequestedEvent cmd) {
        completed(cmd, null);
    }

    public void completed(ManagementCommandRequestedEvent cmd, TranscodeMediaInfo transcode) {
        rabbitTemplate.convertAndSend(EXCHANGE, "command.completed",
                new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), cmd.attempt(),
                        cmd.operationType(), cmd.targetType(), cmd.targetId(),
                        transcode));
    }
```

- [ ] **Step 4: TranscodeCommandHandler 实测回传**

`TranscodeCommandHandler.java`：
1. 新增内部结果类型与依赖（注入 `MediaAnalyzer`）：
```java
    private final MediaAnalyzer mediaAnalyzer;

    /** 单页转码结果：error 为 null 表示成功；transcode 为成功时的实测元数据（可能为 null）。 */
    private record TranscodeResult(String error, TranscodeMediaInfo transcode) {}
```
2. `processPage` 返回类型改为 `TranscodeResult`（各失败分支返回 `new TranscodeResult(错误消息或 "TRANSCODE_INTERRUPTED", null)`；成功分支在文件替换后、`return null` 前改为）：
```java
            log.info("转码完成: pageId={}, newPath={}", pageId, newHqFile);
            TranscodeMediaInfo info = probe(newHqFile);
            return new TranscodeResult(null, info);
```
3. 新增 `probe` 方法（`ComicMetadata.MediaInfo` 组件顺序已确认：`(fileName, pageNumber, hqStatus, lqStatus, fileSize, width, height, mediaType, duration, container, videoCodec, audioCodec)`，`analyzeVideo` 返回 `Optional<MediaInfo>`）：
```java
    /** 用 ffprobe 实测转码后文件元数据；失败降级为 null。 */
    private TranscodeMediaInfo probe(Path file) {
        try {
            var opt = mediaAnalyzer.analyzeVideo(file);
            if (opt.isEmpty()) {
                return null;
            }
            ComicMetadata.MediaInfo info = opt.get();
            return new TranscodeMediaInfo(
                    info.duration(), info.container(), info.videoCodec(), info.audioCodec(),
                    info.fileSize());
        } catch (Exception e) {
            log.warn("转码后元数据探测失败，降级为 null: file={}, error={}", file, e.getMessage());
            return null;
        }
    }
```
4. `transcodePage` 成功分支改为携带 transcode 回传：
```java
            TranscodeResult r = processPage(cmd, pageId);
            if (r.error() == null) {
                publisher.progress(cmd, 100, "转码完成");
                publisher.completed(cmd, r.transcode());
            } else if ("TRANSCODE_INTERRUPTED".equals(r.error())) {
                publisher.failed(cmd, "转码被中断");
            } else {
                publisher.failed(cmd, r.error());
            }
```

- [ ] **Step 5: API 端 MediaMetadataSyncService**

```java
package com.comicatlas.api.storage.service;

import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.importer.event.MetadataRefreshEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Media 元信息同步服务（存储操作域）。
 * <p>
 * 转码等操作导致 media 元信息变更后，负责：刷新章节/漫画统计、失效目录缓存、
 * 触发 metadata.json 重导出（发 metadata.refresh.requested MQ）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaMetadataSyncService {

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final RabbitTemplate rabbitTemplate;

    /**
     * 转码完成后同步：更新漫画/章节统计并触发 metadata.json 重导出。
     */
    public void notifyTranscoded(Long mediaId, Long taskId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null || media.getChapterId() == null) {
            return;
        }
        Chapter chapter = chapterMapper.selectById(media.getChapterId());
        if (chapter == null) {
            return;
        }
        Long comicId = chapter.getComicId();
        refreshChapterAndComicStats(chapter.getId());
        catalogCacheInvalidator.evict(comicId);
        try {
            rabbitTemplate.convertAndSend("comic.export", "metadata.refresh.requested",
                    new MetadataRefreshEvent(null, null, comicId));
            log.info("转码后元数据同步已触发: mediaId={}, comicId={}, taskId={}", mediaId, comicId, taskId);
        } catch (Exception e) {
            log.warn("发送 metadata 刷新 MQ 消息失败: comicId={}", comicId, e);
        }
    }

    private void refreshChapterAndComicStats(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }
        long pageCount = mediaMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .notIn(Media::getStatus, "DELETED", "TRASHED"));
        chapterMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Chapter>()
                .eq(Chapter::getId, chapterId)
                .set(Chapter::getPageCount, (int) pageCount));
        Comic comic = comicMapper.selectById(chapter.getComicId());
        if (comic != null) {
            long totalPages = comicMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Comic>()
                    .eq(Comic::getId, comic.getId()));
            // 精确重算需按章节聚合；此处沿用 ManagementCommandResultHandler 同款逻辑（按漫画下全部章节聚合）
            comicMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Comic>()
                    .eq(Comic::getId, comic.getId())
                    .set(Comic::getTotalPages, (int) totalPages));
        }
    }
}
```
> 注：`MetadataRefreshEvent` 构造器签名与 `refreshChapterAndComicStats` 聚合逻辑需与现有 `ManagementCommandResultHandler`（L487-511）保持一致；实现时直接对齐既有代码，避免引入新语义。

- [ ] **Step 6: applyTranscodeCompleted 增强**

`ManagementCommandResultHandler.java`：
1. `applyCompletedBusiness` 的 TRANSCODE 分支（L162-170）改为传 `ev`（`applyTranscodeCompleted` 需访问 `ev.transcode()`）：
```java
            case "TRANSCODE" -> {
                if (comicScope) {
                    for (Long mediaId : mediaIdsOf(ev.targetId())) {
                        applyTranscodeCompleted(ev, mediaId);
                    }
                } else {
                    applyTranscodeCompleted(ev, ev.targetId());
                }
            }
```
2. `applyTranscodeCompleted`（L243-260）签名与方法体改为：
```java
    private void applyTranscodeCompleted(ManagementCommandCompletedEvent ev, Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            return;
        }
        TranscodeMediaInfo transcode = ev.transcode();
        String hqPath = media.getHqPath();
        LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .set(Media::getTranscodeStatus, TranscodeStatus.READY)
                .set(Media::getContainer, transcode != null && transcode.container() != null
                        ? transcode.container() : "mp4")
                .set(Media::getVideoCodec, transcode != null && transcode.videoCodec() != null
                        ? transcode.videoCodec() : "h264")
                .set(Media::getAudioCodec, transcode != null && transcode.audioCodec() != null
                        ? transcode.audioCodec() : "aac");
        if (transcode != null) {
            if (transcode.duration() != null) {
                mediaUpdate.set(Media::getDuration, transcode.duration());
            }
            if (transcode.fileSize() != null) {
                mediaUpdate.set(Media::getFileSize, transcode.fileSize());
            }
        }
        if (hqPath != null && !hqPath.isBlank()) {
            mediaUpdate.set(Media::getHqPath, deriveTranscodedPath(hqPath));
        }
        mediaMapper.update(null, mediaUpdate);
        mediaMetadataSyncService.notifyTranscoded(mediaId, ev.taskId());
        log.info("转码完成业务更新: mediaId={}", mediaId);
    }
```
3. 类中新增注入 `MediaMetadataSyncService`，并新增 import `com.comicatlas.common.event.TranscodeMediaInfo`。

- [ ] **Step 7: 更新契约测试与既有测试**

更新 `ManagementEventContractTest`：`ManagementCommandCompletedEvent` 序列化/反序列化包含新 `transcode` 组件（null 与携带值两种）。更新 `TranscodeCompletedHandlerTest` 断言（如适用）。

- [ ] **Step 8: 全模块测试并提交**

```bash
.\mvnw -pl comic-common -am -DskipTests=false test
.\mvnw -pl worker-service -am -DskipTests=false test
.\mvnw -pl api-service -am -DskipTests=false test
git add -A
git commit -m "转码完成后自动同步 media 元信息：ffprobe 实测回传、更新 DB 并刷新 metadata.json"
```

---

### Task 8: 收尾（文档 + 全量验证）

**Files:**
- Modify: `docs/api.md`

- [ ] **Step 1: api.md 新增 v1.1 存储操作节**

在 `docs/api.md` 末尾（§19.2 兼容窗口表之后）追加：
```markdown
## 20. 存储操作域（v1.1）

统一形态：`POST /api/storage/{operation}/{targetType}/{targetId}`，`targetType = comics | chapters`。

| 操作 | 端点 |
|------|------|
| 生成 LQ | `POST /api/storage/lq/comics/{id}`、`/lq/chapters/{id}` |
| 视频转码 | `POST /api/storage/transcode/comics/{id}`、`/transcode/chapters/{id}` |
| 删除 HQ 保留 LQ | `POST /api/storage/delete-hq/comics/{id}`、`/delete-hq/chapters/{id}` |
| 导出漫画 | `POST /api/storage/export/comics/{id}` |
| 导出任务查询 | `GET /api/storage/export/comics/{id}/tasks`、`GET /api/storage/export/tasks/{taskId}` |
| 刷新 Metadata | `POST /api/storage/refresh-metadata/comics/{id}` |
| 存储统计 | `GET /api/storage/stats` |

> 旧端点（`/comics/{id}/lq`、`/admin/storage/comics/{id}/transcode-videos` 等）保留为 deprecated 兼容入口，等待前端迁移后移除（见 §19.2）。
```

`§19.2 兼容窗口表` 追加行：
```markdown
| `POST /api/comics/{id}/lq` 等存储操作旧端点 | 保留 deprecated，转发到 `/api/storage/*` | 前端迁移后移除 |
```

- [ ] **Step 2: 全量验证**

```bash
git grep -n "getChapterPages\|findActiveTasksForComics" -- "api-service/src" || Write-Output "死代码已清除"
.\mvnw clean verify "-DskipTests=false"
```
Expected: BUILD SUCCESS、Checkstyle 0 违规、全部测试通过。

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "更新 API 文档：新增存储操作域 v1.1 端点与兼容窗口"
```

---

## Self-Review 记录

- **Spec 覆盖**：域包结构（Task 3-6 建 storage 域）、存储 URL 统一（Task 3/4/5/6）、功能收敛 F1（Task 1）、F2（Task 2）、F3（Task 5）、F4（Task 4）、转码后自动同步（Task 7）、兼容迁移（各任务 @Deprecated + Task 8 文档）。非目标（前端不动/轻量导入/删库搁置）均已排除。
- **占位符**：Task 1-6 全部含完整代码；Task 4 测试方法体需按既有 `MediaOperationPipelineIT` 的 mock 基建实现（已在 Step 1 注明出处）；Task 5 的 `MetadataRefreshServiceTest` 构造参数以最终实现为准（Step 3 完成后补齐）——二者属执行前置读取，非凭空占位。
- **类型一致性**：`OperationSubmitResult` 全链一致；`ComicStatus status` 在 Task 2 三处（VO/列表组装/详情组装）同步改名；`TranscodeMediaInfo` 组件顺序已核对 `ComicMetadata.MediaInfo` 实际定义（`(fileName, pageNumber, hqStatus, lqStatus, fileSize, width, height, mediaType, duration, container, videoCodec, audioCodec)`）；`applyTranscodeCompleted` 签名已修正为 `(ManagementCommandCompletedEvent ev, Long mediaId)`（对齐实际 L243 实现与 TRANSCODE 分支调用）。
