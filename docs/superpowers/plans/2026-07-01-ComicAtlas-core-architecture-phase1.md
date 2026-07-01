# ComicAtlas Core Architecture - Phase 1 Implementation Plan

> **For agentic workers:** Use subagent-driven-development to implement this plan task-by-task.
>
> **Architecture Baseline:** `docs/superpowers/specs/2026-07-01-ComicAtlas-core-architecture.md` v1.2 (Frozen)
>
> **语言**: 中文 commit message, 中文注释

**Goal:** 将 ComicAtlas 代码库从当前扁平 chapter 模型迁移到 v1.2 核心架构（Catalog + Chapter 双表、StorageRef 存储抽象、ImportPipeline 重构、服务拆分）。

**Architecture:** 四个里程碑按依赖顺序执行——M1 数据库+枚举（底层）、M2 存储抽象（基础设施）、M3 导入流水线（业务核心）、M4 阅读模型+API（接口层）。每里程碑可独立测试和提交。

**Tech Stack:** Java 21, Spring Boot 3.3, MyBatis Plus, RabbitMQ, MySQL 8, Vue3 + TypeScript + Pinia + Element Plus

---

## 里程碑总览

| 里程碑 | 内容 | 预计时间 | 可独立验证 |
|--------|------|----------|-----------|
| M1 | DB Schema + 枚举 | 30min | MySQL 表结构、编译通过 |
| M2 | 存储抽象层 | 30min | 单元测试 |
| M3 | 导入流水线重构 | 45min | ZIP + REGISTER 导入测试 |
| M4 | 阅读模型 + API + 前端 | 45min | Postman API + 前端渲染 |

---

## 里程碑 1：DB Schema + 枚举体系

### Task 1.1: 建立枚举类

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/common/enums/SourceType.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/enums/StoragePolicy.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/enums/ComicStatus.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/enums/ImportTaskStatus.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/enums/HqStatus.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/enums/LqStatus.java`

- [ ] **Step 1: 创建所有枚举**

```java
// SourceType.java
package com.comicatlas.api.common.enums;

public enum SourceType { ZIP, REGISTER, EHENTAI }

// StoragePolicy.java
package com.comicatlas.api.common.enums;

public enum StoragePolicy { MANAGED, EXTERNAL }

// ComicStatus.java
package com.comicatlas.api.common.enums;

public enum ComicStatus { IMPORTING, READY, DELETING, DELETED, RESCANNING }

// ImportTaskStatus.java
package com.comicatlas.api.common.enums;

public enum ImportTaskStatus { PENDING, PARSING, IMPORTING, SUCCESS, FAILED }

// HqStatus.java
package com.comicatlas.api.common.enums;

public enum HqStatus { PENDING, READY, MISSING }

// LqStatus.java
package com.comicatlas.api.common.enums;

public enum LqStatus { PENDING, READY, FAILED }
```

- [ ] **Step 2: 编译验证**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "feat: 建立枚举体系 - SourceType/StoragePolicy/ComicStatus/ImportTaskStatus/HqStatus/LqStatus"
```

---

### Task 1.2: 数据库 Schema 变更

**Files:**
- Modify: `api-service/src/main/resources/db/schema.sql`
- Create: `api-service/src/main/resources/db/migration/V2__core_architecture.sql`

- [ ] **Step 1: 创建 migration SQL**

```sql
-- V2__core_architecture.sql
-- ComicAtlas Core Architecture v1.2 DB Migration

USE comic_atlas;

-- 1. comic: storage_type → storage_policy
ALTER TABLE comic CHANGE COLUMN storage_type storage_policy VARCHAR(16) DEFAULT 'MANAGED';

-- 2. 新增 catalog 表
CREATE TABLE IF NOT EXISTS catalog (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id    BIGINT NOT NULL,
    parent_id   BIGINT DEFAULT NULL,
    title       VARCHAR(255) NOT NULL,
    sort_order  INT DEFAULT 0,
    path        VARCHAR(512) DEFAULT NULL,
    level       INT DEFAULT 0,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES catalog(id) ON DELETE CASCADE,
    UNIQUE INDEX uk_comic_parent_title (comic_id, parent_id, title),
    INDEX idx_comic_parent (comic_id, parent_id),
    INDEX idx_path (path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. chapter: 新增 catalog_id, sort_order, global_order, 调整唯一约束
ALTER TABLE chapter
    ADD COLUMN catalog_id    BIGINT DEFAULT NULL AFTER comic_id,
    ADD COLUMN sort_order    INT DEFAULT 0 AFTER chapter_no,
    ADD COLUMN global_order  INT DEFAULT 0 AFTER sort_order,
    DROP INDEX uk_comic_chapter,
    ADD UNIQUE INDEX uk_catalog_chapter (comic_id, catalog_id, chapter_no),
    ADD FOREIGN KEY fk_chapter_catalog (catalog_id) REFERENCES catalog(id) ON DELETE SET NULL,
    ADD INDEX idx_comic_global (comic_id, global_order);

-- 4. page: drop image_name, 新增 hq_root/hq_path/lq_root/lq_path
ALTER TABLE page
    DROP COLUMN image_name,
    ADD COLUMN hq_root VARCHAR(32) DEFAULT 'HQ' AFTER chapter_id,
    ADD COLUMN hq_path VARCHAR(512) AFTER hq_root,
    ADD COLUMN lq_root VARCHAR(32) DEFAULT NULL AFTER hq_path,
    ADD COLUMN lq_path VARCHAR(512) AFTER lq_root;
```

