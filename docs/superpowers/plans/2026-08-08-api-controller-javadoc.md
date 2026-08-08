# api-service Controller 注释补齐实施计划

**状态**: 待执行

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `com/comicatlas/api` 下全部 24 个 Controller 补齐规范 Javadoc（类级完整 + 方法级简明），按阿里开发标准。纯注释变更，零业务逻辑。

**Architecture:** 按业务域拆 6 批（阅读/漫画/导入/存储/管理/上传设置），每批独立提交 + 编译门禁 + 覆盖检查。

**Design spec:** `docs/superpowers/specs/2026-08-08-api-controller-javadoc-design.md`

## Global Constraints

- **不改任何业务逻辑**：URL 映射、方法签名、参数、返回类型一律不动；只新增/升级 Javadoc 注释。
- **注释规范**（阿里"简明 Javadoc"）：
  - 类级：职责一句话 + `<p>` URL 基路径约定 + 关键业务语义（软删除/异步/幂等）；跨类用 `{@link}`。
  - 方法级：业务动作 + 约束一句话 + 必要 `@param`/`@return`（仅参数不直观时）。
  - 解释业务原因不重复代码；中文注释；不补作者/日期；不新增 `//` 行内注释。
  - 已有单行注释（`/** xxx */`）升级为规范格式，保留原语义。
- 每批 `git status` 确认只含本批文件；提交信息中文"动作 + 内容"。
- 参考范例：`StorageOperationController`（完整 Javadoc 基准）。

---

### Task 1: 阅读域（2 控制器）

**Files (Modify):**
- `reader/controller/ReaderController.java`（当前零注释）
- `reader/controller/HistoryController.java`

- [ ] **Step 1: ReaderController**
- 类 Javadoc：阅读接口，基路径 `/api`，`GET /chapters/{id}` 返回章节阅读数据（pages + prev/next）。
- `getChapter` 方法 Javadoc：加载章节阅读页，`@param id` 章节 ID，`@return` ReaderDTO。
- [ ] **Step 2: HistoryController**
- 类 Javadoc：阅读历史接口（列表/更新/获取）。
- 4 个映射方法逐个补简明 Javadoc。
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/reader
git commit -m "补充 API 注释：阅读域控制器 Javadoc（Reader/History）"
```

---

### Task 2: 漫画域（6 控制器）

**Files (Modify):**
- `comic/controller/ComicController.java`（12 映射仅 4 注释，重点）
- `comic/controller/CatalogController.java`
- `comic/controller/CatalogManagementController.java`（已有类 Javadoc，补方法级）
- `comic/controller/CategoryController.java`
- `comic/controller/ChapterManagementController.java`（已有类 Javadoc，补方法级）
- `comic/controller/TagController.java`

- [ ] **Step 1: ComicController（重点）**
- 类 Javadoc：漫画 CRUD，基路径 `/api`；删除走软删除（进回收站）返回管理任务引用；批量更新约束（categoryId 或 addTagIds 至少其一）。
- 12 个映射方法逐个补简明 Javadoc（listComics/createComic/updateComic/getComic/deleteComic/getMetadata/updateMetadata/getComicTags/updateComicTags/batchUpdate/autocompleteTitles）。已有 4 个单行注释升级。
- [ ] **Step 2: 其余 5 控制器**
- CatalogController/CategoryController/TagController：类 Javadoc + 方法级。
- CatalogManagementController/ChapterManagementController：已有类 Javadoc 保留，补方法级。
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/comic
git commit -m "补充 API 注释：漫画域控制器 Javadoc（Comic/Catalog/Category/Tag 等）"
```

---

### Task 3: 导入域（3 控制器）

**Files (Modify):**
- `importer/controller/ImportController.java`
- `importer/controller/DirectoryScanTaskController.java`
- `importer/controller/RecoveryTaskController.java`

