# ComicAtlas 项目能力架构审核报告

- **审核日期**: 2026-08-10
- **审核快照**: snapshotId=`0f5d4640a69d8c19`（分支 `feature/lq-transcode-import-video-status`，HEAD=6765376）
- **审核范围**: 全项目（api-service / worker-service / comic-common / gateway / frontend / e2e / scripts / docs / compose）
- **审核标准**: 目标设计 `docs/architecture/09-media-lifecycle-capabilities.md` → 项目规则 `AGENTS.md`（含《阿里巴巴 Java 开发手册》边界）→ 当前代码+Flyway 协议 → 对外声明（README/api.md/user-guide.md/operations/releases）
- **方法**: Wave 0 冻结源快照 → Wave 1 并行 8 路只读静态审核 → Wave 2 全量门禁+显式 IT+隔离真实 QA → Wave 3 报告与证据清单；P0/P1 关键发现均经审计员一手核实源码调用链
- **审核员**: Sisyphus（Orchestration） + 8 路只读审核代理 + Momus 计划审查（计划 OKAY）

---

## 1. 执行摘要与总体结论

### 总体结论: **FAIL**

**判定依据（按计划 FAIL>BLOCKED>PASS 规则）**：

| # | 确认发现 | 级别 | 证据 |
|---|---------|------|------|
| 1 | **视频转码每次全新执行必然失败**（`TranscodeCommandHandler` 对 `.mp4.tmp` 临时文件 probe 时被 `MediaAnalyzer` 扩展名门禁拒绝 → 恒抛 IOException → 发布 failed） | **P0** | F6-09（一手核实调用链） |
| 2 | **批量 LQ（COMIC 目标）功能级不可用**（API 校验把 `targetId` 当 chapterId，跨章节媒体结果全部被拒） | **P1** | 审核新增发现（真实 QA 抓到，一手核实） |
| 3 | **HQ 删除误删视频文件**（Worker 删除全部媒体 HQ 文件，API 仅更新 IMAGE 状态 → 视频文件永久丢失而 DB 仍 READY） | **P1** | F6-26（一手核实） |
| 4 | **章节回收 manifest 用 globalOrder 定位文件**，与最终化 chapterId 布局不符 → 新布局漫画章节回收为静默 no-op 或错移文件 | **P1** | F3-01/F7-01（双路独立发现） |
| 5 | **导入后 metadata.json 与最终 DB 布局不一致**（globalOrder 布局残留，灾难恢复会恢复错误 hqPath） | **P1** | F6-17 |
| 6 | **StorageRoot/ApiStorageRoot 路径校验为纯词法**，不感知 symlink/junction → 可越出存储根 | **P1** | F6-27（一手核实） |
| 7 | **视频元数据修复管线为孤儿**（VideoMetadataFixRequestedEvent 无生产方，能力不可触发） | **P1** | F6-10 |
| 8 | **前端错误处理静默吞掉全部业务错误**（axios 拦截器不校验 Result.code，后端恒 HTTP 200） | **P1** | F9-01 |
| 9 | **存储统计 totalBytes 被 @JsonIgnore**，前端"总大小"恒 0 B | **P1** | F9-02 |

> 任一确认 P0-P2 即总体 FAIL。本审核存在 **1 个 P0 + 8 个 P1**（另 P2×21、P3×57）。**项目缺陷导致 FAIL 仍代表审核任务成功**——证据与结论可信，且未整改任何代码。

### 正向结论（供整改优先级参考）
- 代码质量整体高：Worker 只读边界有实证（`WorkerDatabasePermissionIT`）、路径 containment 普遍采用 normalize+startsWith 词法校验、MQ 幂等（Inbox+Outbox+item CAS）设计完整、阿里规范 Checkstyle 0 违规、441 单测全绿。
- 多数核心链路（导入两阶段、ZIP 分卷、目录扫描、上传、回收站主链路、导出、元数据刷新）实现完整且测试覆盖充分。
- 无 UNSAFE 项（50/50 SAFE）——按"可信本机部署"边界判定。