- [ ] **Step 2: 更新 schema.sql（全量建表脚本）**

将上述变更合并到 `api-service/src/main/resources/db/schema.sql`，保持其为最新的完整建表脚本。

- [ ] **Step 3: 执行 migration**

```bash
# 手动在 MySQL 执行 V2__core_architecture.sql
# 或通过应用的 flyway/liquibase 自动执行
```

- [ ] **Step 4: 验证表结构**

```sql
DESC catalog;
DESC chapter;
DESC page;
SHOW CREATE TABLE comic;
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: DB Schema v1.2 - catalog表 + chapter/page ALTER + storage_policy重命名"
```

---

### Task 1.3: 更新 Entity 类

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/entity/Comic.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/entity/Chapter.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/entity/Page.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/entity/Catalog.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/entity/ImportTask.java`

- [ ] **Step 1: 更新 Comic.java**

```java
// 关键变更：storage_type → storage_policy
@TableName("comic")
public class Comic {
    // ... 其他字段不变
    private String sourceType;        // 保持 VARCHAR，映射 SourceType
    private String storagePolicy;     // 原 storageType，MANAGED / EXTERNAL
    // ... rootKey, relativePath, status 不变
}
```

- [ ] **Step 2: 更新 Chapter.java**

```java
@Data
@TableName("chapter")
public class Chapter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private Long catalogId;        // 新增：可为 null
    private String title;
    private String chapterNo;      // 原始编号，不参与排序
    private Integer pageCount;
    private Integer sortOrder;     // 新增：同级排序
    private Integer globalOrder;   // 新增：全书阅读顺序
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: 更新 Page.java**

```java
@Data
@TableName("page")
public class Page {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chapterId;
    private Integer pageNumber;
    // 删除 imageName
    private String hqRoot;     // 新增: HQ/LOCAL
    private String hqPath;     // 新增: 相对路径
    private String hqStatus;
    private String lqRoot;     // 新增
    private String lqPath;     // 新增
    private String lqStatus;
    private Long lqSize;
    private Integer width;
    private Integer height;
    private Long fileSize;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 4: 创建 Catalog.java**

```java
package com.comicatlas.api.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("catalog")
public class Catalog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private Long parentId;
    private String title;
    private Integer sortOrder;
    private String path;
    private Integer level;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 5: 更新 ImportTask.java**

```java
// 关键变更：status 字段从 String 保持为 VARCHAR
// 新增字段和方法按需调整
@Data
@TableName("import_task")
public class ImportTask {
    // ... 字段不变，但 status 语义变为 ImportTaskStatus 枚举对应的 VARCHAR 值
}
```

- [ ] **Step 6: 编译验证**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat: Entity 适配 v1.2 - Comic/Chapter/Page/Catalog/ImportTask 新增和修改字段"
```

---

## 里程碑 2：存储抽象层

### Task 2.1: StorageRef 值对象

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/storage/StorageRef.java`

- [ ] **Step 1: 创建 StorageRef**

```java
package com.comicatlas.worker.file.storage;

public record StorageRef(String rootKey, String relativePath) {

    public StorageRef {
        if (rootKey == null || rootKey.isBlank()) {
            throw new IllegalArgumentException("rootKey must not be blank");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add -A && git commit -m "feat: StorageRef 值对象 - rootKey + relativePath"
```

---

### Task 2.2: StorageService 接口与实现

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/storage/StorageService.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/storage/LocalStorageService.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/storage/StorageProperties.java`

- [ ] **Step 1: 创建 StorageProperties**

```java
package com.comicatlas.worker.file.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private Map<String, RootConfig> roots;

    @Data
    public static class RootConfig {
        private String type = "FILESYSTEM";
        private String path;
    }
}
```

- [ ] **Step 2: 创建 StorageService 接口**

```java
package com.comicatlas.worker.file.storage;

