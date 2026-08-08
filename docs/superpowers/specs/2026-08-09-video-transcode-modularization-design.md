# 视频转码模块化设计

**日期**: 2026-08-09
**状态**: 设计待审阅
**范围**: worker-service 视频转码领域模块化——提取公共 ffmpeg 转码核心，删除死代码 VideoNormalizer

## 背景与目标

worker-service 存在两个视频转码类，功能重叠但职责分散：

| | `VideoNormalizer` | `VideoTranscodeHandler` |
|---|---|---|
| 触发 | 导入时自动（已移除调用） | MQ 事件驱动（管理面板手动转码） |
| 作用 | 源目录批量预处理 | 单个 HQ 视频页 |
| 状态 | **死代码**（无调用方） | **在用** |

两者在 **ffmpeg 命令构造、非标准格式判定、临时文件处理** 上高度重复，违反阿里规范"高内聚、复用、单一职责"。

**目标**：
1. 提取公共 ffmpeg 转码核心（命令构造 + 非标准判定 + 执行）到独立服务类
2. `VideoTranscodeHandler` 改为复用核心
3. **删除** `VideoNormalizer` 死代码及其专属 `videoNormalizeExecutor` bean
4. 零业务行为变更

## 现状分析

### VideoNormalizer（将删除）
- `NON_STANDARD_EXTENSIONS`（wmv/flv/ts/avi/mov/mkv/mts/m2ts/vob/3gp/m4v）
- `transcode()`：ffmpeg `-c:v libx264 -threads 2 -c:a aac...` → 临时目录 → 全成功才替换源目录
- 专属 `videoNormalizeExecutor`（WorkerExecutorConfig，仅它使用）

### VideoTranscodeHandler（在用，将复用核心）
- `FFMPEG_ARGS`：`-c:v libx264 -crf 23 -preset medium -c:a aac -b:a 128k -movflags +faststart -y`
- `buildFfmpegCommand()`：构造命令
- 转临时文件 → 校验 → move 替换 HQ → 更新 DB

### 重复点
1. **ffmpeg 命令构造**（libx264 + aac 参数，两处重复实现）
2. **非标准格式判定**（Normalizer 的扩展名集合 vs TranscodeHandler 的 container 判定）
3. **临时文件处理**（转临时 + 校验非空 + 原子替换）

## 变更设计

### 变更 1：新建 `FfmpegTranscoder`（公共转码核心）

位置：`worker-service/.../file/transcode/FfmpegTranscoder.java`

职责（单一）：ffmpeg 视频转码的**纯技术能力**——
```java
@Component
@RequiredArgsConstructor
public class FfmpegTranscoder {
    // ffmpeg 参数：H.264 + AAC（以 VideoTranscodeHandler.FFMPEG_ARGS 为基准，收敛单处）
    private static final List<String> FFMPEG_ARGS = List.of(...);

    /** 判定容器是否标准（mp4/m4v 无需转码）——收敛非标准判定单处 */
    public boolean isStandardContainer(String container);

    /** 执行转码：input → output，返回 exitCode（超时/中断由 Runner 语义保证） */
    public int transcode(Path input, Path output);

    /** 构造 ffmpeg 命令（包可见，供测试） */
    List<String> buildCommand(String ffmpegPath, String input, String output);
}
```

依赖：`WorkerConfig`（ffmpeg 路径）、`ExternalProcessRunner`（统一执行）。

### 变更 2：`VideoTranscodeHandler` 复用核心

- 删除私有 `FFMPEG_ARGS`、`buildFfmpegCommand()`
- 注入 `FfmpegTranscoder`，转码改为 `ffmpegTranscoder.transcode(hqFile, tempFile)`
- 保留 MQ 消费、临时文件 move 替换 HQ、DB 更新逻辑（业务编排不动）
- `isStandardVideoContainer` 语义由核心 `isStandardContainer` 提供（但 API 侧 `ImportEventHandler` 的判定是**API 模块**，不依赖 worker 核心——保持独立，避免跨模块耦合；仅 worker 内部复用）

### 变更 3：删除 `VideoNormalizer` + `videoNormalizeExecutor`

- 删除 `VideoNormalizer.java`（死代码）
- 删除 `WorkerExecutorConfig` 的 `videoNormalizeExecutor` bean（仅 VideoNormalizer 使用）
- 删除相关测试引用（若有）

## 不做的事（YAGNI）

- 不改 `ImportEventHandler` 的容器判定（API 模块独立，worker 核心不跨模块依赖）
- 不新增转码队列/批量能力（管理面板手动单文件转码现状不变）
- 不重构 `VideoTranscodeHandler` 的 MQ 编排与 DB 更新逻辑（仅替换转码调用）
- 不引入新依赖

## 验证策略

1. **编译门禁**：`.\mvnw -q -pl worker-service -am compile` exit 0
2. **测试**：`VideoTranscodeHandlerTest`（验证复用核心后行为不变）、`ExternalProcessRunnerTest` 全过
3. **残留检查**：grep `VideoNormalizer|videoNormalizeExecutor` 零残留（除删除记录）
4. **行为不变**：`VideoTranscodeHandlerTest` 的转码参数断言与核心 `FFMPEG_ARGS` 一致

## 提交规划

按依赖拆 2 批：
1. `模块化视频转码：新建 FfmpegTranscoder 公共核心并接入 VideoTranscodeHandler`
2. `清理死代码：删除 VideoNormalizer 及其 videoNormalizeExecutor bean`

每批独立提交 + 编译门禁。