---

## 2. 审核范围、标准与快照

### 2.1 源快照冻结（Todo 1, Wave 0）
- **snapshotId**: `0f5d4640a69d8c19`（最终基线；ordinal 排序可复算，consistent=True）
- 纳入 869 个源文件（四个 Maven 模块 + frontend/e2e/scripts/config/docs/tools + 根文件 + .omo/plans + .omo/drafts）
- 排除 node_modules/dist/target/logs/playwright-report 等生成目录 + 二进制/媒体扩展名 + `.omo/evidence` 自身
- 工具版本: java 21.0.2 / maven 3.9.12 / node v24.18.0 / pnpm 11.12.0 / go 1.22.1 / docker 29.1.5 / ffmpeg N-112664 / image-optimizer（worker-service/tools）
- **快照漂移处理**: QA 后重算发现漂移，根因为快照脚本未排除 `logs/`（运行时日志被误纳）；修复脚本重建基线，**源码 tracked 零改动**（git status 全程 0 行）
- 证据: `.omo/evidence/audits/2026-08-10-project-capability-architecture-audit/task-1/`

### 2.2 QA 隔离边界（preflight 结论：PASS）
- QA compose 项目 `comicatlas-qa` 与生产 `comicatlas` 完全隔离；`Stop-QaInfra` 仅销毁 QA 容器/临时 MANGA_ROOT
- QA 端口（Gateway 18000/Api 18010/Worker 18020/Nginx 18080/Nacos 18848+19848）全空闲；MySQL/Redis/RabbitMQ 用随机宿主端口
- 占用端口 3306/5672/6379/8848 为 `frpc.exe`（FRP 隧道→远端基础设施），非 QA 目标
- QA MANGA_ROOT = EvidenceDir/manga（唯一证据目录内临时根）

---

## 3. 能力三状态矩阵（Todo 2，50 项能力）

完整 50 行矩阵见 `.omo/evidence/audits/2026-08-10-project-capability-architecture-audit/task-2/matrix.md`。汇总：

| 状态 | 实现 | 验证 | 安全 |
|------|------|------|------|
| IMPLEMENTED / VERIFIED / SAFE | 46 | 42 | 50 |
| PARTIAL | 4（Category CRUD 无独立测试、EHENTAI 下载链路无测试、视频元数据修复孤儿、Compose 无 Worker） | — | 0 |
| NOT_VERIFIED | — | 7（Category/EHENTAI/对账/视频修复/Gateway 零测试/Nginx 无专项/Settings） | — |
| MISSING / UNSAFE | 0 | — | 0 |
| BLOCKED | — | 1（Compose——依赖真实容器） | — |

**关键覆盖结论**：
- 22 项必含能力全部入矩阵（漫画 CRUD/Catalog/Reader/History/搜索/Tag/Category/导入/扫描/取消/最终化/LQ/转码/刷新/metadata/统计/HQ 删除/上传/批量/回收站/灾难恢复/DLQ/Outbox/Gateway/Nginx/Compose/导出）
- 补 10 项（标签/批量导入/重试/允许操作/状态机/回收站事件闭环/MQ 拓扑/Worker 只读/设置/封面）
- 反向验证：无"仅有前端 client 无后端"的悬空接口

---

## 4. 命令与测试统计（Todo 10, Wave 2）