import java.nio.file.Path;

public interface StorageService {
    StorageRef store(Path source, String rootKey, String relativePath);
    Path resolve(StorageRef ref);
    boolean exists(StorageRef ref);
    void delete(StorageRef ref);
}
```

- [ ] **Step 3: 创建 LocalStorageService**

```java
package com.comicatlas.worker.file.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private final StorageProperties properties;

    @Override
    public StorageRef store(Path source, String rootKey, String relativePath) {
        var root = properties.getRoots().get(rootKey);
        if (root == null) throw new IllegalArgumentException("未知存储根: " + rootKey);
        Path target = Path.of(root.getPath(), relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("store: {} -> {}", source, target);
        } catch (IOException e) {
            throw new RuntimeException("文件存储失败: " + target, e);
        }
        return new StorageRef(rootKey, relativePath);
    }

    @Override
    public Path resolve(StorageRef ref) {
        var root = properties.getRoots().get(ref.rootKey());
        if (root == null) throw new IllegalArgumentException("未知存储根: " + ref.rootKey());
        return Path.of(root.getPath(), ref.relativePath());
    }

    @Override
    public boolean exists(StorageRef ref) {
        return Files.exists(resolve(ref));
    }

    @Override
    public void delete(StorageRef ref) {
        try {
            Files.deleteIfExists(resolve(ref));
        } catch (IOException e) {
            log.warn("文件删除失败: {}", ref, e);
        }
    }
}
```

- [ ] **Step 4: 更新 worker-service application.yml**

```yaml
storage:
  roots:
    HQ:
      type: FILESYSTEM
      path: ${MANGA_ROOT:D:/manga}/hq
    LQ:
      type: FILESYSTEM
      path: ${MANGA_ROOT:D:/manga}/lq
    LOCAL:
      type: FILESYSTEM
      path: ${LOCAL_ROOT:F:/games/comics}
```

- [ ] **Step 5: 编译验证**

```bash
cd worker-service && mvn compile -q
```

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: StorageService 存储抽象 - StorageRef + LocalStorageService + StorageProperties"
```

---

### Task 2.3: FileUrlResolver + StorageLayout

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/common/storage/StorageRef.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/storage/FileUrlResolver.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/storage/StorageLayout.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/storage/DefaultStorageLayout.java`

- [ ] **Step 1: 创建 api-service 侧 StorageRef（同上，两边各一份）**

```java
package com.comicatlas.api.common.storage;

public record StorageRef(String rootKey, String relativePath) {}
```

- [ ] **Step 2: 创建 FileUrlResolver**

```java
package com.comicatlas.api.common.storage;

import com.comicatlas.api.comic.entity.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileUrlResolver {

    @Value("${storage.url-prefix:/comic/files}")
    private String urlPrefix;

    public String resolve(Page page) {
        if (page.getHqRoot() == null || page.getHqPath() == null) return null;
        return urlPrefix + "/" + page.getHqRoot().toLowerCase()
            + "/" + page.getHqPath();
    }

    public String resolveLq(Page page) {
        if (page.getLqRoot() == null || page.getLqPath() == null) return null;
        return urlPrefix + "/" + page.getLqRoot().toLowerCase()
            + "/" + page.getLqPath();
    }
}
```

- [ ] **Step 3: 创建 StorageLayout 接口**

```java
package com.comicatlas.api.common.storage;

@FunctionalInterface
public interface StorageLayout {
    String forPage(Long comicId, Long chapterId, String imageName);
}
```

- [ ] **Step 4: 创建 DefaultStorageLayout**

```java
package com.comicatlas.api.common.storage;

import org.springframework.stereotype.Component;

@Component
public class DefaultStorageLayout implements StorageLayout {
    @Override
    public String forPage(Long comicId, Long chapterId, String imageName) {
        return comicId + "/" + chapterId + "/" + imageName;
    }
}
```

- [ ] **Step 5: 编译验证**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: FileUrlResolver + StorageLayout - URL 解析与托管文件路径管理"
```

---

## 里程碑 3：导入流水线重构

### Task 3.1: DirectoryTree 数据类

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/parse/DirectoryTree.java`

- [ ] **Step 1: 创建 DirectoryTree**

```java
package com.comicatlas.worker.file.parse;

import java.nio.file.Path;
import java.util.List;

/**
 * 纯目录结构，不包含业务语义（Catalog/Chapter）。
 * Parser 输出此对象，MetadataAssembler 负责转换为 ComicMetadata。
 */
