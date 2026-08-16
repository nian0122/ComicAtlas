# 03 - 存储模型

> 本文档描述 ComicAtlas 的物理存储布局、存储策略、存储服务职责和 Page 实体的存储字段。
> URL 生成规则在末尾抽象说明，具体 Nginx 路由映射见 `docs/api.md`。

---

## 1. 存储策略 (StoragePolicy)

| 策略 | 说明 |
|------|------|
| `MANAGED` | 文件由 ComicAtlas 统一管理，导入时搬入 HQ/LQ 根目录 |

当前所有漫画均使用 `MANAGED` 策略。`comic.storage_policy` 字段固定为 `MANAGED`。

---

## 2. MANAGED 文件布局

所有导入的漫画文件统一搬入 `F:/manga/` 下的托管目录。物理路径以 `{chapterId}` 作为章节级分段（不是 `globalOrder`）。

```text
F:/manga/                          # MANGA_ROOT
├── hq/                            # HQ 原图存储根
│   └── {comicId}/
│       └── {chapterId}/
│           └── {imageName}        # 如 001.jpg
├── lq/                            # LQ 低质量图存储根
│   └── {comicId}/
│       └── {chapterId}/
│           └── {imageName}
├── imports/                       # 导入清单（恢复点，最终化后删除）
│   └── {taskId}/
│       └── manifest.json
├── thumbs/                        # 封面缩略图（Worker 配置目录，非 StorageRoot）
│   └── {comicId}/
│       └── cover.webp
├── metadata/                      # 导入元数据 JSON（Worker 配置目录，非 StorageRoot）
│   ├── {taskId}.json
│   └── {comicId}.json
└── staging/ trash/ export/        # 上传临时 / 回收站文件卷 / 导出产物
```

### 布局规则

- **HQ 页面路径**: `{comicId}/{chapterId}/{imageName}`
- **LQ 页面路径**: 与 HQ 同构，仅根目录不同
- **路径分隔符**: 统一使用 `/`，代码中通过 `replace('\\', '/')` 规范化
- **布局接口**: `StorageLayout.forPage(comicId, chapterId, imageName)` 返回相对路径字符串

### 两阶段落位（staging → finalize）

导入文件不直接落到最终位置，而是先暂存、最终化后再就位：

| 阶段 | HQ 路径 | 说明 |
|------|---------|------|
| 暂存（DirectoryImportHandler） | `{comicId}/{globalOrder}/{fileName}` | Worker 按规范化 globalOrder 暂存 |
| 最终化（ImportStorageFinalizeHandler） | `{comicId}/{chapterId}/{fileName}` | Worker 按章节移动到最终位置 |

- 最终化前 DB 中 `page.hq_path` 为 PENDING；最终化完成后由 API 按 finalize completed 事件的 `targetDir` 修正为 `{comicId}/{chapterId}/{fileName}` 并置 READY。
- **`chapterId == globalOrder` 时**（DB 自增恰好等于全书顺序）：暂存即最终位置，无需移动文件，仅校验尺寸并照常确认。
- 迁移存储时只改 `storage.roots.HQ.path` 配置与 `MANGA_ROOT`，不改 DB 页面路径。

### 重要区分

| 目录 | 是否为 StorageRoot | 说明 |
|------|-------------------|------|
| `hq/` | 是 (key=`HQ`) | 配置在 `storage.roots.HQ` |
| `lq/` | 是 (key=`LQ`) | 配置在 `storage.roots.LQ` |
| `thumbs/` | **否** | Worker 直接写入的独立目录 |
| `metadata/` | **否** | Worker 直接写入的独立目录 |

`metadata/` 和 `thumbs/` 是 Worker 在导入流程中直接使用的目录，不参与 `StorageRoot` 注册机制。

---

## 3. 存储服务 (Worker)

Worker 侧的存储抽象由 `StorageService` 接口和 `TransferService` 实现组成。

### 3.1 StorageService 接口