| 门禁 | 命令 | 结果 |
|------|------|------|
| 空白检查 | `git diff --check` | ✅ exit 0 |
| 全量构建+单测 | `.\mvnw.cmd -B verify -DskipTests=false` | ✅ BUILD SUCCESS（3:13min），**Tests run: 441, Failures 0, Errors 0, Skipped 1**（1 为平台跳过） |
| Checkstyle | verify 内置（5 模块） | ✅ 0 violations |
| Go 工具 | `go test ./...`（worker image-optimizer） | ✅ ok |
| 前端构建 | `pnpm --dir frontend run build` | ✅ exit 0（675KB chunk 警告非错误） |
| 前端 Playwright | `playwright test --config frontend/playwright.config.ts` | ✅ **28/28 passed**（9 spec，mock UI 套件） |
| 显式 IT | `mvnw -pl api-service,worker-service -am test -Dtest=*IT` | ⚠️ **244 run, 9 fail**（见 §6.2 测试过时） |
| 隔离真实 QA | `scripts/qa/run-management-e2e.ps1 -EvidenceDir .../task-10/qa-e2e` | ❌ **FAIL, 3 failures**（1 真实 P1 + 2 连锁） |
| 只读 Gate | `scripts/qa/final-gate.ps1` | ❌ FAIL（6 项：runner FAIL + 5 项 QA 提前 fatal 连锁缺失） |

**命令记录**: 全部命令含 argv/cwd/时间/exit/duration 记录于 `task-10/gate-records.md` 与 `task-10/logs/*.log` + 证据目录 `qa-e2e/`。

---

## 5. 发现清单（P0→P3 排序）

### 5.1 P0（阻断级，1 项）

#### F6-09 【P0 · HIGH 置信度 · 已一手核实】视频转码每次全新执行必然失败
- **位置**: `worker-service/.../command/TranscodeCommandHandler.java:105,120-129,182-185`；`worker-service/.../media/MediaAnalyzer.java:33,71-83`
- **根因链**: `resolveTempFile` 生成 `{taskId}-{itemId}-{attempt}-{mediaId}.mp4.tmp`（:182）→ `runFfmpeg` 写临时文件（:120）→ `probeFile(tempFile)`（:123）→ `MediaAnalyzer.analyzeVideo` 扩展名门禁（:76-78，`VIDEO_EXT={.mp4,.mkv,.webm,.mov,.avi}`）对 `xxx.mp4.tmp` 取 `.tmp` 不在集合 → 恒 `Optional.empty()` → `probeFile` 返回 null（:286）→ L124 抛 IOException → catch 发布 failed
- **影响**: **每次全新转码必然 failed，转码产物永不落库**。唯一成功路径是"确定性最终产物已存在且兼容"的幂等复用分支（:109-117，`.mp4` 能过门禁）
- **测试盲区**: `TranscodeCommandHandlerTest` mock 掉 `analyzeVideo`（恒兼容）；`MediaOperationPipelineIT:471-475` 直接投递假 completed 事件绕过真实 Worker/ffmpeg → 现有测试无法捕获（F6-15）
- **整改**: 临时文件改用兼容扩展名（如 `{base}.probe.mp4`）或 probe 前复制/改名；补充不 mock analyzeVideo 的真实转码测试

### 5.2 P1（严重级，8 项）

#### F6-26 【P1 · HIGH 置信度 · 已一手核实】HQ 删除误删视频文件，数据库与磁盘不一致
- **位置**: `HqDeleteCommandHandler.java:87-98`（遍历全部行无 mediaType 过滤）；`ExportMediaMapper.java:43-53`（SQL 含 VIDEO）；`MediaOperationCommandService.java:216-222`（仅 IMAGE 标记）；`ManagementCommandResultHandler.java:469-484`（仅 IMAGE 置 DELETED）
- **影响**: 视频 HQ 文件被删除而 DB `hq_status` 仍 READY → 文件不可恢复、`comic.hqSize`/统计虚高、阅读器播放指向已删文件失败。**数据丢失级缺陷**
- **整改**: Worker 侧过滤 `media_type='IMAGE'`（与 API 口径一致），或为 VIDEO 定义明确策略（目标设计 §11 L283"视频默认不参与 HQ 删除"）

