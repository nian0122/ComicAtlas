# 03 - 存储模型

> 本文档描述 ComicAtlas 的物理存储布局、存储策略、存储服务职责和 Page 实体的存储字段。
> URL 生成规则在末尾抽象说明，具体 Nginx 路由映射见 `docs/api.md`。

---

## 1. 存储策略 (StoragePolicy)

| 策略 | 说明 | 当前状态 |
|------|------|----------|
| `MANAGED` | 文件由 ComicAtlas 统一管理，导入时搬入 HQ/LQ 根目录 | **当前唯一使用** |
| `EXTERNAL` | 文件由外部系统管理，DB 只存引用路径 | 未来预留 |
| `OBJECT_STORAGE` | 对象存储后端（S3/MinIO 等） | 未来预留 |

当前所有漫画均使用 `MANAGED` 策略。`comic.storage_policy` 字段值为 `MANAGED`。

---

## 2. MANAGED 文件布局

所有导入的漫画文件统一搬入 `D:/manga/` 下的托管目录。物理路径以 `{chapterId}` 作为章节级分段（不是 `globalOrder`）。

```text
D:/manga/                          # MANGA_ROOT
├── hq/                            # HQ 原图存储根
│   └── {comicId}/
│       └── {chapterId}/
│           └── {imageName}        # 如 001.jpg
├── lq/                            # LQ 低质量图存储根
│   └── {comicId}/
│       └── {chapterId}/
│           └── {imageName}
├── thumbs/                        # 封面缩略图（Worker 配置目录，非 StorageRoot）
│   └── {comicId}/
│       └── cover.webp
└── metadata/                      # 导入元数据 JSON（Worker 配置目录，非 StorageRoot）
    └── {comicId}.json
```

### 布局规则

- **HQ 页面路径**: `{comicId}/{chapterId}/{imageName}`
- **LQ 页面路径**: 与 HQ 同构，仅根目录不同
- **路径分隔符**: 统一使用 `/`，代码中通过 `replace('\\', '/')` 规范化
- **布局接口**: `StorageLayout.forPage(comicId, chapterId, imageName)` 返回相对路径字符串

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

Worker 侧的存储抽象由 `StorageService` 接口和 `LocalStorageService` 实现组成。

### 3.1 StorageService 接口

```java
public interface StorageService {
    StorageRef store(Path source, String rootKey, String relativePath);
    Path resolve(StorageRef ref);
    boolean exists(StorageRef ref);
    void delete(StorageRef ref);
}
```

| 方法 | 职责 |
|------|------|
| `store` | 将源文件复制到指定 root 下的相对路径，返回 `StorageRef` |
| `resolve` | 将 `StorageRef` 解析为物理 `Path` |
| `exists` | 检查 `StorageRef` 对应的文件是否存在 |
| `delete` | 删除 `StorageRef` 对应的文件 |

### 3.2 LocalStorageService

`LocalStorageService` 是 `StorageService` 的本地文件系统实现。它通过 `StorageProperties` 获取已注册的 `StorageRoot` 映射。

核心行为：

- `store`: 创建父目录，使用 `Files.copy` + `REPLACE_EXISTING` 复制文件
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
      path: ${MANGA_ROOT:D:/manga}/hq
    LQ:
      type: FILESYSTEM
      path: ${MANGA_ROOT:D:/manga}/lq
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
| `fileSize` | `Long` | HQ 文件大小（字节） |
| `lqSize` | `Long` | LQ 文件大小（字节） |
| `width` | `Integer` | 图片宽度（像素） |
| `height` | `Integer` | 图片高度（像素） |

### 字段说明

- `hqRoot` + `hqPath` 组合定位 HQ 文件。`hqRoot` 对应 `StorageRoot` 的 map key，`hqPath` 是该根下的相对路径。
- `fileSize` 记录 HQ 原始文件大小，`lqSize` 记录 LQ 文件大小。注意 HQ 文件大小字段名为 `fileSize`。
- `lqStatus` 初始值为 `NOT_GENERATED`，LQ 不自动生成，需手动触发。
- `width` / `height` 为图片尺寸元数据，在导入时提取。

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
| `StorageService` (Worker) | 文件复制、解析、存在检查、删除 | 数据库写入、URL 生成 |
| `StorageLayout` (API) | 计算页面相对路径 | 文件操作、URL 拼接 |
| `FileUrlResolver` (API) | 将 Page 存储字段转为 HTTP URL | 物理文件管理 |
| `ImportEventHandler` (API) | 读 metadata.json 写数据库 | 文件搬移 |
| Worker Handlers | 解析、搬文件、写 metadata.json | 数据库业务表写入 |
