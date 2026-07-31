# 导入改用 Move（含中断恢复）设计

**日期**: 2026-07-31
**状态**: 已审阅（含用户修订：manifest 位置、相对路径、Redis 取消机制）
**范围**: Worker（导入核心链路）+ 取消机制

---

## 1. 背景与目标

当前所有导入来源（REGISTER/DIRECTORY/ZIP/EHENTAI）在搬入 HQ 时使用 `Files.copy` 逐文件复制，大漫画导入慢（读+写双重 I/O）。目标：改为 `Files.move`，同卷时为瞬时 rename。

**核心目标**：
- REGISTER/DIRECTORY/ZIP 统一改用 move 搬入 HQ
- **中断恢复机制**：任务失败/取消后重试，能跳过已搬文件续搬，不产出缺页漫画
- **文件不损坏**：目标路径要么不存在、要么完整，杜绝半截文件

**非目标**（个人本地漫画库，不做 NAS/云盘级能力）：
- ❌ 不建 `import_file_transfer` 表、不做文件级 DB 记录
- ❌ 不做秒级进度统计、多线程搬运、SHA256 全量校验、分布式锁
- ❌ EHENTAI 导入（`FileService.processImport`）已用 `Files.move` 且源可再生（重新下载），不纳入本次改动
- ❌ 不改 DB schema、不改 API 对外接口、不改 MQ 事件结构

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
3. **取消意图管理**——取消是用户操作，Worker 不应擅自覆盖；取消状态需 API 可控

---

## 3. 架构

```
DirectoryImportHandler
        │
        ▼
ImportManifestManager          # 清单读写（导入恢复点）
        │
        ▼
StorageService.transfer(source, target, TransferMode)
        │
        ├── TransferMode.COPY → Files.copy
        └── TransferMode.MOVE → SafeMoveStrategy
                                  ├── 同卷: atomic rename
                                  └── 跨卷: copy→.tmp → size 校验 → rename → delete source
```

新增组件：
- `worker/file/import/ImportManifest.java`（新）— 清单 POJO
- `worker/file/import/ImportManifestManager.java`（新）— 清单读写/恢复判定
- `worker/file/storage/TransferMode.java`（新）— 枚举 COPY/MOVE
- `worker/file/storage/SafeMoveStrategy.java`（新）— 同卷/跨卷安全 move
- `worker/file/storage/TransferService.java`（新）— 搬运门面（createDirectories + 分派）
- `StorageService` 接口 — `store(source, rootKey, relativePath)` 改为 `transfer(Path source, StorageRef target, TransferMode mode)`

---

## 4. 清单（Manifest）设计

### 4.1 位置

```
D:/manga/imports/{taskId}/manifest.json
```

**不放 temp**。原因：它不是临时文件，是**导入恢复点**。temp 目录由 ZIP 的 `finally` 清理，而 manifest 必须保留到导入成功；失败/取消时保留供重试续搬。

### 4.2 结构

```json
{
  "version": 1,
  "taskId": 123456,
  "source": {
    "type": "DIRECTORY",
    "root": "D:/download/naruto"
  },
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
    { "source": "vol1/ch1/001.jpg", "target": "10/20/001.jpg", "size": 123456 }
  ]
}
```

### 4.3 关键性质

- **`files[].source` 存相对路径**（相对 `source.root`），不存绝对路径——源目录移动后清单仍可定位。
- **`files[].target` 为 HQ 相对路径**（`comicId/chapterGlobalOrder/fileName`），与 DB `page.hq_path` 一致。
- **写入一次，从不更新**——恢复靠"目标已存在且大小匹配则跳过"判断，无需写日志/标记，天然防清单自身损坏。
- **原子写入**：写 `manifest.json.tmp` → `Files.move(tmp, manifest)`，杜绝半截清单。
- **`metadata` 字段 = 完整 v3 metadata**（含 MediaAnalyzer 提取的 width/height/fileSize/duration/container/videoCodec/audioCodec 等文件元信息），恢复时直接序列化写 `metadata.json`，零依赖源文件。

---

## 5. 文件搬运策略（SafeMoveStrategy）

```
move(source, target):
  同卷 (Files.getFileStore 相等):
    Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)   # 瞬时，原子
    # AtomicMoveNotSupportedException → 回退普通 Files.move(REPLACE_EXISTING)
  跨卷:
    Files.copy(source, target.tmp, REPLACE_EXISTING)            # 写临时名
    校验 Files.size(target.tmp) == Files.size(source)           # 不等则抛错
    Files.move(target.tmp, target, ATOMIC_MOVE)                 # 同目录改名 = 原子
    # AtomicMoveNotSupportedException → 回退普通 Files.move
    Files.deleteIfExists(source)                                # 目标完整后才删源
  finally:
    Files.deleteIfExists(target.tmp)                            # 清理残留 .tmp
```

> 跨卷中断残留的 `.tmp` 被下一次 `Files.copy(..., REPLACE_EXISTING)` 覆盖，不影响判定。

---

## 6. 导入流程（DirectoryImportHandler 重构）

```
handle(ctx, taskId, comicId, mangaRoot):
  if ImportManifestManager.exists(mangaRoot, taskId):            # 中断恢复
    manifest = ImportManifestManager.read(mangaRoot, taskId)
    # 绝不重新解析源目录 —— 源已被部分消费，重解析会产出缺页 metadata
  else:                                                          # 全新导入
    视频标准化 → parse → assemble（含 MediaAnalyzer）
    files = 构建 {source(相对路径), target, size} 列表
    ImportManifestManager.write(mangaRoot, taskId, metadata, files)   # 原子写，之后才动文件

  for file in manifest.files:
    检查取消 → throw（保持现有 isCancelled 逐文件检查）
    恢复判定（下表）→ skip / transfer(MOVE) / 报错

  封面生成（从 HQ 读取，不依赖源目录，现有逻辑不变）
  writeMetadata(manifest.metadata, taskId, mangaRoot)            # 从清单 metadata 序列化
  ImportManifestManager.delete(mangaRoot, taskId)                # 成功后清理恢复点
  return metaPath
```