#### F3-01 / F7-01 【P1 · HIGH 置信度 · 双路独立发现】章节回收 manifest 用 globalOrder 定位文件，与最终化 chapterId 布局不符
- **位置**: `TrashLifecycleService.java:118-121`（manifest 源路径 `comicId + "/" + chapter.getGlobalOrder()`）；对照 `ChapterIdStorageLayout.java:27-31` + `ImportStorageFinalizeHandler.java:119-121`（最终化 `hq/{comicId}/{globalOrder}` → `hq/{comicId}/{chapterId}`）
- **影响**: 新布局漫画章节回收时源路径不存在 → STATE_MISSING 视为成功 → 章节标 TRASHED 但文件未移入 TRASH；globalOrder 恰等于某章 chapterId 时可能**错移其他章文件**；章节永久清理后 HQ 文件永久残留。`TrashLifecycleIT` 手工将文件放 globalOrder 目录掩盖缺陷
- **整改**: manifest 按 DB 真实 hqPath（逐 media 或按 chapterId 目录）生成；补充"文件经最终化后回收"IT

#### F6-17 【P1 · HIGH 置信度】导入后 metadata.json 与最终 DB 布局不一致
- **位置**: `DirectoryImportHandler.java:138,180-188`（导入期写 globalOrder 布局）；`ImportPersistenceServiceImpl.java:305-311`（DB 修正 chapterId）；`ImportStorageFinalizeHandler`（最终化不重写 metadata.json）
- **影响**: `METADATA/{comicId}.json` 保持 globalOrder 布局直到下次转码/刷新重建；`RecoveryEngine.java:77` 按该文件恢复得到错误存储路径 → 灾难恢复缺陷
- **整改**: 全章节 READY 时经 Outbox 触发 `MetadataRefreshEvent`，或最终化后重写 `{comicId}.json`

#### F6-27 【P1 · HIGH(代码)/MEDIUM(场景) 置信度 · 已一手核实】StorageRoot/ApiStorageRoot 路径校验为纯词法，不感知 symlink/junction
- **位置**: `StorageRoot.java:27-36` + `ApiStorageRoot.java:27-36`（逐字节相同）：`resolve()` = `path.resolve(relative).normalize()` + `startsWith`，无 `toRealPath()`/`isSymbolicLink()`/`NOFOLLOW_LINKS`
- **影响**: `hq/{comicId}` 为 junction 指向根外时，删除/统计/读取可越出存储根（数据丢失面）。Windows 支持 junction；本机可信单用户 + 受控导入管道使实际触发概率低，但无纵深防御
- **整改**: resolve 增加 `toRealPath()` 后真实路径 containment 或拒绝中间 symlink 组件

#### F6-10 【P1 · HIGH 置信度】视频元数据修复管线为孤儿（无生产方）
- **位置**: `VideoMetadataFixHandler.java`（Worker 消费）；`MqRoutingKeys.java:30-31`；`MqQueues.java:23-24`；`VideoMetadataFixCompletedHandler.java`（API 消费）
- **证据**: 全仓库 `new VideoMetadataFixRequestedEvent(` 零匹配
- **影响**: 能力不可触发；`p.width IS NULL` 的视频无法补齐元数据
- **整改**: 接线生产方或下线该管线

#### F9-01 【P1 · HIGH 置信度】前端 axios 拦截器不校验 `Result.code`，业务错误全被静默吞掉
- **位置**: `frontend/src/services/api.ts:43-52`；`api-service/.../common/exception/GlobalExceptionHandler.java:17-59`（BusinessException 一律 `Result.fail` 无 `@ResponseStatus` → HTTP 200）；`Result.java:33-38`
- **证据**: 全前端 `grep "\.code|code ==="` 零命中；409（如"已有恢复任务"）实际 HTTP 200 + `code:409` → 前端 `catch` 不触发，`res.data=null` 走成功分支 → 误导性成功提示
- **影响**: 全应用错误展示失效；部分成功/幂等冲突对用户不可见
- **整改**: 拦截器对 `code !== 0/200` 抛错（携带 message），或后端统一 `ResponseEntity.status(code)`

#### F9-02 【P1 · HIGH 置信度】`StorageStatsDTO.totalBytes` 被 `@JsonIgnore`，前端"总大小"恒 0 B
- **位置**: `api-service/.../admin/dto/StorageStatsDTO.java:13-15`；`frontend/src/views/management/storage/StorageSummary.vue:21`
- **证据**: `GET /api/storage/stats` 响应不含 `totalBytes` → 前端显示 '0 B'；`ComicListPage.vue:184-187` 已自行 hq+lq+thumb 绕开
- **整改**: 移除 `@JsonIgnore` 或前端按 hq+lq+thumb 计算