```java
public interface StorageService {
    StorageRef transfer(Path source, StorageRef target, TransferMode mode);
    Path resolve(StorageRef ref);
    boolean exists(StorageRef ref);
    void delete(StorageRef ref);
}
```

| 方法 | 职责 |
|------|------|
| `transfer` | 按 `TransferMode`（COPY/MOVE）将源文件复制/移动到目标 `StorageRef`，返回目标引用 |
| `resolve` | 将 `StorageRef` 解析为物理 `Path` |
| `exists` | 检查 `StorageRef` 对应的文件是否存在 |
| `delete` | 删除 `StorageRef` 对应的文件 |

### 3.2 TransferService

`TransferService` 是 `StorageService` 的本地文件系统实现。它通过 `StorageProperties` 获取已注册的 `StorageRoot` 映射，并按 `TransferMode` 决定复制或移动。

核心行为：

- `transfer`: 创建父目录，COPY 模式使用 `Files.copy` + `REPLACE_EXISTING`，MOVE 模式通过 `SafeMoveStrategy` 安全移动
- `resolve`: 通过 `rootKey` 查找 `StorageRoot`，调用 `root.resolve(relativePath)` 得到绝对路径
- `delete`: 调用 `Files.deleteIfExists`，失败时仅 warn 不抛异常

### 3.3 StorageRoot 与 StorageRef

**StorageRoot** 代表一个物理存储根目录：

```java
public class StorageRoot {
    private String type = "FILESYSTEM";
    private Path path;
    private boolean enabled = true;
    private boolean readOnly = false;

    public Path resolve(String relativePath) { ... }
    public boolean exists() { ... }
}
```

**StorageRef** 是一个不可变引用，由 `rootKey` + `relativePath` 组成：

```java
public record StorageRef(String rootKey, String relativePath) { }
```

### 3.4 配置

Worker 的 `application.yml` 中注册了两个 StorageRoot：

```yaml
storage:
  roots:
    HQ:
      type: FILESYSTEM
      path: ${MANGA_ROOT:F:/manga}/hq
    LQ:
      type: FILESYSTEM
      path: ${MANGA_ROOT:F:/manga}/lq
```

`StorageProperties` 通过 `@ConfigurationProperties(prefix = "storage")` 绑定配置，暴露 `Map<String, StorageRoot> roots`。

---

## 4. Page 存储字段

`Page` 实体记录每一页图片的存储信息。所有路径均为相对路径，不存绝对路径。

| 字段 | 类型 | 说明 |
|------|------|------|
| `hqRoot` | `String` | HQ 存储根 key，如 `HQ` |
| `hqPath` | `String` | HQ 相对路径，如 `{comicId}/{chapterId}/001.jpg` |
| `lqRoot` | `String` | LQ 存储根 key（LQ 未生成时为 null） |
| `lqPath` | `String` | LQ 相对路径 |
| `hqStatus` | `String` | HQ 文件状态（如 `READY`） |
| `lqStatus` | `String` | LQ 文件状态（如 `NOT_GENERATED`） |
| `hqSize` | `Long` | HQ 文件大小（字节） |
| `lqSize` | `Long` | LQ 文件大小（字节） |
| `width` | `Integer` | 图片宽度（像素） |
| `height` | `Integer` | 图片高度（像素） |

### 字段说明

- `hqRoot` + `hqPath` 组合定位 HQ 文件。`hqRoot` 对应 `StorageRoot` 的 map key，`hqPath` 是该根下的相对路径。
- `hqPath` 最终形态为 `{comicId}/{chapterId}/{fileName}`；最终化前为 `{comicId}/{globalOrder}/{fileName}`（PENDING），由 API 在 finalize completed 时按事件 `targetDir` 修正。
- `hqSize` 记录 HQ 原始文件大小，`lqSize` 记录 LQ 文件大小。数据库字段分别为 `hq_size` 与 `lq_size`。
- `lqStatus` 初始值为 `NOT_GENERATED`，LQ 不自动生成，需手动触发。
- `hq_status` 生命周期：`PENDING`（staging 落库）→ `READY`（最终化完成）→ `DELETED`（HQ 删除后），另有 `MISSING`（文件丢失）、`DELETE_QUEUED`/`DELETING`/`FAILED`。
- `width` / `height` 为图片尺寸元数据，在导入时提取。

