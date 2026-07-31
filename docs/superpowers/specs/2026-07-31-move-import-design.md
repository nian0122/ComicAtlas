# 导入改用 Move（含中断恢复）设计

**日期**: 2026-07-31
**状态**: 已审阅
**范围**: Worker（导入核心链路）+ 取消机制修复

---

## 1. 背景与目标

当前所有导入来源（REGISTER/DIRECTORY/ZIP/EHENTAI）在搬入 HQ 时使用 `Files.copy` 逐文件复制，大漫画导入慢（读+写双重 I/O）。目标：改为 `Files.move`，同卷时为瞬时 rename。

**核心目标**：
- REGISTER/DIRECTORY/ZIP 统一改用 move 搬入 HQ
- **中断恢复机制**：任务失败/取消后重试，能跳过已搬文件续搬，不产出缺页漫画
- **文件不损坏**：目标路径要么不存在、要么完整，杜绝半截文件

**非目标**：
- EHENTAI 导入（`FileService.processImport`）已用 `Files.move` 且源可再生（重新下载），不纳入本次改动
- 不改 DB schema、不改 API、不改前端
- 不做并行搬运（带宽/磁盘瓶颈下无收益，反而复杂）

---

## 2. 关键事实与约束

| 来源 | 当前文件处理 | 源的可再生性 |
|------|-------------|-------------|
| REGISTER/DIRECTORY | `LocalStorageService.store()` → `Files.copy` | ❌ 用户真实目录，被 move 消费后不可再生 |
| ZIP | 解压到 `mangaRoot/temp/{taskId}/extracted` → 委托 `DirectoryImportHandler` 复制 | ✅ 失败时 temp 被 `finally` 清理，重试重新解压 |
| EHENTAI | `FileService.processImport` 已用 `Files.move` | ✅ 重新下载 |

**速度前提**：move 仅在**同卷**（源与 `D:/manga` 同一文件系统）时是瞬时 rename。跨卷 move 物理上仍是复制。ZIP/EHENTAI 的源都在 `mangaRoot/temp` 下 → 天然同卷；REGISTER 取决于用户源目录位置。

**Move 的固有风险**（当前 copy 语义天然免疫，必须解决）：
1. **中断后源目录被部分消费**——重试若重新解析源目录，已搬走的文件缺失 → metadata 缺页 → 漫画损坏
2. **跨卷 `Files.move` 内部是 copy+delete，非原子**——中断可能留下半截目标 + 源已删 = 数据丢失
3. **取消标记残留**——`CancelHandler.isCancelled` 有 30 分钟 TTL，`retryTask` 不清除标记，取消后立刻重试会被 worker 跳过（现有 bug，move 后影响更大）

---

## 3. 设计：清单（Manifest）驱动的安全 Move

### 3.1 三个不变式

1. **先写清单，再动第一个文件**——清单原子写入（临时名 + rename）。"清单存在" = 已开始搬运；"清单不存在" = 什么都没动，可安全全新导入。
2. **目标文件要么不存在，要么完整**——跨卷 copy 到 `.tmp` 校验大小后原子改名，绝不直接写目标名；同卷 rename 本身原子。
3. **metadata 永远来自首次完整解析**——存在清单就跳过解析，从清单恢复。绝不再解析已被搬空的源目录（否则产出缺页漫画）。清单内嵌完整 v3 metadata（含 MediaAnalyzer 提取的 width/height/fileSize/duration/container/videoCodec/audioCodec 等文件元信息），恢复时零依赖源文件。

### 3.2 搬运策略（`LocalStorageService` 新增 move 语义）

```
store(source, rootKey, relativePath, move=true):
  同卷 (Files.getFileStore 相等):
    Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)   # 瞬时，原子
  跨卷:
    Files.copy(source, target.tmp, REPLACE_EXISTING)            # 写临时名
    校验 Files.size(target.tmp) == Files.size(source)           # 不等则抛错
    Files.move(target.tmp, target, ATOMIC_MOVE)                 # 同目录改名 = 原子
    Files.deleteIfExists(source)                                # 目标完整后才删源
```

- `ATOMIC_MOVE` 失败（如文件系统不支持）时回退到普通 `Files.move`（仍先写 `.tmp`，目标名不暴露半截）。
- 失败清理：异常路径删除残留 `.tmp`，不删目标。
- 现有 copy 语义保留：`store(source, rootKey, relativePath, move=false)` 供封面/其它复用（ImageOptimizer 等不受影响）。

### 3.3 清单文件（新建 `ImportManifest`）

位置：`mangaRoot/temp/{taskId}/manifest.json`（ZIP 的 temp 清理会连带删除，天然符合"ZIP 重试重新解压"语义；REGISTER 无 temp 清理方，成功/失败都由导入链路管理）。

```json
{
  "version": 1,
  "metadata": {
    "version": 3,
    "comic": { "title": "...", "author": "...", "tags": [...] },
    "catalogs": [ { "title": "...", "sortOrder": 1, "parentIndex": -1 } ],
    "chapters": [
      {
        "title": "...", "chapterNo": "1", "sortOrder": 1, "globalOrder": 1,
        "catalogIndex": -1, "sourceDir": "...",
        "mediaItems": [
          { "fileName": "001.jpg", "pageNumber": 1, "hqStatus": "PENDING",
            "lqStatus": "NOT_GENERATED", "fileSize": 12345,
            "width": 800, "height": 1200, "mediaType": "IMAGE",
            "duration": null, "container": null, "videoCodec": null, "audioCodec": null }
        ]
      }
    ]
  },
  "files": [
    { "src": "/abs/path/ch1/001.jpg", "dst": "5/1/001.jpg", "size": 12345 }
  ]
}
```