#### 【P1 · HIGH 置信度 · 审核新增 · 真实 QA 抓到】批量 LQ（COMIC 目标）功能级不可用
- **位置**: `api-service/.../management/event/ManagementCommandResultHandler.java:296-335`（`validateLqResult` 将 `ev.targetId()` 当 chapterId）；`BatchOperationService` 批量创建 targetType=COMIC
- **运行证据**（真实 QA）：taskId=6 (LQ_GENERATE, COMIC:5) Worker 侧 success=6/failure=0 全部生成，但 API 校验逐媒体抛"LQ 结果 mediaId=21 不属于目标章节 5" → item FAILED → 批量 LQ 失败；连锁导致"页面 LQ=READY"与"HQ 删除 409"两个 QA 失败
- **影响**: 跨章节批量 LQ（前端批量操作主入口之一）**完全不可用**；单项 LQ（CHAPTER 目标）正常
- **测试盲区**: `LqCommandHandlerTest` 仅测 CHAPTER 目标；`MediaOperationPipelineIT` 未覆盖 COMIC 目标批量
- **整改**: `validateLqResult` 按 item.targetType 区分：COMIC 目标应校验 media 属于该 comic 任一章，或改为逐章校验

### 5.3 P2（21 项）与 P3（57 项）

完整清单见各 task 证据文件：
- **P2 摘要**: F3-02（updateMetadata 无版本回显）/ F3-05（history upsert 无目标校验+并发 500+不过滤 TRASHED）/ F3-06（deleteCategory 留悬空引用）/ F3-07（TRASHED 漫画仍可编辑）/ F3-11（管理功能无前端 UI 但文档宣称）/ F4-01（取消后 FAILED 覆盖）/ F4-02（EHENTAI 下载无取消检查点）/ F4-06（completed 异常卡 PARSING+DLQ+不可重试）/ F4-08（EHENTAI 元数据丢弃）/ F5-01（导出分卷序号 off-by-one）/ F6-01（Go stdout 64KB 截断致大章节 LQ 失败）/ F6-11（转码矩阵漂移）/ F6-12（FfmpegTranscoder 死代码）/ F6-18（刷新新增视频恒 NOT_NEEDED）/ F6-19（METADATA_JSON_REBUILD 手动入口缺失）/ F6-20/21（转码触发 direct publish 吞异常）/ F7-02（恢复冲突死锁）/ F7-03（DELETED tombstone 阻断灾难恢复）/ F7-04（占位漫画无升级路径）/ F9-03（15 组后端 API 零前端调用）/ F9-04（user-guide 3 个不存在路由+EHENTAI UI）/ F9-05（README 声明与前端矛盾）/ F9-12（无真实前端→后端绿测）/ F9-13（部署链 4 处不一致）
- **P3 摘要**: 缓存失效/文档漂移（REGISTER/DIRECTORY、TranscodeStatus 枚举三处不一致、事件计数 31/32/36/37、ComicStatus RESCANNING 残留、schema.md 过期）/ 死代码死事件（media-url.ts、FailedItem 接口合并、StorageBatchBar、RecoveryProgress/Completed 死事件）/ API 固定默认凭据 / SELECT * / 事务内长 IO / @Select 执行 DELETE / GROUP_CONCAT 1024 上限 / symlink 词法校验补充 / 临时文件孤儿 / preview token 内存存储 / Compose 无 Worker 服务等

---

## 6. 架构与阿里规范、目标—现状冲突、假绿审计

