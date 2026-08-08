# api-service Controller 注释补齐设计

**日期**: 2026-08-08
**状态**: 设计待审阅
**范围**: `com/comicatlas/api` 下全部 24 个 Controller 的 Javadoc 补充（纯注释变更，零业务逻辑变更）

## 背景与目标

`api-service/.../com/comicatlas/api` 下 24 个 Controller 注释覆盖严重不均：11 个缺类级 Javadoc，大量映射方法无注释（如 `ComicController` 12 个映射仅 4 个有单行注释）。对照《阿里巴巴 Java 开发手册》"公共 API 必须有简明 Javadoc"及项目 AGENTS.md"注释解释业务原因、约束和状态转换，不重复代码"，为全部 24 个 Controller 补齐规范注释。

**目标**：全部 Controller 类级 + 方法级 Javadoc 覆盖；**不改变任何业务逻辑、URL、参数、返回结构**。

## 现状分析（24 个 Controller）

| 业务域 | Controller | 类 Javadoc | 备注 |
|--------|-----------|-----------|------|
| reader | ReaderController、HistoryController | ❌×2 | ReaderController 整个类零注释 |
| comic | ComicController、CatalogController、CatalogManagementController、CategoryController、ChapterManagementController、TagController | ❌×4 ✅×2 | ComicController 12 映射仅 4 注释 |
| importer | ImportController、DirectoryScanTaskController、RecoveryTaskController | ❌×3 | |
| storage | StorageOperationController、StorageStatsController | ✅×2 | 有完整 Javadoc 可作范例 |
| admin | AdminController、AdminDlqController、AdminStorageController | ❌×3 | |
| management | ManagementTaskController、MediaOperationController、OutboxStatsController、TrashLifecycleController、BatchOperationController | ✅×5 | 类注释有但方法级缺失 |
| upload/settings | UploadController、MediaManagementController、SettingsController | ✅×2 ❌×1 | |

## 注释规范（统一模板）

### 类级 Javadoc（完整格式）

```java
/**
 * 漫画列表/详情查询接口。
 * <p>
 * 基路径 {@code /api}，提供漫画列表分页、详情、元数据、标签与批量更新。
 * 删除走软删除（进回收站），返回管理任务引用 {@link ManagementTaskResponse}。
 */
@RestController
@RequestMapping("/api")
public class ComicController {
```

模板要素：
- 第一句：类职责（一句话）
- `<p>` 后：URL 约定（基路径）+ 关键业务语义（软删除/异步/幂等等约束）
- 跨类协作用 `{@link}` 引用（如 `{@link StorageStatsController}`）

### 方法级 Javadoc（简明格式）

```java
/**
 * 分页查询漫画列表，支持标题/作者/标签/分类筛选。
 *
 * @param query 筛选与分页条件（keyword/categoryId/tagIds/page/size）
 * @return 分页结果 {@link ComicListVO}
 */
@GetMapping("/comics")
public Result<IPage<ComicListVO>> listComics(ComicListQuery query) {
```

模板要素：
- 第一句：业务动作 + 关键约束（"分页查询…支持…筛选"）
- `@param`：仅当参数语义不直观时补充（`query`/`dto`/`request` 类参数需说明用途；`id`/`comicId` 等直观参数可省）
- `@return`：返回类型 + 关键字段语义（不逐字段罗列）
- `@throws`：Controller 层一般不直接声明异常（由全局异常处理兜底），如方法有显式业务异常可注明

### 对齐原则（阿里规范）
- **解释业务原因、约束、状态转换，不重复代码**——禁止"拷贝方法体"式注释
- 已有单行注释（`/** 创建新漫画（初始 DRAFT） */`）**升级**为规范格式（补 @param/@return），保留原语义
- 中文注释；不写"作者/日期"（项目 AGENTS.md 未要求，与现有代码风格一致）
- 不新增 `//` 行内注释

## 变更设计（按业务域分 6 批）

| 批次 | 控制器 | 文件数 | 提交信息 |
|------|--------|--------|----------|
| 1 阅读域 | ReaderController、HistoryController | 2 | `补充 API 注释：阅读域控制器 Javadoc（Reader/History）` |
| 2 漫画域 | Comic、Catalog、CatalogManagement、Category、ChapterManagement、Tag | 6 | `补充 API 注释：漫画域控制器 Javadoc（Comic/Catalog/Category/Tag 等）` |
| 3 导入域 | Import、DirectoryScanTask、RecoveryTask | 3 | `补充 API 注释：导入域控制器 Javadoc（Import/Scan/Recovery）` |
| 4 存储域 | StorageOperation、StorageStats、Admin、AdminDlq、AdminStorage | 5 | `补充 API 注释：存储域控制器 Javadoc（Storage/Admin）` |
| 5 管理域 | ManagementTask、MediaOperation、OutboxStats、TrashLifecycle、BatchOperation | 5 | `补充 API 注释：管理域控制器 Javadoc（Task/Trash/Batch 等）` |
| 6 上传/设置 | Upload、MediaManagement、Settings | 3 | `补充 API 注释：上传与设置控制器 Javadoc（Upload/Settings）` |

每批独立提交 + 编译验证 + git status 确认只含本批文件。

## 不做的事（YAGNI）

- 不改 Controller 外的注释（Service/DTO/Mapper 等不在本次范围）
- 不改任何业务逻辑、URL 映射、参数、返回类型
- 不新增 Controller 文件、不合并/拆分方法
- 不补"作者/日期"行（AGENTS.md 未要求）
- 不修改前端或其他模块

## 验证策略

1. **编译门禁**：每批 `.\mvnw -q -pl api-service -am compile -DskipTests` exit 0
2. **覆盖检查**：脚本统计 24 个 Controller 的类级 Javadoc 与映射方法 Javadoc 覆盖率，最终 100%
3. **残留检查**：`git diff` 确认每批仅注释变更（`-` 行仅出现在被替换的旧注释）
4. **测试**：注释变更无逻辑影响，跑一次全量 compile + 关键测试（可选，验证无回归）

## 提交规划

按"一个提交一个完整问题"拆 6 个提交（每业务域一个），全部完成后汇总。

## 参考范例

- `StorageOperationController`（已有完整类 Javadoc + 方法注释，作为格式基准）
- `docs/superpowers/specs/2026-08-08-comic-common-restructure-design.md`（同类整理任务的 spec 格式）
