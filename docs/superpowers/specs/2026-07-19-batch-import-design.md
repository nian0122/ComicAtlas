# 批量导入功能设计

**日期**: 2026-07-19
**状态**: 已确认

## 1. 目标

为 ComicAtlas 添加批量导入功能，支持一次导入父目录下的多个漫画子目录。每个子目录导入为 1 个独立漫画。

## 2. API 设计

### 2.1 `GET /api/tasks/import/scan`

扫描父目录，返回所有子目录信息。

**请求**:
```
GET /api/tasks/import/scan?parentPath=F:/games/comics/...&sourceType=DIRECTORY
```

**响应**:
```json
{
  "parentPath": "F:/games/comics/...",
  "total": 35,
  "items": [
    {
      "name": "(C106) [trick&treat] ...",
      "path": "F:/games/comics/.../(C106)...",
      "imageCount": 162
    }
  ]
}
```

**行为**:
- 纯文件系统扫描，不走 DB
- `Files.list(parentPath)` → 过滤子目录 → 统计 imageCount（全量 `count()`，不设上限）
- `imageCount` 只计图片扩展名（jpg/jpeg/png/webp/bmp/gif）
- 不做图片过滤（用户保证目录内容正确）

### 2.2 `POST /api/tasks/import/batch`

为选中的子目录批量创建导入任务。

**请求**:
```json
{
  "sourceType": "DIRECTORY",
  "sourcePaths": [
    "F:/games/comics/.../dir1",
    "F:/games/comics/.../dir2"
  ]
}
```

**响应**:
```json
{
  "batchId": "uuid",
  "total": 35,
  "succeeded": [
    { "taskId": 1, "comicId": 1, "title": "...", "status": "PENDING" }
  ],
  "failed": [
    { "sourcePath": "F:/.../dirX", "errorMessage": "路径不存在" }
  ]
}
```

**行为**:
- 生成 `batchId = UUID`
- 每个 `sourcePath` → 独立事务创建 comic + import_task → 事务提交后发 MQ
- 单个失败不影响其他：catch → 记录到 `failed` 列表 → `continue`
- 复用现有 `createImportTask` 的核心逻辑（标题推断、MQ 发布）

### 2.3 现有端点增强

`GET /api/tasks/import` 新增可选参数：

| 参数 | 类型 | 说明 |
|------|------|------|
| `batchId` | `String` | 按批次 ID 筛选任务 |

## 3. 数据库变更

```sql
ALTER TABLE import_task ADD COLUMN batch_id VARCHAR(36) DEFAULT NULL;
CREATE INDEX idx_import_task_batch_id ON import_task(batch_id);
```

- 可空，单个导入不受影响
- 批量导入时同一批共享一个 UUID

## 4. DTO

| DTO | 字段 |
|-----|------|
| `BatchImportRequest` | `sourceType: String`, `sourcePaths: List<String>` |
| `BatchImportResultVO` | `batchId: String`, `total: int`, `succeeded: List<ImportTaskVO>`, `failed: List<FailedItem>` |
| `FailedItem` | `sourcePath: String`, `errorMessage: String` |
| `ScanItemVO` | `name: String`, `path: String`, `imageCount: int` |

## 5. 后端实现

### 5.1 ImportService 接口新增方法

```java
List<ScanItemVO> scanDirectories(String parentPath, String sourceType);
BatchImportResultVO createBatchImportTasks(BatchImportRequest request);
```

### 5.2 ImportServiceImpl 实现要点

**`scanDirectories`**:
- `Files.list(parentPath)` 遍历一级子目录
- 每个子目录统计 `imageCount`：`Files.list(dir).filter(图片扩展名).count()`，全量不设上限
- `Files.list().count()` 在 NTFS 上是目录项遍历，2000+ 文件也只需数十毫秒

**`createBatchImportTasks`**:
- 生成 UUID 作为 `batchId`
- 遍历 `sourcePaths`，每个 path 调用独立的 `@Transactional` 方法（避免循环内自调用导致 AOP 失效，所有迭代共享同一事务）
- 独立事务方法：预创建 comic + import_task + `task.setBatchId(batchId)` → 事务提交后发 MQ
- 单个失败 → `try/catch` → 记录到 `failed` 列表 → 继续下一个
- 所有任务创建完毕 → 返回汇总结果

### 5.3 ImportController

```java
@GetMapping("/scan")
Result<List<ScanItemVO>> scan(@RequestParam String parentPath,
                               @RequestParam(defaultValue = "DIRECTORY") String sourceType);

@PostMapping("/batch")
Result<BatchImportResultVO> createBatch(@RequestBody BatchImportRequest request);
```

### 5.4 文件清单

| 文件 | 变更 |
|------|------|
| `api-service/.../dto/ScanItemVO.java` | 新增 |
| `api-service/.../dto/BatchImportRequest.java` | 新增 |
| `api-service/.../dto/BatchImportResultVO.java` | 新增 |
| `api-service/.../dto/FailedItem.java` | 新增 |
| `api-service/.../service/ImportService.java` | 新增 2 个方法签名 |
| `api-service/.../service/impl/ImportServiceImpl.java` | 实现 2 个方法 |
| `api-service/.../controller/ImportController.java` | 新增 2 个端点 |
| `api-service/.../mapper/ImportTaskMapper.java` | 扩展查询支持 `batchId` 参数 |
| DB migration | `batch_id` 字段 |

## 6. 前端实现

### 6.1 交互流程

1. ImportPage 新增「批量导入」入口（按钮/标签切换）
2. 进入批量模式 → 输入父目录路径 → 点击「扫描」
3. GET `/scan` 加载列表 → 展示可勾选目录（checkbox + 名称 + 图片数）
4. 勾选/排除 → 点击「确认导入」
5. POST `/batch` → 成功后跳转 TaskPage（URL 带 `?batchId=xxx`）
6. TaskPage 按 batchId 筛选展示这批任务（可切换回全部）

### 6.2 组件改动

| 文件 | 变更 |
|------|------|
| `ImportPage.vue` | 新增批量模式面板：父目录输入 + 扫描按钮 + 勾选列表 + 确认按钮 |
| `TaskPage.vue` | 支持 `batchId` URL 参数筛选；加「返回全部」链接 |
| `stores/management/import.ts` | 新增 `scan()`、`createBatch()` action |
| `services/api.ts` | 新增 `importApi.scan()`、`importApi.createBatch()` |
| `types/index.ts` | 新增 `ScanItemVO`、`BatchImportResultVO` 接口 |

## 7. 不涉及的范围

- 不改变现有单次导入逻辑
- 不引入并发控制/限流（Worker 已基于 RabbitMQ 天然异步消费）
- 不引入线程池（逐个顺序创建 task，MQ 异步处理）
- 不改动 Worker 层（每个 task 独立处理，无感知）