### 6.1 架构与阿里规范（Todo 8 详细，15 项全 P3）
- Worker 只读边界: ✅ 实证（HikariCP read-only + comicatlas_ro 账号 + `WorkerDatabasePermissionIT` INSERT/UPDATE 被拒）；⚠️ 权限测试用测试账号而非生产默认（F8-15）
- Flyway: V1+V2 净效果快照 + V10-V18 连续；⚠️ V3-V9 归档旧链不可回退（F8-09）、schema.sql 双份维护源（F8-10）
- MQ: 逐域核对 AGENTS.md 拓扑表全匹配（含 DLX/DLQ/幂等矩阵）；Outbox at-least-once 窗口（F8-04）、Inbox 并发重复语义不一致（F8-05）
- 阿里规范: Checkstyle 0 违规；残余 15 项 P3（默认凭据 F8-01、SELECT * F8-02、事务内长 IO F8-07 等）

### 6.2 测试过时/假绿面（审核重点）
- **显式 IT 9 失败全部为测试过时**（非产品缺陷）: `ComicManagementCrudIT`×8 期望 `$.data.lifecycle`（`f2ed432` 2026-08-06 已改名 `status` 并移除无消费者字段）；`TrashLifecycleIT`×1 期望详情响应 `allowedOperations`（同一提交移除）。前端契约 0 处 `.lifecycle` 佐证实现正确
- `RabbitMqConfigTest` 旧报告（12:34）显示 NoSuchMethodError 引用已移除的旧转码 MQ 方法；该测试随后已被 `58c18e6` 同批删除（当前快照不存在此测试类），不存在残留过时测试，但旧报告痕迹说明测试曾引用已移除方法
- **默认 verify 不运行 `*IT`**（surefire 默认排除）→ `MediaOperationPipelineIT`/`RabbitTopologyIT` 的 12:06/12:33 报告为旧证据；本审核显式重跑全部 17 个 `*IT`
- 根 e2e 套件（`e2e/tests`，baseURL localhost:80）过时（旧路由 /comics、/comics/{id}/read），已有失败 traces；前端 mock UI 套件 28 项真实通过
- final-gate 诚实报告 FAIL（QA fatal 时不伪造 PASS）✅

### 6.3 目标—现状冲突（Todo 2，8 个候选）
| ID | 冲突 | 要点 |
|----|------|------|
| C-01 | REGISTER→DIRECTORY 语义 | 代码已迁移（V17），docs/api.md:24/107 仍写 REGISTER（文档内部自相矛盾 :204） |
| C-02 | TranscodeStatus 枚举 | 代码 `NOT_NEEDED/REQUIRED/QUEUED/TRANSCODING/READY/FAILED`；api.md:259-278 写 PENDING/DONE、:709 漏 REQUIRED |
| C-03 | PURGE tombstone | 目标"删除记录"vs 实现"comic 保留 DELETED tombstone"（ManagementCommandResultHandler:748） |
| C-04 | 分卷导出时态 | 目标 09:162 禁止 user-guide 标记完成，user-guide:225-238 已完整文档化（实现其实已满足） |
| C-05 | 管理前端入口缺失 | user-guide 导航表列出 `/manage/trash`、`/manage/tasks`，router 无此路由；trashApi/managementTaskApi/batchApi 零调用 |
| C-06 | Compose 无 Worker | docker-compose.yml:45-67 worker 注释，README/user-guide 声称 Compose 完整部署 |
| C-07 | ComicStatus 残留 | api.md:22 RESCANNING、schema.md 枚举过期（V10/V17 已归并） |
| C-08 | 事件计数 | AGENTS 31 / api.md 36 / 06-api 37 / 实际 32 |

### 6.4 dirty-worktree 对账
- 源工作树全程 `git status --porcelain=v1` = 0 行（无源码/配置/测试改动）
- 唯一写入: `docs/audits/2026-08-10-project-capability-architecture-audit.md`（本报告）、`docs/README.md`（索引项）、`.omo/evidence/audits/2026-08-10-project-capability-architecture-audit/`
- 未整改、未暂存、未提交；QA 容器/进程/临时文件已清理，生产容器未受影响

---

## 7. 限制与 NOT_VERIFIED