**关键性质**：
- **写入一次，从不更新**——恢复靠"目标已存在且大小匹配则跳过"判断，无需写日志/标记，天然防清单自身损坏。
- **原子写入**：写 `manifest.json.tmp` → `Files.move(tmp, manifest)`，杜绝半截清单。
- **`metadata` 字段 = 完整 v3 metadata**（`writeMetadata` 序列化的那份），恢复时直接序列化写 `metadata.json`，零依赖源文件。

### 3.4 恢复判定

对清单中每个文件，恢复/重试时：

| 目标状态 | 源状态 | 动作 |
|---------|--------|------|
| 目标存在 且 大小匹配 | 任意 | **跳过**（已搬完，同卷原子 / 跨卷已校验） |
| 目标存在 且 大小不匹配 | 任意 | **报错**（目标被污染，人工介入，不覆盖） |
| 目标不存在 | 源存在 | 重新安全 move |
| 目标不存在 | 源不存在 | **报错**（源被外部删除，明确失败，不静默丢页） |

> 注：跨卷中断残留的 `.tmp` 会被下一次 `Files.copy(..., REPLACE_EXISTING)` 覆盖，不影响判定。

---

## 4. 各来源行为

| 来源 | 清单 | 中断恢复方式 |
|------|------|-------------|
| **REGISTER/DIRECTORY** | ✅ 写入 | 重试读清单续搬，跳过已搬文件，metadata 从清单出（完整） |
| **ZIP** | ✅ 写入（随 temp 清理） | 失败时 temp 被清理、清单消失 → 重试重新解压 → 全新解析，天然幂等（REPLACE_EXISTING 覆盖已搬文件，内容相同） |

---

## 5. 变更文件清单

| 文件 | 变更 |
|------|------|
| `worker-service/.../file/storage/LocalStorageService.java` | `store` 增加 `move` 参数，实现同卷/跨卷安全 move 分支 |
| `worker-service/.../file/ImportManifest.java`（新） | 清单读写（原子写）、恢复判定逻辑 |
| `worker-service/.../file/handler/DirectoryImportHandler.java` | 解析后写清单；有清单则跳过解析、按清单恢复；metadata 从清单出；成功删清单 |
| `worker-service/.../event/CancelHandler.java` | 增加 `clear(taskId)` 方法 |
| `worker-service/.../event/ImportTaskHandler.java` | 任务开始执行时清除过期取消标记 |

### 5.1 DirectoryImportHandler 流程变化

```
handle(ctx, taskId, comicId, mangaRoot):
  manifest = ImportManifest.load(tempDir)        # temp/{taskId}/manifest.json
  if manifest == null:                            # 全新导入
    视频标准化 → parse → assemble（含 MediaAnalyzer）
    metadata = 解析结果
    files = 构建 {src, dst, size} 列表
    ImportManifest.write(tempDir, metadata, files)  # 原子写入，之后才动文件
  else:                                           # 中断恢复
    metadata = manifest.metadata                   # 绝不重新解析源目录
    files = manifest.files
  for file in files:
    检查取消 → throw（保持现有 isCancelled 检查）
    恢复判定（3.4）→ skip / move / 报错
    storageService.store(src, "HQ", dst, move=true)
  writeMetadata(metadata, taskId, mangaRoot)      # 从清单 metadata 序列化
  ImportManifest.delete(tempDir)                  # 成功后清理
  return metaPath
```

### 5.2 封面生成位置不变

封面在搬文件完成后从 HQ 读取生成（现有逻辑），不依赖源目录，无需改动。

### 5.3 取消标记修复

- `CancelHandler` 增加 `clear(Long taskId)`：移除 `cancelled` 中的条目。
- `ImportTaskHandler.handle()` 在**处理开始后**（`publisher.publishStatus(taskId, "PARSING", ...)` 之前）调用 `cancelHandler.clear(taskId)`。
  - 语义：任务确实开始执行时，清除可能残留的旧取消标记，允许续搬。
  - 不吞并发取消：执行期间的取消由逐文件 `isCancelled` 检查捕获；执行前已取消的任务仍被 `handle()` 开头的 `isCancelled` 检查拦截。
- 覆盖场景：取消 → `retryTask` → 30 分钟内重试不再被跳过。

---

## 6. 错误处理与边界

| 场景 | 行为 |
|------|------|
| 清单 JSON 损坏 | 报错并提示人工处理，**绝不**回退到"重新解析源目录" |
| 跨卷 copy 中断 | 残留 `.tmp`，下次 REPLACE_EXISTING 覆盖；目标名永不见半截文件 |
| 同卷 move 后崩溃 | 目标完整存在，恢复跳过，metadata 照写 |
| 取消后 30 分钟内重试 | `clear()` 修复，正常续搬 |
| 源文件被外部删除 | 恢复时 dst 缺失且 src 不在 → 明确报错，不静默丢页 |
| 目标存在但大小不匹配 | 报错，不覆盖（保护既有数据） |

---

## 7. 测试

- 单元：`ImportManifest` 读写/原子写/损坏清单解析失败
- 单元：`LocalStorageService` move 同卷（rename）/跨卷（copy+tmp+rename+删源）分支、`.tmp` 残留清理
- 集成：`DirectoryImportHandlerSmokeTest` 扩展——
  1. 正常导入：源目录被搬空，metadata 完整（与 copy 时代对比缺一不可）
  2. 模拟中断（搬 N 个文件后抛异常）→ 重试 → 续搬剩余，metadata 含全部页
  3. 模拟取消 → 重试 → 正常完成
  4. 目标已存在且大小匹配 → 跳过不重复搬

---

## 8. 发布说明

- 无需 DB 迁移
- 无需 API/前端改动
- 唯一行为变化：REGISTER 导入后**源目录被清空**（move 语义），用户需知晓