### 恢复判定

| 目标状态 | 源状态 | 动作 |
|---------|--------|------|
| 目标存在 且 大小匹配 | 任意 | **跳过**（已搬完，同卷原子 / 跨卷已校验） |
| 目标存在 且 大小不匹配 | 任意 | **报错**（目标被污染，人工介入，不覆盖） |
| 目标不存在 | 源存在 | 重新安全 move |
| 目标不存在 | 源不存在 | **报错**（源被外部删除，明确失败，不静默丢页） |

### 各来源行为

| 来源 | 清单 | 中断恢复方式 |
|------|------|-------------|
| **REGISTER/DIRECTORY** | ✅ 写入 | 重试读清单续搬，跳过已搬文件，metadata 从清单出（完整） |
| **ZIP** | ✅ 写入（imports 下，不受 temp 清理影响） | 失败时 temp 被清理、清单保留 → 重试重新解压 → 清单存在则跳过解析、直接按清单续搬（源路径同 taskId 不变） |

---

## 7. 取消机制（Redis）

**职责分离**：用户操作 → API 处理 → Worker 执行。Worker 不修改取消意图。

| 动作 | 位置 | 行为 |
|------|------|------|
| 取消 | API `cancelTask` | 写 Redis `import:cancel:{taskId}=1`（TTL 7 天）+ 保留现有 CancelTaskEvent MQ |
| 消费取消 | Worker `CancelHandler` | 消费 MQ 后写 Redis（幂等）；`isCancelled(taskId)` 读 Redis |
| 重试 | API `retryTask` | 删 Redis `import:cancel:{taskId}` → 重新发布 ImportTaskCreatedEvent |

**Worker 新增依赖**：`spring-boot-starter-data-redis` + `spring.data.redis` 配置（与 API 侧一致，docker-compose 已有 Redis）。

> 替代原设计中 `CancelHandler.clear()` + `ImportTaskHandler` 改动的方案：不引入 Worker 自行 clear，取消状态以 Redis 为唯一事实来源，API 重试时删除 key。

---

## 8. 变更文件清单

| 文件 | 变更 |
|------|------|
| `worker-service/pom.xml` | 新增 `spring-boot-starter-data-redis` |
| `worker-service/.../application.yml` | 新增 `spring.data.redis` 配置 |
| `worker-service/.../file/storage/TransferMode.java`（新） | 枚举 COPY/MOVE |
| `worker-service/.../file/storage/SafeMoveStrategy.java`（新） | 同卷/跨卷安全 move |
| `worker-service/.../file/storage/TransferService.java`（新） | 搬运门面 |
| `worker-service/.../file/storage/StorageService.java` | `store` → `transfer(source, target, mode)` |
| `worker-service/.../file/storage/LocalStorageService.java` | **删除**（唯一消费者为 DirectoryImportHandler，由 TransferService 取代） |
| `worker-service/.../file/import/ImportManifest.java`（新） | 清单 POJO |
| `worker-service/.../file/import/ImportManifestManager.java`（新） | 清单读写（原子写）+ 恢复判定 |
| `worker-service/.../file/handler/DirectoryImportHandler.java` | 重构：清单驱动搬运 + metadata 从清单出 |
| `worker-service/.../event/CancelHandler.java` | ConcurrentHashMap → Redis |
| `api-service/.../service/impl/ImportServiceImpl.java` | cancelTask 写 Redis；retryTask 删 Redis key |

---

## 9. 错误处理与边界

| 场景 | 行为 |
|------|------|
| 清单 JSON 损坏 | 报错并提示人工处理，**绝不**回退到"重新解析源目录" |
| 跨卷 copy 中断 | 残留 `.tmp`，下次 REPLACE_EXISTING 覆盖；目标名永不见半截文件 |
| 同卷 move 后崩溃 | 目标完整存在，恢复跳过，metadata 照写 |
| 取消后重试 | API 删除 Redis key → 正常续搬 |
| 源文件被外部删除 | 恢复时 dst 缺失且 src 不在 → 明确报错，不静默丢页 |
| 目标存在但大小不匹配 | 报错，不覆盖（保护既有数据） |

---

## 10. 测试

- 单元：`SafeMoveStrategy` 同卷 move、`.tmp` 残留清理、跨卷校验失败抛错
- 单元：`ImportManifestManager` 读写/原子写/损坏清单解析失败
- 单元：`CancelHandler` Redis 读写（mock RedisTemplate）
- 集成：`DirectoryImportHandler` 扩展——
  1. 正常导入：源目录被搬空，metadata 完整（缺一不可）
  2. 模拟中断（搬 N 个文件后抛异常）→ 重试 → 续搬剩余，metadata 含全部页
  3. 模拟取消（Redis 标记）→ 重试（删标记）→ 正常完成
  4. 目标已存在且大小匹配 → 跳过不重复搬
- API：`ImportServiceTest` 更新 cancelTask 写 Redis / retryTask 删 Redis key 的 verify

---

## 11. 发布说明

- 无需 DB 迁移
- 无需 API 对外接口/前端改动
- 唯一行为变化：REGISTER 导入后**源目录被清空**（move 语义），用户需知晓
- Worker 新增 Redis 依赖，需保证 Redis 可用（docker-compose 已有）
