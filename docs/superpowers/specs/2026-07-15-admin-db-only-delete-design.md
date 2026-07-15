# 后台管理：仅删除数据库漫画记录

**Date:** 2026-07-15  
**Topic:** admin-db-only-delete  
**Status:** 待实现

## 1. 目标

为 ComicAtlas 后台管理提供一个安全的「仅删除数据库记录」能力：

- 删除漫画在数据库中的所有业务关联数据
- **不删除任何本地文件**（HQ / LQ / 缩略图 / 原档）
- **不经过 RabbitMQ，不调用 Worker**
- 保留 `import_task` 导入日志，便于后续排查
- 删除前检查是否存在运行中的导入任务，防止与 Worker 并发修改数据库

## 2. 使用场景

- 数据库损坏后清理单本漫画记录
- 测试导入时重置数据库
- 调试 metadata / rebuild 流程
- 重新扫描并导入已有 HQ 文件

## 3. API 设计

### 3.1 新增管理接口

```http
DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY
```

**参数：**

| 参数 | 必填 | 取值 | 说明 |
|---|---|---|---|
| `mode` | 是 | `DATABASE_ONLY` | 当前仅支持数据库删除模式 |

**行为：**

1. 校验 `mode` 参数，当前仅支持 `DATABASE_ONLY`，其他值返回 `400 Bad Request`
2. 校验漫画存在，不存在返回 `404 Not Found`
3. 检查该漫画是否存在运行中的导入任务（即 `import_task` 状态为未结束状态，具体由 `ImportTaskStatus` 枚举定义），存在则返回 `409 Conflict`
4. 在单个事务内删除漫画及其所有业务关联数据
5. **不删除 `import_task` 记录**
6. **不发送 MQ 事件，不调用 Worker，不触碰本地文件**
7. 返回删除统计

### 3.2 响应格式

```json
{
  "code": 0,
  "data": {
    "comic": 1,
    "catalog": 5,
    "chapter": 32,
    "page": 687,
    "tag": 8,
    "history": 4
  }
}
```

DTO 定义：

```java
@Data
public class ComicDeleteStats {
    private int comic;
    private int catalog;
    private int chapter;
    private int page;
    private int tag;
    private int history;
}
```

字段说明：

| 字段 | 含义 |
|---|---|
| `comic` | 删除的漫画主记录数 |
| `catalog` | 删除的目录记录数 |
| `chapter` | 删除的章节记录数 |
| `page` | 删除的页面记录数 |
| `tag` | 删除的标签关联记录数 |
| `history` | 删除的阅读历史记录数 |

### 3.3 错误码

| HTTP 状态 | 场景 |
|---|---|
| `404 Not Found` | 漫画不存在 |
| `409 Conflict` | 该漫画存在运行中的导入任务 |
| `500 Internal Server Error` | 数据库删除异常 |

## 4. 后端设计

### 4.1 复用 AdminController

```java
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService adminService;

    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuildFromHq() { ... }

    @DeleteMapping("/comics/{id}")
    public Result<ComicDeleteStats> deleteComic(
            @PathVariable Long id,
            @RequestParam String mode) {
        return Result.ok(adminService.deleteComic(id, mode));
    }
}
```

### 4.2 Service 命名

```java
public interface AdminService {
    Map<String, Object> rebuildFromHq();
    ComicDeleteStats deleteComic(Long comicId, String mode);
}
```

实现类 `AdminServiceImpl` 中新增：

```java
@Override
@Transactional
public ComicDeleteStats deleteComic(Long comicId, String mode) {
    // 1. 校验 mode 参数，当前仅支持 DATABASE_ONLY
    // 2. 校验漫画存在
    // 3. 检查运行中的导入任务
    // 4. 按依赖顺序删除业务关联数据
    // 5. 删除漫画主记录
    // 6. 返回统计
}
```

### 4.3 删除顺序

按外键依赖顺序删除：

1. `page`（按 chapter_id）
2. `chapter`（按 comic_id）
3. `catalog`（按 comic_id）
4. `comic_tag`（按 comic_id）
5. `reading_history`（按 comic_id）
6. `comic`（按 id）

> `import_task` 属于导入日志，**不删除**。
>
> 删除顺序依据当前数据库外键依赖设计。如后续 Schema 调整（例如新增 `comic_category` / `comic_author` 等关联表），应同步调整删除顺序。

### 4.4 运行中任务检查

检查条件：