public record DirectoryTree(
    Path path,
    String name,
    List<Path> imageFiles,           // 当前目录下的图片文件（叶子节点有值）
    List<DirectoryTree> children     // 子目录
) {
    public boolean isLeaf() {
        return !imageFiles.isEmpty();
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add -A && git commit -m "feat: DirectoryTree - 纯目录结构，无业务语义"
```

---

### Task 3.2: DirectoryParser 重构 — 输出 DirectoryTree

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/parse/DirectoryParser.java`

- [ ] **Step 1: 重构 DirectoryParser**

```java
package com.comicatlas.worker.file.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
public class DirectoryParser {

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    /**
     * 解析目录为 DirectoryTree。只关心目录结构和图片列表，
     * 不注入 Catalog/Chapter 等业务语义。
     */
    public DirectoryTree parse(Path entryDir) {
        if (!Files.exists(entryDir) || !Files.isDirectory(entryDir)) {
            throw new IllegalArgumentException("目录不存在: " + entryDir);
        }
        Path root = findComicRoot(entryDir);
        if (root == null) throw new RuntimeException("目录中没有图片: " + entryDir);
        return buildTree(root);
    }

    /** 沿目录树向下，找到第一个含图片或含图片子目录的层级 */
    public Path findComicRoot(Path dir) {
        if (!Files.exists(dir)) return null;
        if (hasImages(dir)) return dir;
        List<Path> subs = listSubDirs(dir);
        if (subs.isEmpty()) return null;
        if (subs.stream().anyMatch(this::hasImages)) return dir;
        for (Path sub : subs) {
            Path deeper = findComicRoot(sub);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private DirectoryTree buildTree(Path dir) {
        List<Path> images = listImages(dir);
        List<DirectoryTree> children = new ArrayList<>();
        for (Path sub : listSubDirs(dir)) {
            children.add(buildTree(sub));
        }
        children.sort(Comparator.comparing(c -> c.name(), String.CASE_INSENSITIVE_ORDER));
        return new DirectoryTree(dir, dir.getFileName().toString(), images, children);
    }

    // ---- helpers (保持与之前一致) ----
    private List<Path> listImages(Path dir) {
        List<Path> r = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path f : s) {
                if (IMAGE_EXT.stream().anyMatch(e -> f.getFileName().toString().toLowerCase().endsWith(e))) {
                    r.add(f);
                }
            }
        } catch (Exception e) { log.warn("read: {}", dir); }
        r.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return r;
    }

    private List<Path> listSubDirs(Path dir) {
        List<Path> r = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, Files::isDirectory)) {
            s.forEach(r::add);
        } catch (Exception e) {}
        r.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return r;
    }

    private boolean hasImages(Path dir) { return !listImages(dir).isEmpty(); }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd worker-service && mvn compile -q
```

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "refactor: DirectoryParser 输出 DirectoryTree - 纯目录解析，无业务语义"
```

---

### Task 3.3: MetadataAssembler — DirectoryTree → ComicMetadata

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/parse/MetadataAssembler.java`

- [ ] **Step 1: 创建 MetadataAssembler**

```java
package com.comicatlas.worker.file.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将 DirectoryTree 转换为具有业务语义的 ComicMetadata。
 * 注入 Catalog/Chapter 区分、global_order、HQ/LQ 状态等。
 */
@Slf4j
@Component
public class MetadataAssembler {

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    public ComicMetadata assemble(DirectoryTree tree, String rootKey) {
        String title = tree.name();
        List<ComicMetadata.CatalogInfo> catalogs = new ArrayList<>();
        List<ComicMetadata.ChapterInfo> chapters = new ArrayList<>();
        AtomicInteger globalOrder = new AtomicInteger(0);

        processNode(tree, null, 0, catalogs, chapters, globalOrder, rootKey);

        if (chapters.isEmpty()) throw new RuntimeException("无可用章节: " + tree.path());
        return new ComicMetadata(title, null, null, List.of(), catalogs, chapters, null, null, null);
    }

    private void processNode(DirectoryTree node, Long parentCatalogId, int sortOrder,
            List<ComicMetadata.CatalogInfo> catalogs,
            List<ComicMetadata.ChapterInfo> chapters,
            AtomicInteger globalOrder, String rootKey) {

        if (node.isLeaf()) {
            // 含图片 → Chapter
            var pages = scanPages(node, rootKey);
            if (!pages.isEmpty()) {
                chapters.add(new ComicMetadata.ChapterInfo(
                    node.name(), String.valueOf(sortOrder + 1),
                    sortOrder, globalOrder.getAndIncrement(),
                    parentCatalogId, pages
                ));
            }
        } else if (node.hasChildren()) {
            // 只含子目录 → Catalog（递归）
            int catSort = sortOrder;
            var catInfo = new ComicMetadata.CatalogInfo(node.name(), catSort, new ArrayList<>());
            catalogs.add(catInfo);
            for (int i = 0; i < node.children().size(); i++) {
                processNode(node.children().get(i), null, i, catalogs, chapters, globalOrder, rootKey);
            }
        }
    }

    private List<ComicMetadata.PageInfo> scanPages(DirectoryTree node, String rootKey) {
        List<ComicMetadata.PageInfo> pages = new ArrayList<>();
        Path dir = node.path();
        Path lqDir = resolveLqDir(dir, rootKey);

        for (int i = 0; i < node.imageFiles().size(); i++) {
            Path img = node.imageFiles().get(i);
            String name = img.getFileName().toString();
            long size = safeFileSize(img);
            String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            boolean lqExists = lqDir != null && Files.exists(lqDir.resolve(baseName + ".webp"));

            // EXTERNAL 模式下 relativePath 相对于漫画根
            String relativePath = name; // 简化：单层目录用文件名
            pages.add(new ComicMetadata.PageInfo(
                name, i + 1,
                size > 0 ? "READY" : "MISSING",
                lqExists ? "READY" : "PENDING",
                size, relativePath,
                safeImageWidth(img), safeImageHeight(img)
            ));
        }
        return pages;
    }

    private Path resolveLqDir(Path hqDir, String rootKey) {
        if (rootKey == null || !rootKey.equals("LOCAL")) return null;
        String s = hqDir.toString().replace('\\', '/');
        s = s.replaceFirst("/h_photograph/", "/l_photograph/");
        Path lqDir = Path.of(s);
        return Files.exists(lqDir) ? lqDir : null;
    }

    // ---- helpers ----
    private long safeFileSize(Path p) { try { return Files.size(p); } catch (Exception e) { return 0; } }
    private Integer safeImageWidth(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getWidth() : null; } catch (Exception e) { return null; } }
    private Integer safeImageHeight(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getHeight() : null; } catch (Exception e) { return null; } }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd worker-service && mvn compile -q
```

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "feat: MetadataAssembler - DirectoryTree 转 ComicMetadata，注入 Catalog/Chapter 语义"
```

---

### Task 3.4: 更新 ComicMetadata（适配新 PageInfo）

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/parse/ComicMetadata.java`

- [ ] **Step 1: 更新 ComicMetadata**

```java
package com.comicatlas.worker.file.parse;

import java.util.List;

public record ComicMetadata(
    String title,
    String author,
    String category,
    List<String> tags,
    List<CatalogInfo> catalogs,     // 新增
    List<ChapterInfo> chapters,
    String storageType,
    String rootKey,
    String relativePath
) {
    public record CatalogInfo(
        String title,
        int sortOrder,
        List<CatalogInfo> children
    ) {}

    public record ChapterInfo(
        String title,
        String chapterNo,
        int sortOrder,
        int globalOrder,
        Long catalogId,             // 新增：关联 Catalog（临时 ID，非 DB ID）
        List<PageInfo> pages
    ) {}

    public record PageInfo(
        String imageName,
        int pageNumber,
        String hqStatus,
        String lqStatus,
        long fileSize,
        String relativePath,        // 新增：相对于 comic root 的文件路径
        Integer width,
        Integer height
    ) {}
}
```

- [ ] **Step 2: 编译验证**

```bash
cd worker-service && mvn compile -q
```

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "feat: ComicMetadata 适配 v1.2 - 新增 CatalogInfo/PageInfo.relativePath"
```

---

### Task 3.5: 更新 ImportTaskHandler — 串联新流水线

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/handler/ZipImportHandler.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/handler/DirectoryImportHandler.java`

- [ ] **Step 1: 更新 ImportTaskHandler 综合使用 Parser + Assembler**

```java
// 在 ImportTaskHandler 的 handle 方法中
DirectoryTree tree = directoryParser.parse(sourcePath);
ComicMetadata metadata = metadataAssembler.assemble(tree, importContext.rootKey());
// ... 后续写入 metadata.json 不变
```

- [ ] **Step 2: 更新 ZipImportHandler**

```java
// 解压后调用 directoryParser.parse() 替代旧的 parse()
// DirectoryImportHandler.importManaged() 使用 StorageService 搬文件
```

- [ ] **Step 3: 更新 DirectoryImportHandler**

```java
// importManaged(): 用 StorageService.store() 替代手动 Files.move()
// importExternal(): Parser 生成 relativePath，不动文件，写入 metadata 即可
```

- [ ] **Step 4: 编译验证**

```bash
cd worker-service && mvn compile -q
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: ImportPipeline 重构 - Parser+Assembler+StorageService 串联"
```

---

### Task 3.6: 更新 ImportEventHandler — 写入 catalog/chapter/page

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventHandler.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/mapper/CatalogMapper.java`

- [ ] **Step 1: 创建 CatalogMapper**

```java
package com.comicatlas.api.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.comic.entity.Catalog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CatalogMapper extends BaseMapper<Catalog> {
}
```

- [ ] **Step 2: 更新 ImportEventHandler.handleComicImported**

关键变更：
- 从 metadata 中读取 catalogs 列表，写入 `catalog` 表（DFS 序，回填 path）
- Chapter 写入时设置 `catalog_id` 和 `global_order`
- Page 写入时设置 `hq_root`/`hq_path`（替代 `image_name`）

```java
// 伪代码重点
// 1. 遍历 catalogs，INSERT catalog，记录 (tempId -> realId) 映射
// 2. 遍历 chapters，INSERT chapter（catalog_id=映射后的realId, global_order）
// 3. 遍历 pages，INSERT page（hq_root, hq_path）

// MANAGED 模式: hq_root="HQ", hq_path=StorageLayout.forPage(comicId, chapterId, imageName)
// EXTERNAL 模式: hq_root=comic.getRootKey(), hq_path=pageInfo.relativePath
```

- [ ] **Step 3: 编译验证**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "feat: ImportEventHandler 写入 catalog/chapter/page 适配 v1.2"
```

---

## 里程碑 4：阅读模型 + API + 前端

### Task 4.1: CatalogService — buildTree

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/comic/service/CatalogService.java`

- [ ] **Step 1: 创建 CatalogService**

```java
package com.comicatlas.api.comic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.dto.CatalogNode;
import com.comicatlas.api.comic.dto.ChapterRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;

    public List<CatalogNode> buildTree(Long comicId) {
        // 查所有 catalog
        var catalogs = catalogMapper.selectList(
            new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId).orderByAsc(Catalog::getSortOrder));
        // 查所有 chapter
        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId).orderByAsc(Chapter::getGlobalOrder));

        // catalog_id → CatalogNode 映射
        Map<Long, CatalogNode> nodeMap = new HashMap<>();
        for (Catalog cat : catalogs) {
            nodeMap.put(cat.getId(), new CatalogNode(cat.getId(), cat.getTitle(), new ArrayList<>(), new ArrayList<>()));
        }

        // 挂 Chapter 到对应 CatalogNode
        for (Chapter ch : chapters) {
            var ref = new ChapterRef(ch.getId(), ch.getChapterNo(), ch.getTitle(),
                ch.getGlobalOrder(), ch.getPageCount(), null);
            if (ch.getCatalogId() != null && nodeMap.containsKey(ch.getCatalogId())) {
                nodeMap.get(ch.getCatalogId()).chapters().add(ref);
            }
        }

        // 组装树（parent_id 关系）
        List<CatalogNode> roots = new ArrayList<>();
        for (Catalog cat : catalogs) {
            CatalogNode node = nodeMap.get(cat.getId());
            if (cat.getParentId() == null) {
                roots.add(node);
            } else if (nodeMap.containsKey(cat.getParentId())) {
                nodeMap.get(cat.getParentId()).children().add(node);
            }
        }

        // 同时挂直接属于 comic 的 Chapter（catalog_id = null）
        var directChapters = chapters.stream()
            .filter(ch -> ch.getCatalogId() == null)
            .map(ch -> new ChapterRef(ch.getId(), ch.getChapterNo(), ch.getTitle(),
                ch.getGlobalOrder(), ch.getPageCount(), null))
            .collect(Collectors.toList());

        // 如果只有直接 Chapter，不返回空 Catalog 根
        if (roots.isEmpty() && !directChapters.isEmpty()) {
            return List.of(new CatalogNode(null, null, List.of(), directChapters));
        }
        return roots;
    }
}
```

- [ ] **Step 2: 创建 DTO**

```java
// api-service/src/main/java/com/comicatlas/api/comic/dto/CatalogNode.java
package com.comicatlas.api.comic.dto;

