# ADR-0001: 统一导入流水线

**日期**: 2026-07-16  
**状态**: 已接受  
**决策者**: ComicAtlas 架构组

---

## 问题

ComicAtlas 需要支持多种漫画来源：ZIP 压缩包、本地目录、EHentai 抓取，未来可能还有 Torrent、SMB 等。每种来源的文件获取方式不同，但最终都需要完成相同的后续步骤：

1. 解析文件结构，识别漫画、卷、章节、页面
2. 生成结构化元数据（Catalog/Chapter/Page）
3. 将文件搬入托管存储（HQ/LQ/Thumbs）
4. 写入数据库（comic/catalog/chapter/page 表）

如果为每种来源独立实现一套完整的导入服务（ZipImportService、DirectoryImportService、EHentaiImportService），会导致：

- 数据库写入逻辑重复，修改一处需同步多处
- 新增来源时需要复制大量样板代码
- 不同来源的行为容易漂移，难以保持一致性

核心问题：**如何在支持多来源的同时，复用解析后的元数据组装、文件搬迁和数据库写入逻辑？**

---

## 决策

采用统一导入流水线，所有来源共享相同的后置处理链路：

```
Source (ZIP / Directory / EHentai / ...)
    │
    ▼
ImportTask (MQ: task.created)
    │
    ▼
ImportTaskHandler (按 sourceType 路由)
    │
    ├── ZipImportHandler ──► 解压到临时目录 ──┐
    ├── DirectoryImportHandler ───────────────┤
    └── EHentaiImportHandler (future) ────────┤
                                              ▼
                                    DirectoryParser
                                              │
                                              ▼
                                    DirectoryTree (纯目录结构)
                                              │
                                              ▼
                                    MetadataAssembler
                                              │
                                              ▼
                                    ComicMetadata (immutable record)
                                              │
                                              ▼
                                    StorageService + DirectoryImportHandler
                                              │
                                              ▼
                                    HQ / LQ / Thumbs + metadata.json
                                              │
                                              ▼
                                    MQ: task.completed
                                              │
                                              ▼
                                    API ImportEventHandler
                                              │
                                              ▼
                                    Database (comic/catalog/chapter/page)
```

**关键设计点**：

1. **来源只影响前置 Handler**：ZIP 需要先解压，EHentai 需要先抓取，Directory 直接扫描。这些差异在 Handler 层处理，不影响后续流程。

2. **DirectoryParser 输出纯目录结构**：`DirectoryTree` 只包含文件系统信息（目录名、文件列表、层级），不包含任何业务语义（不知道什么是 Catalog、Chapter）。

3. **MetadataAssembler 注入业务语义**：将 `DirectoryTree` 转换为 `ComicMetadata`，决定哪些目录是 Catalog、哪些是 Chapter，生成 `global_order` 等。

4. **ComicMetadata 是不可变记录**：一旦生成，后续流程只读不改。StorageService + DirectoryImportHandler 负责文件搬迁，API 侧的 `ImportEventHandler` 负责数据库写入。

5. **API Service 是数据库的唯一写入方**：Worker 不直接写 MySQL，全部通过 MQ 事件（`task.completed`）回传元数据，由 API 侧消费并写入数据库。

**实现示例**：

- `ZipImportHandler`：解压 ZIP 到临时目录，然后委托 `DirectoryImportHandler` 处理解压后的目录。
- `DirectoryImportHandler`：调用 `DirectoryParser.parse()` 得到 `DirectoryTree`，调用 `MetadataAssembler.assemble()` 得到 `ComicMetadata`，搬文件到 HQ，写 `metadata.json`。
- 未来 `EHentaiImportHandler`：抓取远程图片到本地目录，然后同样委托 `DirectoryImportHandler`。

---

## 替代方案

### 方案 A：每个来源独立 Service（已拒绝）

为每种来源实现独立的导入服务：

```
ZipImportService      → 解析 ZIP → 组装元数据 → 搬文件 → 写数据库
DirectoryImportService → 扫描目录 → 组装元数据 → 搬文件 → 写数据库
EHentaiImportService   → 抓取远程 → 组装元数据 → 搬文件 → 写数据库
```

**拒绝理由**：

- 数据库写入逻辑（INSERT comic/catalog/chapter/page）在每个 Service 中重复
- 修改元数据模型（如增加 `description` 字段）需要同步修改所有 Service
- 新增来源（如 Torrent）需要复制大量代码
- 不同来源的行为容易漂移，难以保证一致性

