# comic-common 按阿里开发规范整理实施计划

**状态**: 待执行

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按阿里规范整理 comic-common 包结构——event 数据载体下沉 payload 子包、enums 下沉 api、dto 统一命名。纯结构变更，零业务逻辑/零 MQ 契约变更。

**Architecture:** 4 个批次（payload 子包 / enums 下沉 / dto 命名 / 文档同步），每批独立提交 + grep 校验旧引用清零 + 编译测试门禁。

**Design spec:** `docs/superpowers/specs/2026-08-08-comic-common-restructure-design.md`

## Global Constraints

- **不改任何业务逻辑**：枚举值、事件字段、record 组件、方法签名一律不动；只移动/重命名类与更新 import。
- **MQ 契约零变更**：`ComicEvent` 的 `@JsonSubTypes` 注册不动；payload 子包移动不影响序列化（两载体未被注册，已核实）。
- 移动/重命名必须**逐处更新所有引用方**（含测试），`mvnw compile` 零错误为硬门禁。
- 提交信息中文"动作 + 内容"；每批 `git status` 确认只含本批文件。
- 命名映射（唯一权威来源）：
  - `TranscodeMediaInfo` → `com.comicatlas.common.event.payload.TranscodeMediaInfo`
  - `VideoMetadataFixResult` → `com.comicatlas.common.event.payload.VideoMetadataFixResult`
  - `OutboxStats` → `OutboxStatsDTO`、`TrashManifest` → `TrashManifestDTO`、`TrashManifestActual` → `TrashManifestItemDTO`、`ScanItemVO` → `ScanItemDTO`、`ScanResultVO` → `ScanResultDTO`（均在 `com.comicatlas.common.dto`）
  - 6 枚举 → `com.comicatlas.api.common.enums`：`ChapterLifecycleStatus`/`ManagementTaskStatus`/`MediaLifecycleStatus`/`TaskStage`/`TaskType`/`TranscodeStatus`

---

### Task 1: event 数据载体下沉 payload 子包

**Files:**
- Move: `comic-common/.../event/TranscodeMediaInfo.java` → `comic-common/.../event/payload/TranscodeMediaInfo.java`（改 package 声明）
- Move: `comic-common/.../event/VideoMetadataFixResult.java` → `comic-common/.../event/payload/VideoMetadataFixResult.java`
- Modify: `comic-common/.../event/ManagementCommandCompletedEvent.java`、`VideoMetadataFixCompletedEvent.java`（import + 字段类型）
- Modify（消费方 import）: `worker TranscodeCommandHandler`、`VideoMetadataFixHandler`、`ManagementCommandPublisher`；`api ManagementCommandResultHandler`、`VideoMetadataFixCompletedHandler`
- Modify（测试）: `worker TranscodeCommandHandlerTest`、`comic-common ManagementEventContractTest`（如引用）

