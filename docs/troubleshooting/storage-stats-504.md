# 存储统计 504 修复与踩坑记录

> 记录时间：2026-08-02
> 关联改动：`storage/stats` 接口改为 DB 聚合 + Redis 缓存；Redis 缓存序列化修复

## 背景

漫画 203 LQ 迁移后，管理后台"存储管理"页打不开，控制台报：

```
GET http://localhost/api/admin/storage/stats 504 (Gateway Time-out)
```

## 坑 1：文件系统全量遍历导致超时（根因）

`AdminServiceImpl.getStorageStats()` 用 `dirSize()` 统计 HQ/LQ/thumbs 字节数：

```java
Files.walk(dir)  // 全量递归
    .filter(Files::isRegularFile)
    .mapToLong(p -> Files.size(p))  // 每个文件一次系统调用
    .sum();
```

LQ 迁移后文件量暴增：**hq 63,485 + lq 23,761 + thumbs 84 = 87,330 个文件**。Docker 容器内每个 `Files.size()` 都是跨文件系统调用，本地 PowerShell 遍历都要 9 秒，容器内经挂载访问更慢，最终超过 nginx 默认 60s 超时 → 504。

### 解法

`StorageMapper.xml` 新增 DB 聚合（毫秒级），`page` 表已有 `file_size`/`lq_size`：

```sql
SELECT
    COALESCE(SUM(CASE WHEN m.hq_status = 'READY' THEN COALESCE(m.file_size, 0) ELSE 0 END), 0) AS hq_bytes,
    COALESCE(SUM(CASE WHEN m.lq_status = 'READY' THEN COALESCE(m.lq_size, 0) ELSE 0 END), 0) AS lq_bytes
FROM page m
JOIN chapter ch ON ch.id = m.chapter_id
JOIN comic c ON c.id = ch.comic_id
WHERE c.deleted_at IS NULL
```

- `comicCount` 改查 `comic WHERE deleted_at IS NULL`
- thumbs 仅 84 个文件，保留 `dirSize`，但整个 `getStorageStats()` 加了 `@Cacheable`（TTL 5m）

**教训**：任何"统计总大小/数量"都不要遍历文件系统，优先用 DB 已存的元数据字段。

## 坑 2：PowerShell 测速假象（浪费大量排查时间）

修复后测试接口仍然"21 秒"，一度误判为 Docker Desktop 端口转发故障（甚至准备重启 Docker）：

- PowerShell `Invoke-WebRequest http://localhost/...` → **21 秒**
- 容器内 `wget` 同一地址 → **0.09 秒**
- curl.exe 从宿主访问 → **0.2 秒**

真相：**.NET HttpClient 访问 `localhost` 先试 IPv6（`::1`）再回退 IPv4（`127.0.0.1`），每次回退耗 21 秒**。容器内 wget 只走 IPv4，所以快。

### 解法

用 curl.exe 测速代替 PowerShell `Invoke-WebRequest`：

```powershell
curl.exe -s -o NUL -w "time_total=%{time_total}s" http://localhost/api/tags
```

**教训**：Windows 宿主测容器端口，优先 curl；PowerShell 的 21s"延迟"是 IPv6 回退假象，与网络/网关无关。排查期间曾怀疑 LoadBalancer→Nacos、Docker 转发层、gRPC 通道，全部排除后才想到是测量工具本身的问题。

## 坑 3：缓存值序列化包含只读属性导致反序列化失败

`StorageStatsDTO` 有计算属性 `getTotalBytes()`（无 setter），`@Data` 让 Jackson 把它当普通属性序列化进 Redis，反序列化时找不到 setter → `UnrecognizedPropertyException` → 缓存永不命中，每次回源。

### 解法

```java
@JsonIgnore
public long getTotalBytes() {
    return hqBytes + lqBytes + thumbBytes;
}
```

**教训**：缓存的 DTO 若含只读计算属性，必须 `@JsonIgnore`。

## 坑 4：缓存方法返回 final 集合导致根类型 id 缺失

修复后 `comicTags` 缓存仍读取失败，日志报：

```
MismatchedInputException: Unexpected token (START_ARRAY), expected VALUE_STRING:
need String, Number or Boolean value that contains type id
```

根因：`TagServiceImpl.listTags()` 用 `Stream.toList()` 返回 `ImmutableCollections$ListN`（**final 类**）。Redis 序列化器配置了 `DefaultTyping.NON_FINAL`——**对 final 类不写根类型 id**，存成裸数组 `[[...]]`；而反序列化静态类型是 `Object`，`AsArrayTypeDeserializer` 期望根第一个 token 是类型字符串 → 报错。

对比：`storageStats`（单对象 `StorageStatsDTO`，非 final）序列化正常，所以只有集合缓存中招。

### 解法

缓存方法返回非 final 的 `ArrayList`：

```java
return new ArrayList<>(tags.stream().map(this::toDTO).toList());
```

受影响：`TagServiceImpl.listTags()`、`CategoryServiceImpl.listCategories()`。
不受影响：`ComicListPage`（本身非 final）、`CatalogServiceImpl`（用 `Collectors.toList()` 返回 ArrayList）。

**教训**：缓存序列化开启 `DefaultTyping.NON_FINAL` 时，`@Cacheable` 方法**不要返回 `Stream.toList()` 的 final 集合**，包一层 `new ArrayList<>()`。

## 坑 5：旧格式缓存残留导致升级后读取失败

RedisConfig 曾用无类型信息的序列化器写过缓存，升级为 `GenericJackson2JsonRedisSerializer`（带 default typing）后旧 key 读不兼容 → 报错 → 回源 DB（功能正常但缓存失效）。

### 解法

清理旧 key 让其按新格式重写：

```redis
DEL comicTags::all comicCategories::all
```

**教训**：序列化器格式变更后需清理旧缓存 key，否则缓存永不命中。

## 验证结果

- stats 首次 1.16s → 缓存命中 0.25s（原 504）
- tags/categories 命中缓存 0.26s/0.28s
- Redis 缓存读取失败告警归零
- `CatalogCacheTest` 7/7 通过（RedisConfig cacheManager 新增参数需同步测试）

## 相关文件

- `api-service/.../admin/service/impl/AdminServiceImpl.java`
- `api-service/.../admin/mapper/StorageMapper.java` + `StorageMapper.xml`
- `api-service/.../admin/dto/StorageStatsDTO.java`
- `api-service/.../config/RedisConfig.java` + `comic/cache/ComicReferenceCache.java`
- `api-service/.../comic/service/impl/TagServiceImpl.java`、`CategoryServiceImpl.java`