import java.util.List;

public record CatalogNode(
    Long id,
    String title,
    List<CatalogNode> children,
    List<ChapterRef> chapters
) {}

// api-service/src/main/java/com/comicatlas/api/comic/dto/ChapterRef.java
package com.comicatlas.api.comic.dto;

public record ChapterRef(
    Long id,
    String chapterNo,
    String title,
    int globalOrder,
    int pageCount,
    String status   // UNREAD | READING | READ，预留
) {}
```

- [ ] **Step 3: 编译验证 + 提交**

```bash
cd api-service && mvn compile -q && git add -A && git commit -m "feat: CatalogService - buildTree 组装目录树 ViewModel"
```

---

### Task 4.2: ReaderService — 阅读序列 + prev/next

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/reader/service/ReaderService.java`

- [ ] **Step 1: 创建 ReaderService**

```java
package com.comicatlas.api.reader.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Page;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.PageMapper;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.reader.dto.ReaderDTO;
import com.comicatlas.api.reader.dto.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReaderService {

    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final FileUrlResolver fileUrlResolver;

    public ReaderDTO getChapter(Long chapterId) {
        Chapter ch = chapterMapper.selectById(chapterId);
        if (ch == null) throw new RuntimeException("章节不存在");

        var pages = pageMapper.selectList(
            new LambdaQueryWrapper<Page>().eq(Page::getChapterId, chapterId).orderByAsc(Page::getPageNumber));

        List<PageInfo> pageInfos = pages.stream().map(p -> {
            var pi = new PageInfo();
            pi.setId(p.getId());
            pi.setPageNumber(p.getPageNumber());
            pi.setHqUrl(fileUrlResolver.resolve(p));
            pi.setLqUrl(fileUrlResolver.resolveLq(p));
            pi.setLqStatus(p.getLqStatus());
            pi.setWidth(p.getWidth());
            pi.setHeight(p.getHeight());
            return pi;
        }).collect(Collectors.toList());

        // prev/next by global_order
        Long prevId = chapterMapper.selectOne(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, ch.getComicId())
                .lt(Chapter::getGlobalOrder, ch.getGlobalOrder())
                .orderByDesc(Chapter::getGlobalOrder)
                .last("LIMIT 1"))
            .getId();
        Long nextId = chapterMapper.selectOne(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, ch.getComicId())
                .gt(Chapter::getGlobalOrder, ch.getGlobalOrder())
                .orderByAsc(Chapter::getGlobalOrder)
                .last("LIMIT 1"))
            .getId();

        var dto = new ReaderDTO();
        dto.setChapterId(ch.getId());
        dto.setChapterTitle(ch.getTitle());
        dto.setPages(pageInfos);
        dto.setTotal(pageInfos.size());
        dto.setPrevChapterId(prevId);
        dto.setNextChapterId(nextId);
        return dto;
    }
}
```

