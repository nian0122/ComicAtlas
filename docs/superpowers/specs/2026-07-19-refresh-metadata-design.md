# Refresh Metadata — 设计规范

**日期**：2026-07-19
**状态**：Frozen
**版本**：1.0

---

## 1. 目标

为编辑页提供**单漫画元数据刷新**功能，不依赖全局 `rebuild`，comic.id 不变，用户维护数据（阅读历史/标签/分类）永久保留。

---

## 2. 核心原则

| # | 原则 |
|---|------|
| P1 | Refresh 以导入时生成的元数据快照为数据源（Phase 1：`D:/manga/metadata/{comicId}.json`，Phase 2 切换至 HQ Package） |
| P2 | comic.id 不变，阅读历史/标签/分类永久保留 |
| P3 | title/author/category 等用户可编辑字段默认保留，不被 metadata 覆盖 |
| P4 | catalog/chapter/page 执行 Replace（实现：DELETE + INSERT） |
| P5 | RestorePolicy 负责"恢复哪些字段"，RestoreContext 负责"恢复场景"——两者正交 |
| P6 | 操作幂等：多次刷新结果一致（catalog/chapter/page 由 metadata 决定，确定性恢复） |
| P7 | 并发安全：同一 comic 同时仅允许一个刷新任务执行 |

---

## 3. API 定义

### 端点

```
POST /api/admin/comics/{comicId}/refresh-metadata
```

- 同步执行（Phase 1）
- Comic 已存在且状态为 READY
- metadata.json 必须存在且可解析

### 成功响应 (200)

```json
{
  "code": 200,
  "data": {
    "comicId": 123,
    "status": "READY",
    "catalogs": 4,
    "chapters": 120,
    "pages": 3200,
    "durationMs": 820,
    "refreshedAt": "2026-07-19T15:32:18"
  }
}
```

### 失败响应

| HTTP | message | 场景 |
|------|---------|------|
| 404 | 漫画不存在 | comicId 无效 |
| 409 | 漫画状态异常，当前状态: IMPORTING | status ≠ READY |
| 409 | 该漫画正在刷新中 | CAS 锁获取失败 |
| 422 | 元数据快照不存在: {path} | metadata.json 缺失 |
| 422 | 元数据快照解析失败: {error} | JSON 解析异常 |
| 500 | 刷新元数据失败 | 恢复过程异常 |

---

## 4. 数据模型

### 4.1 RestorePolicy

```java
public enum RestorePolicy {
    /**
     * 全量导入 — 覆盖 comic 所有字段
     */
    IMPORT,
    /**
     * 编辑页刷新 — 仅刷新解析数据，
     * 保留用户维护的 title/author/category
     */
    REFRESH_METADATA
}
```

### 4.2 RestoreSource

```java
public enum RestoreSource {
    METADATA,      // metadata.json (Phase 1)
    HQ_PACKAGE,    // HQ Package (Phase 2)
    IMPORT,        // 正常导入
    SCAN           // 扫描恢复
}
```

### 4.3 RestoreContext

```java
public record RestoreContext(
    Long comicId,
    boolean comicExists,
    RestorePolicy policy,
    RestoreSource source
) {}
```

### 4.4 RefreshMetadataResult

```java
public record RefreshMetadataResult(
    Long comicId,
    String status,
    int catalogs,
    int chapters,
    int pages,
    long durationMs,
    LocalDateTime refreshedAt
) {}
```

### 4.5 ComicStatus

新增枚举值 `REFRESHING`：

```java
public enum ComicStatus {
    IMPORTING,
    READY,
    REFRESHING,   // 新增
    DELETING,
    DELETED,
    RESCANNING
}
```

---

## 5. 字段恢复矩阵

| 字段 | IMPORT | REFRESH_METADATA |
|------|--------|------------------|
| title | metadata | **保留 DB 值** |
| author | metadata | **保留 DB 值** |
| category | metadata | **保留 DB 值** |
| catalog | Replace | Replace |
| chapter | Replace | Replace |
| page | Replace | Replace |
| pageCount | 写入 | 写入 |
| fileSize / hqSize | 写入 | 写入 |
| cover | 生成 | **保留 DB 值**（metadata.json 不含 cover） |
| tags | — | **保留 DB 值** |