| 限制 | 说明 |
|------|------|
| QA 提前 fatal | 批量 LQ 失败中断 A5 之后场景（A6+ 回收站/恢复/导出真实链、场景 B）未执行 → 对应能力真实链 BLOCKED |
| EHENTAI 下载链路 | 无网络运行证据（依赖 aria2c/torrent/代理），三层（API/Worker/e2e）零覆盖 → NOT_VERIFIED |
| >4GiB 分卷条目 | 单元测试无法构造，ZIP64 大条目跨卷路径依赖库能力未验证（F5-02） |
| symlink/junction 场景 | 环境可构造但审核未实际创建 junction 验证（F6-27 代码级确认、场景级 medium） |
| Gateway/Nginx | 零专项测试（Gateway 无 test 目录；Nginx 仅靠 e2e URL 形态） |
| Testcontainers 跳过 | 本环境 Docker 可用，全部 IT 实际运行（非 skipped） |
| Worker 生产账号 | 权限 IT 用测试账号，未验证生产 comicatlas_ro GRANT（F8-15） |

---

## 8. 整改优先级队列（不实施）

### P0（立即阻断）
1. **F6-09** 修复转码临时文件 probe 扩展名门禁 → 转码功能恢复
2. **批量 LQ COMIC 目标** 修复 `validateLqResult` 按 targetType 区分章节/漫画校验 → 批量 LQ 恢复

### P1（严重，高优先）
3. **F6-26** HQ 删除过滤 VIDEO（数据丢失级）
4. **F3-01/F7-01** 章节回收 manifest 按 chapterId/真实 hqPath 生成
5. **F6-17** 导入最终化后触发 metadata 重建
6. **F6-27** 路径校验加 toRealPath/拒绝 symlink
7. **F6-10** 视频元数据修复接线或下线
8. **F9-01** 前端错误处理校验 Result.code
9. **F9-02** 存储统计 totalBytes 修复

### P2（中优先，21 项）
- 数据一致性: F3-05/F3-06/F3-07、F4-01/F4-06、F7-02/F7-03/F7-04
- 前端产品缺口: F3-11/F9-03/F9-04/F9-05（管理功能 UI 或文档收缩）
- 能力修复: F4-02/F6-01/F6-11/F6-18/F6-19/F6-20/21、F5-01
- 测试补强: F9-12（真实前端→后端绿测）、F4-08

### P3（低优先，57 项）
- 文档再同步（C-01~C-08）、死代码/死事件清理、缓存失效、临时文件清理、默认凭据、部署链（C-06 Compose 加 Worker）等

---

## 9. 证据索引

| 证据 | 路径 |
|------|------|
| 源快照 manifest + 生成脚本 + preflight | `.omo/evidence/audits/2026-08-10-project-capability-architecture-audit/task-1/` |
| 能力追踪矩阵（50 行 + 8 冲突候选） | `task-2/matrix.md` |
| 核心产品审核（12 发现） | `task-3/audit.md` |
| 导入链路审核（10 发现） | `task-4/audit.md` |
| 导出审核（7 发现） | `task-5/audit.md` |
| 媒体处理审核（31 发现 + Go 测试统计） | `task-6/audit.md` |
| 回收站/恢复审核（11 发现） | `task-7/audit.md` |
| 数据/MQ 安全审核（15 发现） | `task-8/audit.md` |
| 前端/文档/部署审核（15 发现） | `task-9/audit.md` |
| 门禁执行记录 + 命令日志 | `task-10/gate-records.md`、`task-10/logs/` |
| 隔离真实 QA 证据 | `task-10/qa-e2e/`（logs/summary.json/progress） |
| 最终 evidence manifest | `task-11/manifest.json`（见下） |

**全部证据绑定 snapshotId=`0f5d4640a69d8c19`；本报告所有 `path:line` 引用均基于该快照磁盘当前内容。**

---

*报告由 ComicAtlas 项目审核计划 `.omo/plans/project-capability-architecture-audit.md` 执行生成；审核不实施整改，整改队列供后续决策。*