```sql
SELECT COUNT(*) FROM import_task
WHERE comic_id = {comicId}
  AND status IN (<未结束状态集合>)
```

> 具体状态集合由 `ImportTaskStatus` 枚举定义，包含所有非终态（如 `PENDING`、`IMPORTING`、`PROCESSING`、`RETRYING` 等）。Spec 不绑定具体枚举值，代码实现时引用枚举定义。
>
> 使用 MyBatis Plus LambdaQueryWrapper 按 `comic_id` 查询。`import_task` 表已存在 `comic_id` 字段。

若存在未结束任务，抛出 `409 Conflict` 业务异常，提示：

> 该漫画存在运行中的导入任务，请等待任务完成后再删除数据库记录。

## 5. 前端设计

### 5.1 入口位置

在现有 **Dashboard 页面**（`/dashboard`）底部新增「数据库维护」区域。

理由：

- 项目目前没有独立 Admin 页面
- Dashboard 已从导航隐藏，性质上属于管理后台
- 避免在 ComicDetail 放置危险操作，降低普通用户误触风险

### 5.2 UI 布局

```
┌─────────────────────────────────┐
│  数据库维护                     │
│  ─────────────────────────────  │
│  Comic ID: [ 12 ]               │
│  标题:     海贼王                │
│  作者:     尾田荣一郎            │
│  状态:     READY                │
│                                 │
│  [ 删除数据库记录 ]              │
│                                 │
│  ⚠️ 仅删除数据库，保留本地文件   │
└─────────────────────────────────┘
```

### 5.3 交互流程

1. 管理员输入 Comic ID（仅允许正整数）
2. 前端调用 `GET /api/comics/{id}` 拉取漫画信息并展示
3. 如果 `comic.status` 为 `IMPORTING` / `PROCESSING` 等未结束状态，禁用「删除数据库记录」按钮并提示
4. 点击「删除数据库记录」按钮
5. 弹出二次确认弹窗
6. 确认后调用 `DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY`
7. 成功后展示返回的删除统计

> Comic ID 输入框建议使用 `<el-input-number>`，限制最小值为 1，只允许整数。

### 5.4 二次确认文案

```
确认删除数据库记录？

此操作会彻底删除该漫画在数据库中的业务数据，包括：
- 漫画信息
- 目录、章节、页面
- 标签关联
- 阅读历史

本地 HQ / LQ / 缩略图文件不会被删除，
之后可以使用「数据库重建」重新扫描本地文件并恢复漫画数据库记录。

注意：手动添加的标签、分类、阅读历史等数据库信息将不会恢复。

[取消]  [确认删除]
```

## 6. 测试策略

### 6.1 后端测试

- **正常删除：** 调用接口后， comic / catalog / chapter / page / comic_tag / reading_history 被删除，import_task 保留，本地文件保留
- **漫画不存在：** 返回 404
- **运行中任务：** 构造 `IMPORTING` 状态任务，返回 409
- **删除统计：** 返回各表删除数量，总和与预期一致
- **事务回滚：** 删除过程中异常，数据回滚一致

### 6.2 前端测试

- Dashboard 页面展示「数据库维护」区域
- 输入 Comic ID 后展示漫画信息
- 二次确认弹窗文案正确
- 成功后展示删除统计
- 输入无效 ID 时按钮禁用或提示

### 6.3 操作日志（推荐）

建议在 `operation_log` 表中记录一次后台数据库删除操作，包含：

- 操作类型：`DELETE_DATABASE_ONLY`
- 目标漫画 ID
- 操作时间

这有助于后续排查「为什么数据库记录消失」的问题。当前阶段可作为可选实现。

## 7. 边界情况

| 场景 | 处理 |
|---|---|
| 漫画不存在 | 返回 404 |
| 存在运行中导入任务 | 返回 409 |
| 本地文件已被手动删除 | 不影响，只操作数据库 |
| 该漫画无阅读历史 / 标签 | 对应统计为 0 |
| 删除过程中异常 | 事务回滚，返回 500 |

## 8. 设计原则

> **本接口仅维护数据库状态，不负责修复文件系统，也不保证数据库与文件系统一致性；如需恢复漫画，应使用数据库重建功能重新扫描本地文件。**

## 9. 注意事项

- 当前项目无 Spring Security，接口仅通过隐藏页面（Dashboard）暴露，不额外增加权限设计
- 不修改现有 `DELETE /api/comics/{id}` 行为