- [ ] **Step 2: 创建 DTO**

```java
// api-service/src/main/java/com/comicatlas/api/reader/dto/ReaderDTO.java
package com.comicatlas.api.reader.dto;
// fields: chapterId, chapterTitle, pages, total, prevChapterId, nextChapterId

// api-service/src/main/java/com/comicatlas/api/reader/dto/PageInfo.java
package com.comicatlas.api.reader.dto;
// fields: id, pageNumber, hqUrl, lqUrl, lqStatus, width, height
```

- [ ] **Step 3: 编译验证 + 提交**

```bash
cd api-service && mvn compile -q && git add -A && git commit -m "feat: ReaderService - 章节阅读 + prev/next by global_order"
```

---

### Task 4.3: API Controller 层更新

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/controller/ComicController.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/controller/CatalogController.java`
- Create: `api-service/src/main/java/com/comicatlas/api/reader/controller/ReaderController.java`

- [ ] **Step 1: 拆分 ComicController — 详情不含 catalogTree**

```java
// ComicController: GET /api/comics/{id} 返回基本信息 + readingOrder（flat 列表）
// 不再返回 catalogTree
```

- [ ] **Step 2: 创建 CatalogController**

```java
// CatalogController: GET /api/comics/{id}/catalog 返回 catalogTree
```

- [ ] **Step 3: 创建 ReaderController**

```java
// ReaderController: GET /api/chapters/{id} 返回 ReaderDTO
// ReaderController: GET /api/pages/{id} 返回页面信息
```

- [ ] **Step 4: 更新 ComicServiceImpl**

```java
// 移除图像 URL 拼接逻辑（由 FileUrlResolver 负责）
// 详情接口不再返回 catalogTree
```

- [ ] **Step 5: 编译验证 + 提交**

```bash
cd api-service && mvn compile -q && git add -A && git commit -m "feat: API 拆分 - Comic/Catalog/Reader Controller 独立路由"
```

---

### Task 4.4: 前端适配

**Files:**
- Modify: `frontend/src/stores/reader-store.ts`
- Modify: `frontend/src/pages/ReaderPage.vue`
- Modify: `frontend/src/pages/ComicDetailPage.vue`
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/services/api.ts`