---

## 6. Service 层设计

### 6.1 refreshMetadata() 流程

```
1. Comic comic = comicMapper.selectById(id)
   → null → 404
   → status ≠ READY → 409

2. 获取分布式锁：UPDATE comic SET status='REFRESHING'
   WHERE id=? AND status='READY'
   → affected rows = 0 → 409

   long start = System.currentTimeMillis();
   try {

3.     读取 & 校验 metadata/{comicId}.json
       → 不存在 → 422 METADATA_NOT_FOUND
       → JSON 异常 → 422 METADATA_CORRUPT

4.     Map<String, Object> stats = transactionTemplate.execute(status -> {
           replaceCatalogChapterPage(comicId);
           return restoreComicInternal(metadata,
               new RestoreContext(comicId, true,
                   RestorePolicy.REFRESH_METADATA, RestoreSource.METADATA));
       });

5.     long durationMs = System.currentTimeMillis() - start;
       return buildResult(comicId, stats, durationMs);

   } finally {
6.     释放锁（仅更新 status → READY，不写其他字段）
   }
```

### 6.2 restoreComic() 改造

```java
// 旧签名（兼容现有调用方，默认 IMPORT）
private Map<String, Object> restoreComic(Map<String, Object> metadata, Long comicId) {
    return restoreComic(metadata,
        new RestoreContext(comicId, false, RestorePolicy.IMPORT, RestoreSource.METADATA));
}

// 新签名
private Map<String, Object> restoreComic(Map<String, Object> metadata, RestoreContext ctx) {
    return transactionTemplate.execute(status -> {
        try {
            return restoreComicInternal(metadata, ctx);
        } catch (Exception e) {
            throw new RuntimeException("恢复失败: comicId=" + ctx.comicId(), e);
        }
    });
}

// 核心逻辑：ctx.comicExists() 决定 INSERT vs UPDATE
// ctx.policy() 决定哪些字段可以写
// 返回 Map: { "catalogs": N, "chapters": N, "pages": N }
private Map<String, Object> restoreComicInternal(Map<String, Object> metadata, RestoreContext ctx);
```

### 6.3 并发锁

```java
// 获取
int updated = comicMapper.update(null,
    new LambdaUpdateWrapper<Comic>()
        .eq(Comic::getId, comicId)
        .eq(Comic::getStatus, ComicStatus.READY)
        .set(Comic::getStatus, ComicStatus.REFRESHING));

// 释放（finally 块，仅更新 status，不写其他列）
comicMapper.update(null,
    new LambdaUpdateWrapper<Comic>()
        .eq(Comic::getId, comicId)
        .set(Comic::getStatus, ComicStatus.READY));
```

**约束**：
- 锁释放必须在 finally 中，与事务成功失败无关。
- 释放时只能更新 `status` 字段，不能用 `comicMapper.updateById(comic)`——防止事务回滚后 comic 对象携带脏数据污染 DB。

### 6.4 Controller

```java
@PostMapping("/comics/{comicId}/refresh-metadata")
public Result<RefreshMetadataResult> refreshMetadata(@PathVariable Long comicId) {
    return Result.success(adminService.refreshMetadata(comicId));
}
```

Controller 不含业务逻辑。

---

## 7. 涉及文件

| 文件 | 变更类型 |
|------|----------|
| `AdminController.java` | 新增端点 |
| `AdminService.java` | 新增方法签名 |
| `AdminServiceImpl.java` | 新增 refreshMetadata() / 改造 restoreComic() |
| `ComicStatus.java` | 新增 REFRESHING |
| `RestorePolicy.java` | **新文件** |
| `RestoreContext.java` | **新文件** |
| `RestoreSource.java` | **新文件** |
| `RefreshMetadataResult.java` | **新文件**（record） |
| `frontend/src/services/api.ts` | 新增 `adminApi.refreshMetadata(id)` |
| `frontend` 编辑页 | 按钮指向新 API |

---

## 8. 已知限制

| 限制 | 计划 |
|------|------|
| 仅支持 metadata.json 数据源 | Phase 2 切换至 HQ Package |
| 同步执行 | Phase 2 可扩展为异步任务模型 |
| 不支持选择性覆盖 title/author | Phase 2 引入用户偏好选项 |