- [ ] **Step 1: 移动两个 record 到 payload 子包**
- 在 `comic-common/.../event/` 下新建 `payload/` 目录；移动文件，`package` 声明改为 `com.comicatlas.common.event.payload`；Javadoc 保持（补充"事件数据载荷"定位说明）。
- [ ] **Step 2: 更新事件定义与全部消费方 import**
- 全量 grep `TranscodeMediaInfo|VideoMetadataFixResult`，逐文件更新 import（含测试）。字段类型声明同步改为新包名。
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl comic-common -am compile -DskipTests   # 需零错误
# 残留检查: comic-common 中非 payload 子包不应再有这两个类名
git add comic-common worker-service api-service
git commit -m "整理 comic-common：事件数据载体下沉 payload 子包，event 包回归纯净"
```

---

### Task 2: 枚举下沉 api-service

**Files:**
- Move 6 枚举 → `api-service/.../com/comicatlas/api/common/enums/`（改 package 声明为 `com.comicatlas.api.common.enums`）
- Modify: api-service 全部 63 处 import（含测试）
- Modify: `AGENTS.md`、`docs/architecture/*` 中"枚举位于 comic-common"的描述（本批只改枚举相关行，其余文档在 Task 4）

- [ ] **Step 1: 移动 6 个枚举**
- 删除 `comic-common/.../enums/` 下 6 个文件；在 api `common/enums` 包重建（内容逐字复制，仅 package 行变化）。Javadoc 保留。
- [ ] **Step 2: 更新 api 全部 import**
- `Get-ChildItem api-service/src -Recurse -Filter *.java | Select-String -Pattern "com\.comicatlas\.common\.enums"` 全量替换为 `com.comicatlas.api.common.enums`；保留 `import static` 场景检查。
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service -am compile -DskipTests   # 需零错误
# 残留检查: comic-common 与 worker 不应再有 common.enums
Select-String comic-common/src,worker-service/src -Pattern "common\.enums" -Recurse
git add comic-common api-service
git commit -m "整理 comic-common：枚举下沉 api-service（高内聚低耦合）"
```

---

### Task 3: dto 统一 *DTO 命名

**Files:**
- Rename in `comic-common/.../dto/`: `OutboxStats`→`OutboxStatsDTO`、`TrashManifest`→`TrashManifestDTO`、`TrashManifestActual`→`TrashManifestItemDTO`、`ScanItemVO`→`ScanItemDTO`、`ScanResultVO`→`ScanResultDTO`
- Modify: 全部引用方（api 9 处 + worker 8 处 + common 内部 `DirectoryScanCompletedEvent` 等 + 测试 `OutboxInboxRelayIT`/`TrashLifecycleIT`/`MediaUploadManagementIT`）

- [ ] **Step 1: 重命名 5 个 DTO 文件与类名**
- 文件重命名 + `public record` 声明改名 + 类内自引用更新（如 `ScanResultVO` 内部引用 `ScanItemVO` 的字段类型）。
- [ ] **Step 2: 更新全部引用方**
- 全量 grep `OutboxStats|TrashManifest|TrashManifestActual|ScanItemVO|ScanResultVO`，逐文件更新 import + 类型引用（含测试）。注意 `TrashManifest` 前缀匹配会命中 `TrashManifestService`/`TrashManifestStore`——**这些 Service/Store 类名不改**，只改 DTO 引用。
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl api-service,worker-service -am compile -DskipTests   # 需零错误
# 残留检查: 旧名不再作为类型出现（允许注释/字符串中的历史说明）
Select-String api-service/src,worker-service/src,comic-common/src -Pattern "\bScanItemVO\b|\bScanResultVO\b|\bOutboxStats\b|\bTrashManifestActual\b|\bTrashManifest\b"
git add comic-common api-service worker-service
git commit -m "整理 comic-common：dto 统一 *DTO 命名，明确传输对象语义"
```

---

### Task 4: 文档同步 + 全量门禁

**Files (Modify):**
- `AGENTS.md`（WHERE TO LOOK：枚举行→api 包；dto 行→*DTO 名；event 行→payload 子包说明）
- `docs/architecture/` 相关引用（01-system-overview/05-domain 如提及枚举位置）
- `docs/superpowers/specs/2026-08-08-comic-common-restructure-design.md` 状态 → 已完成

- [ ] **Step 1: 同步 AGENTS.md 与架构文档**
- "枚举" 条目：位置从 `comic-common/.../enums/` 改为 `api-service/.../common/enums/`；dto 条目更新 *DTO 名；event 条目注明 `payload/` 子包承载数据载体。
- [ ] **Step 2: 全量验证**
```bash
# 全量编译（三个模块）
.\mvnw -q -pl api-service,worker-service -am compile -DskipTests
# 测试门禁（重点契约测试）
.\mvnw -pl comic-common -am test -Dtest=ManagementEventContractTest -DfailIfNoTests=false
.\mvnw -pl api-service -am test 2>&1 | Select-String "Tests run:|BUILD"
.\mvnw -pl worker-service -am test 2>&1 | Select-String "Tests run:|BUILD"
# 残留检查（应仅剩历史注释）
Select-String AGENTS.md,docs -Pattern "comic-common.*enums|common\.enums"
git add AGENTS.md docs
git commit -m "同步文档：comic-common 包结构描述更新（payload/enums 下沉/dto 命名）"
```

- [ ] **Step 3: 收尾验证**
- `git log --oneline` 确认 4 个提交；`git status --short` 干净；汇总编译/测试结果到最终报告。