- [ ] **Step 1: 三个控制器逐个补注释**
- ImportController 类 Javadoc：导入任务接口，基路径 `/api/tasks/import`；创建/列表/批量/取消/重试语义。
- 8 个映射方法（createTask/listTasks/createBatch/getTask/getTaskStatus/cancelTask/retryTask）简明 Javadoc；`@param` 覆盖分页/筛选参数。
- DirectoryScanTaskController/RecoveryTaskController 类 Javadoc + 方法级。
- [ ] **Step 2: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/importer
git commit -m "补充 API 注释：导入域控制器 Javadoc（Import/Scan/Recovery）"
```

---

### Task 4: 存储域（5 控制器）

**Files (Modify):**
- `storage/controller/StorageOperationController.java`（已有完整 Javadoc，补缺失方法级）
- `storage/controller/StorageStatsController.java`
- `admin/controller/AdminController.java`
- `admin/controller/AdminDlqController.java`
- `admin/controller/AdminStorageController.java`

- [ ] **Step 1: StorageOperationController**
- 类 Javadoc 已有，保持；补 13 个映射方法中缺失的方法级注释。
- [ ] **Step 2: StorageStats + Admin 三控制器**
- StorageStatsController 类 Javadoc + 方法级。
- AdminController/AdminDlqController/AdminStorageController：类 Javadoc + 方法级（注意 admin 接口为管理端内部接口，注明"仅供本机管理端使用"）。
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/storage api-service/src/main/java/com/comicatlas/api/admin
git commit -m "补充 API 注释：存储域控制器 Javadoc（Storage/Admin）"
```

---

### Task 5: 管理域（5 控制器）

**Files (Modify):**
- `management/controller/ManagementTaskController.java`（已有类 Javadoc，补方法级）
- `management/controller/MediaOperationController.java`（同上）
- `management/controller/OutboxStatsController.java`（同上）
- `management/trash/TrashLifecycleController.java`（同上）
- `management/batch/controller/BatchOperationController.java`（同上）

- [ ] **Step 1: 五个控制器补方法级注释**
- ManagementTaskController（7 映射）：任务中心列表/详情/进度/取消/重试。
- MediaOperationController（4 映射）：媒体操作（LQ/HQ/转码等）允许操作查询。
- OutboxStatsController（2 映射）：Outbox 积压统计。
- TrashLifecycleController（9 映射）：回收站恢复/清理/对账/7 天保留期语义。
- BatchOperationController（3 映射）：批量操作预览/提交。
- 类 Javadoc 已有者保持，缺失者补。
- [ ] **Step 2: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/management
git commit -m "补充 API 注释：管理域控制器 Javadoc（Task/Trash/Batch 等）"
```

---

### Task 6: 上传/设置域（3 控制器）

**Files (Modify):**
- `upload/controller/UploadController.java`（已有类 Javadoc，补方法级）
- `upload/controller/MediaManagementController.java`（同上）
- `settings/controller/SettingsController.java`

- [ ] **Step 1: 三个控制器补注释**
- UploadController（6 映射）：分块上传会话/文件/完成语义。
- MediaManagementController（2 映射）：媒体管理（重排等）。
- SettingsController（3 映射）：设置读写。
- [ ] **Step 2: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests
git add api-service/src/main/java/com/comicatlas/api/upload api-service/src/main/java/com/comicatlas/api/settings
git commit -m "补充 API 注释：上传与设置控制器 Javadoc（Upload/Settings）"
```

---

### Task 7: 收尾验证

- [ ] **Step 1: 覆盖检查**
- 脚本统计 24 个 Controller：类级 Javadoc 覆盖率 + 映射方法 Javadoc 覆盖率，应 100%。
```powershell
$controllers = Get-ChildItem api-service/src/main/java/com/comicatlas/api -Recurse -Filter "*Controller.java"
foreach ($c in $controllers) { $content = Get-Content $c.FullName -Raw; ... }
```
- [ ] **Step 2: 全量门禁**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests   # exit 0
# 抽查: git diff 确认各批仅注释变更（无逻辑 diff）
git log --oneline -7
git status --short   # 干净
```
- [ ] **Step 3: 汇总**
- 输出 6 批提交清单 + 覆盖率统计 + 编译结果到最终报告。