### metadata 中的 hqPath（真实 StorageRef）

`metadata/{comicId}.json` 的 `mediaItems[].hqPath` 是**真实 StorageRef**（`{comicId}/{globalOrder}/{fileName}`，暂存布局），不是 DB 中的最终路径：

- 导入时由 `DirectoryImportHandler` 按清单目标写入；DB 落库后 API 统一用 finalize 事件的 `targetDir` 修正为 `{comicId}/{chapterId}/` 布局。
- 封面候选选择器、恢复引擎均按该字段定位 HQ 文件，不手拼路径。

### legacy globalOrder 兼容恢复

升级库（Flyway baseline=2）中的旧数据页面 `hq_path` 可能为 `{comicId}/{chapterId}/{fileName}`（旧布局）或历史 globalOrder 布局。恢复与读取按以下规则兼容：

- 最终化完成的页面一律以 DB `page.hq_path` 为准（`{comicId}/{chapterId}/`），阅读/删除/LQ 均通过 `FileUrlResolver` 统一解析，不做路径猜测。
- RecoveryEngine 从 `metadata/{comicId}.json` 重建时，若 metadata 缺失则以 HQ 磁盘目录结构为准（`{comicId}/{chapterId}/`），旧 globalOrder 目录不再作为新布局来源。
- 迁移存储只改根配置，禁止改写 DB 路径列。

---

## 5. URL 生成

图片 URL 由 API 侧的 `FileUrlResolver` 统一生成，不手动拼接。

### URL 格式

```text
/files/{rootKey_lc}/{relativePath}
```

- `rootKey_lc`: 存储根 key 的小写形式（`HQ` -> `hq`，`LQ` -> `lq`）
- `relativePath`: Page 实体中的 `hqPath` 或 `lqPath`

### 示例

```text
/files/hq/1/3/001.jpg       # HQ 原图
/files/lq/1/3/001.jpg       # LQ 低质量图
/files/thumbs/1/cover.webp  # 封面缩略图
```

### FileUrlResolver 方法

| 方法 | 输入 | 输出 |
|------|------|------|
| `resolve(Page)` | Page 实体 | HQ URL (`/files/{hqRoot_lc}/{hqPath}`) |
| `resolveLq(Page)` | Page 实体 | LQ URL (`/files/{lqRoot_lc}/{lqPath}`) |
| `resolveCover(comicId)` | 漫画 ID | 默认封面 URL (`/files/thumbs/{comicId}/cover.webp`) |
| `resolveCover(comicId, coverPath)` | 漫画 ID + 自定义封面路径 | 自定义封面 URL 或默认封面 URL |

URL 前缀通过 `storage.url-prefix` 配置，默认 `/files`。Nginx 将 `/files/{root}/` 路由映射到对应的物理存储目录。

---

## 6. 职责边界

| 组件 | 负责 | 不负责 |
|------|------|--------|
| `StorageService` (Worker) | 文件复制/移动、解析、存在检查、删除 | 数据库写入、URL 生成 |
| `ImportManifestManager` (Worker) | 导入清单（`imports/{taskId}/manifest.json`）的原子读写与逐章移除 | 落库 |
| `StorageLayout` (API) | 计算页面相对路径 | 文件操作、URL 拼接 |
| `FileUrlResolver` (API) | 将 Page 存储字段转为 HTTP URL | 物理文件管理 |
| `ImportPersistenceService` (API) | completed/finalize completed 事件驱动 DB 状态推进 | 文件搬移 |
| `ImportStorageFinalizeHandler` (Worker) | 按清单校验尺寸并把 `{globalOrder}` 移动到 `{chapterId}` | DB 业务表写入 |