- [ ] **Step 1: 更新 API 服务**

```typescript
// api.ts: 新增 catalogApi, readerApi
export const catalogApi = {
  tree: (comicId: number) => api.get(`/comics/${comicId}/catalog`),
}
export const readerApi = {
  chapter: (chapterId: number) => api.get(`/chapters/${chapterId}`),
}
```

- [ ] **Step 2: 更新类型定义**

```typescript
// types/index.ts: 新增 CatalogNode, ChapterRef, ReaderDTO
export interface CatalogNode {
  id: number | null
  title: string | null
  children: CatalogNode[]
  chapters: ChapterRef[]
}
export interface ChapterRef {
  id: number; chapterNo: string; title: string
  globalOrder: number; pageCount: number; status?: string
}
export interface ReaderDTO {
  chapterId: number; chapterTitle: string
  pages: PageInfo[]; total: number
  prevChapterId: number | null; nextChapterId: number | null
}
```

- [ ] **Step 3: 更新 ReaderStore**

```typescript
// reader-store.ts: 适配新的 /api/chapters/{id} 返回格式
// 不再需要额外请求 prev/next
```

- [ ] **Step 4: 更新 ReaderPage**

```vue
<!-- ReaderPage.vue: 适配 ReaderDTO，prev/next 由后端直接返回 -->
```

