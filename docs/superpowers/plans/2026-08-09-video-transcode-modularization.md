# 视频转码模块化实施计划

**状态**: 待执行

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提取公共 ffmpeg 转码核心 `FfmpegTranscoder`，`VideoTranscodeHandler` 复用，删除死代码 `VideoNormalizer` 及专属 executor。零业务行为变更。

**Architecture:** 2 批（先建核心接入，后删死代码），每批独立提交 + 编译门禁 + 测试。

**Design spec:** `docs/superpowers/specs/2026-08-09-video-transcode-modularization-design.md`

## Global Constraints

- **零业务行为变更**：转码参数（H.264+AAC）、MQ 编排、DB 更新逻辑一律不动；只收敛重复代码位置。
- **跨模块边界**：`ImportEventHandler`（api 模块）的容器判定独立，不依赖 worker 核心（避免 api→worker 耦合）。
- 提交信息中文"动作 + 内容"；每批 `git status` 确认只含本批文件。

---

### Task 1: 新建 FfmpegTranscoder + 接入 VideoTranscodeHandler

**Files (Modify/Add):**
- Add: `worker-service/.../file/transcode/FfmpegTranscoder.java`
- Modify: `worker-service/.../event/VideoTranscodeHandler.java`

- [ ] **Step 1: 新建 FfmpegTranscoder**
- 包：`com.comicatlas.worker.file.transcode`
- `@Component @RequiredArgsConstructor`，依赖 `WorkerConfig` + `ExternalProcessRunner`
- 常量 `FFMPEG_ARGS`：`-c:v libx264 -crf 23 -preset medium -c:a aac -b:a 128k -movflags +faststart -y`（**以 VideoTranscodeHandler 现有为准**）
- `isStandardContainer(String container)`：`"mp4".equals(c) || "m4v".equals(c)`（小写）
- `transcode(Path input, Path output)`：构造命令 + `processRunner.run(processBuilder, 600)` 返回 exitCode
- `buildCommand(...)` 包可见（测试用）
- Javadoc：说明"视频转码纯技术能力，命令/判定收敛单处"
- [ ] **Step 2: VideoTranscodeHandler 复用核心**
- 删除私有 `FFMPEG_ARGS`、`buildFfmpegCommand()`
- 注入 `FfmpegTranscoder`，`transcodeAndPublish` 中改为 `ffmpegTranscoder.transcode(hqFile, tempFile)` + exitCode 校验
- 保留：MQ 消费、临时文件 move 替换 HQ、DB 更新、`publishCompleted/publishFailed`
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl worker-service -am compile -DskipTests
.\mvnw -pl worker-service -am test -Dtest=VideoTranscodeHandlerTest -Dsurefire.failIfNoSpecifiedTests=false
git add worker-service/src/main/java/com/comicatlas/worker/file/transcode/FfmpegTranscoder.java worker-service/src/main/java/com/comicatlas/worker/event/VideoTranscodeHandler.java
git commit -m "模块化视频转码：新建 FfmpegTranscoder 公共核心并接入 VideoTranscodeHandler"
```

---

### Task 2: 删除 VideoNormalizer + videoNormalizeExecutor

**Files (Modify/Delete):**
- Delete: `worker-service/.../file/transcode/VideoNormalizer.java`
- Modify: `worker-service/.../config/WorkerExecutorConfig.java`（删 videoNormalizeExecutor bean）

- [ ] **Step 1: 确认无其他引用**
- grep `VideoNormalizer|videoNormalizeExecutor` 全库，确认仅 WorkerExecutorConfig 引用 executor、无业务调用 VideoNormalizer
- [ ] **Step 2: 删除类与 bean**
- `git rm VideoNormalizer.java`
- WorkerExecutorConfig 删除 `videoNormalizeExecutor` bean（保留 processIoExecutor）
- [ ] **Step 3: 验证 + 提交**
```bash
.\mvnw -q -pl worker-service -am compile -DskipTests
# 残留检查
Select-String worker-service/src -Pattern "VideoNormalizer|videoNormalizeExecutor"
git add -A
git commit -m "清理死代码：删除 VideoNormalizer 及其 videoNormalizeExecutor bean"
```

---

### Task 3: 收尾验证

- [ ] **Step 1: 全量测试**
```bash
.\mvnw -pl worker-service -am test 2>&1 | Select-String "Tests run:|BUILD"
```
- [ ] **Step 2: 残留确认**
- grep `VideoNormalizer|videoNormalizeExecutor` 全库零残留（含测试）
- [ ] **Step 3: 汇总**
- 输出 2 批提交清单 + 编译/测试结果到最终报告。