### 方案 B：统一 Pipeline，来源只影响前置 Handler/Parser（已接受）

所有来源共享相同的后置处理链路，差异只在 Handler 层：

```
Handler (来源特定) → DirectoryParser → MetadataAssembler → StorageService + DirectoryImportHandler → DB Writer
```

**接受理由**：

- 数据库写入逻辑只在一处（API 侧的 `ImportEventHandler`）
- 新增来源只需实现 Handler + 可选的 Parser，无需改动 API 侧
- `DirectoryTree` 成为可复用的中间结构，未来可以支持不同的 Parser（如 WebParser）
- 职责清晰：Handler 负责获取文件，Parser 负责解析结构，Assembler 负责业务语义，StorageService + DirectoryImportHandler 负责文件生命周期

---

## 后果

### 正面影响

1. **新增来源成本低**：只需实现 `ImportHandler` 接口，可选实现自定义 Parser。例如：
   - `EHentaiImportHandler`：抓取远程图片到本地，然后委托 `DirectoryImportHandler`
   - `TorrentImportHandler`：下载 Torrent 到本地，然后委托 `DirectoryImportHandler`

2. **`DirectoryTree` 成为可复用中间结构**：不同的 Parser（DirectoryParser、WebParser）都可以输出 `DirectoryTree`，共享同一个 `MetadataAssembler`。

3. **职责边界清晰**：
   - `DirectoryParser`：纯 NIO，不关心业务语义
   - `MetadataAssembler`：注入业务语义，不碰文件系统
   - `StorageService + DirectoryImportHandler`：管文件生命周期，不写数据库
   - `ImportEventHandler`（API）：写数据库，不碰文件系统

4. **易于测试**：每个组件可以独立测试。`MetadataAssembler` 可以给定 `DirectoryTree` 验证输出的 `ComicMetadata`，不需要真实的文件系统。

### 负面影响

1. **需要定义清晰的 `ComicMetadata` 模型**：`ComicMetadata` 是流水线的核心数据结构，必须包含足够的信息（title、catalogs、chapters、pages）供后续流程使用。模型设计不当会导致频繁修改。

2. **`DirectoryTree` 的抽象成本**：`DirectoryTree` 需要足够通用，能够表达不同来源的目录结构。过于具体会限制扩展性，过于抽象会增加 `MetadataAssembler` 的复杂度。

3. **MQ 事件的序列化成本**：Worker 和 API 之间通过 MQ 传递 `task.completed` 事件，需要序列化 `ComicMetadata`。大漫画（数千页）可能导致消息体过大。当前通过 `metadata.json` 文件传递元数据，MQ 只传递文件路径，避免了这个问题。

4. **错误处理的复杂性**：流水线中任何一步失败都需要回滚（如已搬文件但数据库写入失败）。当前通过 `ImportTask` 状态机（PENDING → PARSING → DONE/FAILED）和死信队列（DLQ）处理失败，但重试逻辑需要谨慎设计。

### 中性影响

1. **`DirectoryParser` 不了解业务语义**：这意味着 `DirectoryParser` 无法处理需要业务知识的场景（如识别"番外"目录应该作为特殊 Catalog）。这类逻辑必须放在 `MetadataAssembler` 中。

2. **Worker 不直接写数据库**：这保证了数据库写入的一致性（只有一个写入方），但也意味着 Worker 无法立即知道导入是否成功（需要等待 API 侧消费 MQ 事件）。

---

## 参考

- 核心架构文档：`docs/superpowers/specs/2026-07-01-ComicAtlas-core-architecture.md` §2
- 设计文档：`docs/superpowers/specs/2026-07-16-project-docs-update-phase1-design.md` §3.4
- 实现代码：
  - `worker-service/.../event/ImportTaskHandler.java`：MQ 消费，路由到具体 Handler
  - `worker-service/.../file/handler/DirectoryImportHandler.java`：统一导入逻辑
  - `worker-service/.../file/handler/ZipImportHandler.java`：解压后委托 DirectoryImportHandler
  - `worker-service/.../file/parse/DirectoryParser.java`：输出 DirectoryTree
  - `worker-service/.../file/parse/MetadataAssembler.java`：DirectoryTree → ComicMetadata