- [ ] **Step 5: 更新 ComicDetailPage**

```vue
<!-- ComicDetailPage.vue: 拆分详情和目录请求 -->
<!-- GET /api/comics/{id} → 基本信息 -->
<!-- GET /api/comics/{id}/catalog → catalogTree 渲染 -->
```

- [ ] **Step 6: 编译验证 + 提交**

```bash
cd frontend && npm run build && cd .. && git add -A && git commit -m "feat: 前端适配 v1.2 - Catalog 树 + Reader API 拆分"
```

---

### Task 4.5: 清理旧代码

**Files:**
- Remove: `api-service` + `worker-service` 中 `image_name` 的引用
- Remove: `ComicServiceImpl` 中手动拼 URL 的逻辑
- Remove: 旧 `storage_type` 引用（替换为 `storage_policy`）

- [ ] **Step 1: grep 清理旧字段引用**

```bash
grep -r "image_name" api-service/src/ worker-service/src/ --include="*.java"
grep -r "storage_type" api-service/src/ worker-service/src/ --include="*.java"
grep -r "chapterNo" api-service/src/ --include="*.java"
```

- [ ] **Step 2: 逐一修复编译错误**

```
编译 → 找到错误 → 修复 → 重复直到编译通过
```

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "chore: 清理旧字段引用 - image_name/storage_type → hq_path/storage_policy"
```

---

### Task 4.6: 全量集成测试

- [ ] **Step 1: ZIP 导入测试**

```bash
# Postman POST /api/tasks/import { "sourceType": "ZIP", "sourcePath": "D:/test/comic.zip" }
# 验证: comic.storage_policy=MANAGED, chapter.global_order 正确, page.hq_root=HQ
```

- [ ] **Step 2: REGISTER 导入测试**

```bash
# Postman POST /api/tasks/import { "sourceType": "REGISTER", "sourcePath": "F:/games/comics/h_photograph/Test" }
# 验证: comic.storage_policy=EXTERNAL, catalog 表有记录, page.hq_root=LOCAL
```

- [ ] **Step 3: 阅读器测试**

```bash
# GET /api/chapters/{id} → prevChapterId/nextChapterId 正确
# GET /api/comics/{id}/catalog → catalogTree 正确
```

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "test: 全量集成测试通过 - ZIP/REGISTER 导入 + Catalog + Reader API"
```

---

## 完成标准

- [ ] 数据库：catalog 表存在，chapter/page/comic 字段正确
- [ ] ZIP 导入：文件搬迁到 `D:/manga/hq/{comicId}/{chapterId}/`，metadata 写入 DB
- [ ] REGISTER 导入：文件不动，page.hq_root=LOCAL，catalog 树正确
- [ ] 漫画详情：拆分为 `/api/comics/{id}` + `/api/comics/{id}/catalog`
- [ ] 阅读器：`/api/chapters/{id}` 返回 pages + prevChapterId + nextChapterId
- [ ] LQ 不自动生成（MANAGED + EXTERNAL 均手动触发）
- [ ] 枚举体系：Java enum + DB VARCHAR，无 MySQL ENUM
- [ ] 无编译错误，无 `image_name` / `storage_type` 旧字段残留
