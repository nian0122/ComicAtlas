# P0/P1 审计整改验证报告（2026-08-10）

- **审计报告**: `docs/audits/2026-08-10-project-capability-architecture-audit.md`（原文未改动）
- **整改 HEAD（最后整改提交）**: `de37f7e`；**当前 HEAD（含本验证报告）**: `42d2a42`（`feature/lq-transcode-import-video-status`）
- **snapshotId**: `remediation-20260810-1`
- **证据目录**: `.omo/evidence/audit-p0-p1-remediation/20260810-192036/`

## 整改与证据对照

| finding ID | 缺陷 | 修复提交 | 证据日志 | 验证方式 | 状态 |
|---|---|---|---|---|---|
| F6-09 (P0) | 视频转码每次全新执行必然失败（`.mp4.tmp` 被扩展名门禁拒绝） | `96b6f4d` | `task-4-transcode.log` | RED（ffmpeg exit -22）+ GREEN 16 测试 + 真实 ffmpeg/ffprobe QA（.probe.mp4 可识别、临时目录 0 孤儿） | 已修复 |
| 审核新增 (P1) | 批量 LQ（COMIC 目标）跨章节媒体结果全部被拒 | `6e435f0` | `task-5-lq-hq.log` | RED 3 fail+1 error → GREEN 61 测试（含 Testcontainers COMIC 跨两章 IT） | 已修复 |
| F6-26 (P1) | HQ 删除误删视频文件（Worker 删全部、API 只更新 IMAGE） | `6e435f0` | `task-5-lq-hq.log` | RED 5 fail（VIDEO 被删）→ GREEN（IMAGE-only，VIDEO 保留） | 已修复 |
| F3-01/F7-01 (P1) | 章节回收 manifest 用 globalOrder 猜路径 | `de37f7e` | `task-3-trash-manifest.log` | RED（manifest 为 globalOrder 路径）→ GREEN 20 测试（Testcontainers 三态一致） | 已修复 |
| F6-17 (P1) | 导入后 metadata.json 与 DB chapterId 布局不一致 | `ab94089` | `task-2-canonical-metadata.log` | RED（直接 SUCCESS）→ GREEN 49 测试；FINALIZING 中间态 + 结果事件闭环 | 已修复 |
| F6-27 (P1) | StorageRoot/ApiStorageRoot 纯词法校验，可经 symlink/junction 逃逸 | `b1234fe` | `task-1-storage-containment.log` | RED（根外 junction 放行）→ GREEN 12 测试；junction 实测（Skipped: 0） | 已修复 |
| F6-10 (P1) | 视频元数据修复管线为孤儿（无生产方） | `ad9e706` | `task-6-metadata-topology.log` | RED（旧契约仍存在）→ GREEN 52 测试；生产源码/活跃文档零命中 | 已修复 |
| F9-01 (P1) | 前端 axios 拦截器不校验 Result.code，业务错误被吞 | `901ceaf` | `task-7-result-code.log` | 拦截器契约核实（01a0b65 已实现）+ Playwright 4 用例 + 前端 13 e2e | 已修复 |
| F9-02 (P1) | StorageStatsDTO.totalBytes 被 @JsonIgnore，总大小恒 0 B | `60b93ec` | `task-8-storage-stats.log` | MockMvc 断言 totalBytes=sum + formatSize 单测 + 前端构建 | 已修复 |

## 门禁验证摘要

- 九项整改相关显式 IT（5 组，共 195 用例）全部通过（0 失败、0 skipped），见 `task-9-final-verification/gates.log`：
  - StorageRoot/ApiStorageRoot 12 用例
  - 存储管理 API 27 用例
  - 转码/媒体分析 24 用例
  - 管理命令管线（LQ/HQ）61 用例
  - MQ 拓扑/契约/元数据刷新 71 用例（Testcontainers 真实执行）
- `git diff --check` 通过；前端 `pnpm build` 通过。
- **最终复核（干净 worktree）**: 上述 4 个 api 侧 IT（`ImportPersistenceServiceTest` 22 + `ImportMetadataRefreshResultHandlerTest` 5 + `ReadingLifecycleCompatibilityIT` 18 + `TrashLifecycleIT` 14 = 59 用例）在干净 HEAD `42d2a42` 上全部通过（0 失败、0 skipped，含 2 个 Testcontainers IT 真实执行），见 `task-9-final-verification/f1-clean-worktree-it.log`。主工作区未提交的用户改动 `HistoryServiceTest.java`（MyBatisPlus 双重载歧义编译错误）仅影响主工作区 testCompile，不影响本计划代码。
- 全量 `mvnw clean verify` 与真实 QA runner/final-gate 未执行（用户确认精简门禁，环境/时间受限）。

## 边界说明

- 未改动数据库 schema（无 Flyway 新增）；未引入新表。
- 原审计报告与 `docs/superpowers/**` 历史归档未改动。
- 用户既有改动（`docs/README.md`、`docs/audits/`、frontend 未跟踪文件、HistoryServiceImpl/Flyway V19 等）保持原样未暂存。
- 未 push/merge/tag。
