# 阿里 Java 规范复审（rerun-final）整改实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 rerun-final 复审全部 5 项阻断项（2×P1 中断语义 + Worker 只读、3×P2 命名/枚举/日志），保持全绿。

**Architecture:** 分 4 批独立交付：P1-A 中断语义修复（worker 3 文件）；P1-B Worker 只读三层加固（生产配置 + Mapper 能力层 + 配置契约测试）；P2 命名与枚举收敛（api）；P2-E 日志脱敏。每批独立编译 + 测试 + 提交，最终全量 `clean verify`。

**Tech Stack:** Spring Boot 3、MyBatis Plus、Java 21、JUnit 5 + Testcontainers、Maven Wrapper、Checkstyle。

## Global Constraints

- 语言：对话、注释、提交信息始终使用中文；提交信息格式"动作 + 内容"。
- DB：保持 VARCHAR 存枚举 `name()`，零迁移；禁止 MySQL ENUM。
- 前端契约不变：VO/DTO 的 `status` 字段保持 `String`（输出 `.name()`），禁止改变 HTTP/JSON 形状。
- 禁止 `as any`/`@ts-ignore` 类 Java 等价物；禁止空 catch。
- 中断语义：`InterruptedException` 必须恢复中断标志或向上抛出，禁止宽泛 `catch (Exception)` 吞掉。
- Worker 只读：生产配置 `spring.datasource.hikari.read-only=true` 且默认账号非 root；6 个 `Export*Mapper` 接口不暴露 DML。
- Maven 命令在 PowerShell 下 `-D` 参数需引号包裹：`".\mvnw -pl api-service -am test \"-Dtest=X\""`。
- 提交前 `git diff --check` 通过；每次提交只解决一个完整问题。
- 行内代码中 `<b>` 标签等 HTML 仅用于文档高亮，不写入代码。

---

### Task 1: P1-A 中断语义修复（worker-service 3 文件）

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/transcode/VideoNormalizer.java`（`transcode()` L253-257、`transcodeToTemp()` L150）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/image/ImageOptimizer.java`（`runOptimizer()` L134-138）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/parse/MediaAnalyzer.java`（`analyzeVideo()` L131-135）

**Interfaces:**
- Consumes: 无（纯内部方法重构）
- Produces: 三处 `readFuture.get(...)` 的 `catch (Exception)` 拆分为 `InterruptedException` 分支（恢复中断 + `process.destroyForcibly()` + 重抛或等价传播）与 `ExecutionException | TimeoutException` 分支（仅告警）

- [ ] **Step 1: 读取三个目标文件当前代码，确认行号**

```bash
Get-Content worker-service/src/main/java/com/comicatlas/worker/file/transcode/VideoNormalizer.java | Select-Object -Skip 245 -First 30
```

- [ ] **Step 2: 修复 `VideoNormalizer.transcode()` 的 `readFuture.get` catch**

将（L253-257）：
```java
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待 ffmpeg 输出读取超时: {}", e.getMessage());
        }
```
替换为：
```java
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw e;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待 ffmpeg 输出读取超时: {}", e.getMessage());
        }
```
确认 `transcode()` 已 `throws Exception`（是，L215 签名），`InterruptedException` 可向上传播。

- [ ] **Step 3: 修复 `VideoNormalizer.transcodeToTemp()` 的兜底 catch**

将（L150-153）：
```java
        } catch (Exception e) {
            log.error("视频标准化失败: {} — {}", file.getFileName(), e.getMessage());
            failed.incrementAndGet();
        }
```
替换为：
```java
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;   // 中断向上传播，normalize() 的 future.get() 已正确处理
        } catch (Exception e) {
            log.error("视频标准化失败: {} — {}", file.getFileName(), e.getMessage());
            failed.incrementAndGet();
        }
```

- [ ] **Step 4: 修复 `ImageOptimizer.runOptimizer()` 的 `readFuture.get` catch**

将（L134-138）：
```java
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待图片优化输出读取超时: {}", e.getMessage());
        }
```
替换为：
```java
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new RuntimeException("等待图片优化输出被中断: comicId=" + comicId + ", chapterId=" + chapterId, e);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待图片优化输出读取超时: {}", e.getMessage());
        }
```
注意 `runOptimizer` 签名不 `throws InterruptedException`（包装为 RuntimeException 可接受），`process.destroyForcibly()` 已终止 Go 工具。

- [ ] **Step 5: 修复 `MediaAnalyzer.analyzeVideo()` 的 `readFuture.get` catch**

将（L131-135）：
```java
            try {
                readFuture.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("等待 ffprobe 输出读取超时: {}", e.getMessage());
            }
```
替换为：
```java
            try {
                readFuture.get(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return videoFallback(name, ext, size, "interrupted");
            } catch (ExecutionException | TimeoutException e) {
                log.warn("等待 ffprobe 输出读取超时: {}", e.getMessage());
            }
```
`analyzeVideo` 返回 `MediaInfo`，中断时返回 fallback（不吞中断——标志已恢复）。

- [ ] **Step 6: 编译并跑 Worker 测试**

```bash
".\mvnw -pl worker-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
```
若编译报 `ExecutionException`/`TimeoutException` 未导入，确认 `java.util.concurrent.*` 已导入（`import java.util.concurrent.TimeUnit;` 已存在，补 `ExecutionException`、`TimeoutException` 导入）。

- [ ] **Step 7: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/file/transcode/VideoNormalizer.java worker-service/src/main/java/com/comicatlas/worker/image/ImageOptimizer.java worker-service/src/main/java/com/comicatlas/worker/file/parse/MediaAnalyzer.java
git commit -m "修复中断宽泛捕获：恢复中断标志并强制终止外部子进程"
```

---

### Task 2: P1-B1 Worker 生产配置只读 + 配置契约测试

**Files:**
- Modify: `worker-service/src/main/resources/application.yml`（datasource 段 L34-40）
- Create: `worker-service/src/test/java/com/comicatlas/worker/config/WorkerDataSourceProductionConfigTest.java`
- Modify: `README.md`、`.env.example`、`docs/operations/management.md`（只读账号说明）

**Interfaces:**
- Consumes: 无
- Produces: 生产配置 `spring.datasource.username` 默认值非 root（`comicatlas_ro`）、`spring.datasource.hikari.read-only: true`；配置契约测试断言这两点，防止默认配置回退为高权限可写。

- [ ] **Step 1: 修改 `worker-service/src/main/resources/application.yml` datasource 段**

将（L34-40）：
```yaml
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:3306/comic_atlas?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASS:root}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 2
```
替换为：
```yaml
  datasource:
    # Worker 只读：默认使用独立只读账号（仅 GRANT SELECT），应用层强制 HikariCP read-only
    url: jdbc:mysql://${MYSQL_HOST:localhost}:3306/comic_atlas?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USER:comicatlas_ro}
    password: ${MYSQL_PASS:comicatlas_ro_pass}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      read-only: true
      maximum-pool-size: 2
```

- [ ] **Step 2: 编写配置契约测试**

创建 `worker-service/src/test/java/com/comicatlas/worker/config/WorkerDataSourceProductionConfigTest.java`：

```java
package com.comicatlas.worker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产配置契约测试：防止 Worker 数据源默认配置回退为高权限可写。
 * 直接加载 src/main/resources/application.yml，验证只读边界在生产默认配置中成立。
 */
@DisplayName("WorkerDataSourceProductionConfigTest — 生产默认数据源只读契约")
class WorkerDataSourceProductionConfigTest {

    @Test
    @DisplayName("生产默认 hikari.read-only 应为 true")
    void productionDatasourceIsReadOnly() throws IOException {
        String readOnly = resolve("spring.datasource.hikari.read-only");
        assertThat(readOnly).as("Worker 生产配置必须启用 HikariCP read-only").isEqualTo("true");
    }

    @Test
    @DisplayName("生产默认账号不应为 root")
    void productionDefaultUsernameIsNotRoot() throws IOException {
        String username = resolve("spring.datasource.username");
        assertThat(username).as("Worker 生产默认账号必须为独立只读账号").isNotBlank();
        assertThat(username.toLowerCase()).doesNotContain("root");
    }

    private String resolve(String key) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        for (PropertySource<?> ps : sources) {
            Object v = ps.getProperty(key);
            if (v != null) { return v.toString(); }
        }
        return null;
    }
}
```
注：YAML 中 `${MYSQL_USER:comicatlas_ro}` 原始值含 `comicatlas_ro` 不含 `root`（占位符默认值文本即断言对象）。若断言因占位符文本含 `root` 失败，改为断言 `contains("comicatlas_ro")`。

- [ ] **Step 3: 运行契约测试**

```bash
".\mvnw -pl worker-service -am test \"-Dtest=WorkerDataSourceProductionConfigTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS，2 测试通过。

- [ ] **Step 4: 同步文档**

- `README.md` `.env` 示例（若列出 `REMOTE_MYSQL_USER`）加注：Worker 需独立只读账号（GRANT SELECT）。
- `docs/operations/management.md`：新增"数据库账号"小节或补充 Worker 只读账号创建 SQL：
  ```sql
  CREATE USER 'comicatlas_ro'@'%' IDENTIFIED BY '<强密码>';
  GRANT SELECT ON comic_atlas.* TO 'comicatlas_ro'@'%';
  ```

- [ ] **Step 5: 提交**

```bash
git add worker-service/src/main/resources/application.yml worker-service/src/test/java/com/comicatlas/worker/config/WorkerDataSourceProductionConfigTest.java README.md docs/operations/management.md .env.example 2>/dev/null
git commit -m "Worker 生产配置启用只读账号与 Hikari read-only，新增配置契约测试"
```
若 `.env.example` 不存在，从 git add 中移除该路径。

---

### Task 3: P1-B2 Worker Mapper 移除 BaseMapper DML 能力

**Files:**
- Modify: 6 个 `worker-service/src/main/java/com/comicatlas/worker/mapper/Export*Mapper.java`（Comic/Chapter/Catalog/Media/UploadSession/UploadFile）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/export/ExportCollector.java`（`catalogMapper.selectList(wrapper)` → 显式方法）

**Interfaces:**
- Consumes: Task 2 生产配置只读（本任务接口层加固）
- Produces: 6 个只读查询接口，仅暴露 SELECT 方法。调用方保持 `selectById(...)`、`selectByComicId(...)`、`selectByChapterId(...)`、`selectBySessionId(...)`、`selectByComicIdOrderByGlobalOrder(...)` 签名不变。

- [ ] **Step 1: 收集 6 个 Mapper 被调用方实际使用的方法**

已确认（grep 全量）：
- `ExportComicMapper`：`selectById`（ExportCollector）
- `ExportChapterMapper`：`selectByComicIdOrderByGlobalOrder`（ExportCollector，已有 @Select）
- `ExportCatalogMapper`：`selectList(wrapper)`（ExportCollector）→ 改为显式 `selectByComicId`
- `ExportMediaMapper`：`selectByComicId`/`selectByChapterId`（已有）+ `selectById`（MediaUploadCommandHandler、TranscodeCommandHandler）
- `ExportUploadSessionMapper`：`selectById`（MediaUploadCommandHandler）
- `ExportUploadFileMapper`：`selectBySessionId`（已有）

- [ ] **Step 2: 改造 `ExportComicMapper`**

将：
```java
@Mapper
public interface ExportComicMapper extends BaseMapper<ExportComic> {
}
```
替换为：
```java
@Mapper
public interface ExportComicMapper {

    @Select("SELECT id, title, author, category, status, cover_path FROM comic WHERE id = #{id}")
    ExportComic selectById(Long id);
}
```
删除 `import com.baomidou.mybatisplus.core.mapper.BaseMapper;`，添加 `import org.apache.ibatis.annotations.Select;`。确认 `ExportComic` 字段（id/title/author/category/status/coverPath）与列名映射（map-underscore-to-camel-case）。

- [ ] **Step 3: 改造 `ExportChapterMapper`**

将接口声明 `extends BaseMapper<ExportChapter>` 删除（保留已有 `@Select` 方法），删除 BaseMapper import：
```java
@Mapper
public interface ExportChapterMapper {

    @Select("SELECT id, comic_id, catalog_id, title, chapter_no, global_order FROM chapter WHERE comic_id = #{comicId} ORDER BY global_order ASC")
    List<ExportChapter> selectByComicIdOrderByGlobalOrder(Long comicId);
}
```

- [ ] **Step 4: 改造 `ExportCatalogMapper` + `ExportCollector` 调用**

`ExportCatalogMapper`：
```java
@Mapper
public interface ExportCatalogMapper {

    @Select("SELECT id, comic_id, parent_id, title, sort_order FROM catalog WHERE comic_id = #{comicId} ORDER BY sort_order ASC")
    List<ExportCatalog> selectByComicId(Long comicId);
}
```

`ExportCollector.collect()` 中将（L45-46）：
```java
        List<ExportCatalog> catalogs = catalogMapper.selectList(
                new LambdaQueryWrapper<ExportCatalog>().eq(ExportCatalog::getComicId, comicId));
```
替换为：
```java
        List<ExportCatalog> catalogs = catalogMapper.selectByComicId(comicId);
```
删除 `ExportCollector` 中不再使用的 `LambdaQueryWrapper` import（若他处仍用则保留）。

- [ ] **Step 5: 改造 `ExportMediaMapper`**

接口声明删除 `extends BaseMapper<ExportMedia>`，删除 BaseMapper import，补充 `selectById`：
```java
@Mapper
public interface ExportMediaMapper {

    @Select("""
        SELECT p.id, p.chapter_id, p.page_number, p.media_type,
               p.hq_root, p.hq_path, p.hq_status,
               p.lq_root, p.lq_path, p.lq_status,
               p.file_size, p.width, p.height,
               p.duration, p.container, p.video_codec, p.audio_codec
        FROM page p
        JOIN chapter ch ON p.chapter_id = ch.id
        WHERE ch.comic_id = #{comicId}
        ORDER BY ch.global_order ASC, p.page_number ASC
    """)
    List<ExportMedia> selectByComicId(Long comicId);

    @Select("""
        SELECT p.id, p.chapter_id, p.page_number, p.media_type,
               p.hq_root, p.hq_path, p.hq_status,
               p.lq_root, p.lq_path, p.lq_status,
               p.file_size, p.width, p.height,
               p.duration, p.container, p.video_codec, p.audio_codec
        FROM page p
        WHERE p.chapter_id = #{chapterId}
        ORDER BY p.page_number ASC
    """)
    List<ExportMedia> selectByChapterId(Long chapterId);

    @Select("""
        SELECT id, chapter_id, page_number, media_type,
               hq_root, hq_path, hq_status,
               lq_root, lq_path, lq_status,
               file_size, width, height,
               duration, container, video_codec, audio_codec
        FROM page
        WHERE id = #{id}
    """)
    ExportMedia selectById(Long id);
}
```

- [ ] **Step 6: 改造 `ExportUploadSessionMapper`**

```java
@Mapper
public interface ExportUploadSessionMapper {

    @Select("SELECT id, session_id, comic_id, chapter_id, replace_media_id, status FROM upload_session WHERE id = #{id}")
    ExportUploadSession selectById(Long id);
}
```
删除 BaseMapper import，添加 `import org.apache.ibatis.annotations.Select;`。

- [ ] **Step 7: 改造 `ExportUploadFileMapper`**

仅删除 `extends BaseMapper<ExportUploadFile>` 与 BaseMapper import，保留已有 `selectBySessionId`。

- [ ] **Step 8: 编译 + 全量 Worker 测试**

```bash
".\mvnw -pl worker-service -am test" ; if ($LASTEXITCODE -eq 0) { "WORKER_TESTS_OK" }
```
Expected: BUILD SUCCESS（`WorkerDatabasePermissionIT` 若 Docker 可用则运行，跳过也算通过）。

- [ ] **Step 9: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/mapper worker-service/src/main/java/com/comicatlas/worker/export/ExportCollector.java
git commit -m "Worker Mapper 移除 BaseMapper DML 能力，改为显式只读查询接口"
```

---

### Task 4: P2-C 语义命名残留 + 守卫补漏（api-service）

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/admin/recovery/RecoveryEngine.java`（L291、L310）
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventHandler.java`（L203、L223、L274）
- Modify: `api-service/src/test/java/com/comicatlas/api/config/SemanticNamingContractTest.java`（BANNED + DETECTION_FIXTURES）

**Interfaces:**
- Consumes: 无
- Produces: 生产代码零 `cd`/`md` 短名；守卫 `BANNED`/`DETECTION_FIXTURES` 同步新增两条规则。

- [ ] **Step 1: `RecoveryEngine` 两处 `cd` → `catalogData`**

L291、L310 `Map<String, Object> cd = catalogsData.get(i);` → `Map<String, Object> catalogData = catalogsData.get(i);`，并更新方法体内 `cd.get(...)` 引用。

- [ ] **Step 2: `ImportEventHandler` 两处 `cd` → `catalogData`**

`insertCatalogs()` L203、L223 `Map<String, Object> cd` → `catalogData`，更新方法体内引用。

- [ ] **Step 3: `ImportEventHandler` 一处 `md` → `mediaData`**

`insertChapter()` L274 `for (Map<String, Object> md : itemList)` → `mediaData`，更新循环体内 `md.get(...)` 引用。

- [ ] **Step 4: 守卫表同步新增 `cd`/`md`**

`SemanticNamingContractTest` 的 `BANNED` 与 `DETECTION_FIXTURES` 两处，在同一位置（`"Map<String, Object>" "chm"` 之后）各追加：
```java
            new BannedPattern("Map<String, Object>", "cd", "catalogData"),
            new BannedPattern("Map<String, Object>", "md", "mediaData"),
```
两表必须逐项一致（含顺序），否则 `containsExactlyElementsOf` 断言失败。

- [ ] **Step 5: 运行守卫测试 + 相关单测**

```bash
".\mvnw -pl api-service -am test \"-Dtest=SemanticNamingContractTest,RecoveryEventHandlerTest,ImportEventHandlerCacheTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS，全部通过。

- [ ] **Step 6: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/admin/recovery/RecoveryEngine.java api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventHandler.java api-service/src/test/java/com/comicatlas/api/config/SemanticNamingContractTest.java
git commit -m "清理 cd/md 语义短名残留，命名守卫补充 catalogData/mediaData 规则"
```

---

### Task 5: P2-D1 Chapter.status → ChapterLifecycleStatus

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/entity/Chapter.java`（L28）
- Modify: `api-service/src/main/java/com/comicatlas/api/management/trash/TrashLifecycleService.java`（L109-111、L118、L169-174、L220-224、L324-325、L358-359、L407）
- Modify: `api-service/src/main/java/com/comicatlas/api/management/event/ManagementCommandResultHandler.java`（L276-277、L331-332、L405-406、L624-625、L654-655）
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ChapterManagementServiceImpl.java`（L73、L181-182）
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java`（L192）
- Modify: `api-service/src/main/java/com/comicatlas/api/reader/service/impl/ReaderServiceImpl.java`（L41）
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/dto/ChapterVO.java`（L29）

**Interfaces:**
- Consumes: `com.comicatlas.common.enums.ChapterLifecycleStatus`（已存在，TypeHandler 已注册）
- Produces: `Chapter.getStatus()` 返回 `ChapterLifecycleStatus`；VO 层仍输出 `String`（`.name()`），前端契约不变。

- [ ] **Step 1: 修改 `Chapter` 实体**

L28 `private String status;` → `private ChapterLifecycleStatus status;`，import `com.comicatlas.common.enums.ChapterLifecycleStatus;`，删除 `/** 章节生命周期状态，默认 READY */` 注释中的误导表述。

- [ ] **Step 2: 适配 `TrashLifecycleService`**

- L109/169/220：`policyService.forChapter(chapter.getStatus())` → `chapter.getStatus() == null ? null : chapter.getStatus().name()`
- L111/171/222：`ManagementStateMachine.validateChapterTransition(chapter.getStatus(), "TRASHING")` → `chapter.getStatus() == null ? null : chapter.getStatus().name()`
- L118/174/224/325/359：`chapter.setStatus("TRASHING")` 等 → `chapter.setStatus(ChapterLifecycleStatus.TRASHING)` 等
- L324/358：`"TRASHING".equals(chapter.getStatus())` → `chapter.getStatus() == ChapterLifecycleStatus.TRASHING`
- L407：`yield chapter == null ? null : chapter.getStatus();` → `yield chapter == null || chapter.getStatus() == null ? null : chapter.getStatus().name();`

- [ ] **Step 3: 适配 `ManagementCommandResultHandler`**

- L276：`!"DELETED".equals(chapter.getStatus())` → `chapter.getStatus() != ChapterLifecycleStatus.DELETED`
- L277/625：`chapter.setStatus("TRASHED")` → `ChapterLifecycleStatus.TRASHED`
- L331/624：`"RESTORING".equals(chapter.getStatus())` → `chapter.getStatus() == ChapterLifecycleStatus.RESTORING`
- L332/655：`setStatus("READY")` → `ChapterLifecycleStatus.READY`
- L405：`"PURGING".equals(chapter.getStatus())` → `ChapterLifecycleStatus.PURGING`
- L406：`setStatus("DELETED")` → `ChapterLifecycleStatus.DELETED`
- L624：`"RESTORING".equals(...) || "PURGING".equals(...)` → `== RESTORING || == PURGING`
- L654：`"TRASHING".equals(chapter.getStatus())` → `== TRASHING`

- [ ] **Step 4: 适配 `ChapterManagementServiceImpl`**

- L73：`chapter.setStatus(ChapterLifecycleStatus.READY.name())` → `chapter.setStatus(ChapterLifecycleStatus.READY)`
- L181：`canTransitionChapter(chapter.getStatus(), "TRASHING")` → `chapter.getStatus() == null ? null : chapter.getStatus().name()`

- [ ] **Step 5: 适配 `ComicServiceImpl` 与 `ReaderServiceImpl`**

- `ComicServiceImpl` L192：`!ChapterLifecycleStatus.READY.name().equals(chapter.getStatus())` → `chapter.getStatus() != ChapterLifecycleStatus.READY`
- `ReaderServiceImpl` L41：同样改为 `chapter.getStatus() != ChapterLifecycleStatus.READY`

- [ ] **Step 6: 适配 `ChapterVO`**

L29：`vo.setStatus(chapter.getStatus())` → `vo.setStatus(chapter.getStatus() == null ? null : chapter.getStatus().name())`

- [ ] **Step 7: 全量编译 + 相关 IT**

```bash
".\mvnw -pl api-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl api-service -am test \"-Dtest=ReadingLifecycleCompatibilityIT,TrashLifecycleIT,CatalogChapterManagementIT\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS。若某测试编译失败，检查 `chapter.getStatus()` 调用点是否有遗漏（用 `git grep "getStatus()" api-service/src` 复查）。

- [ ] **Step 8: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/entity/Chapter.java api-service/src/main/java/com/comicatlas/api/management/trash/TrashLifecycleService.java api-service/src/main/java/com/comicatlas/api/management/event/ManagementCommandResultHandler.java api-service/src/main/java/com/comicatlas/api/comic/service/impl/ChapterManagementServiceImpl.java api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java api-service/src/main/java/com/comicatlas/api/reader/service/impl/ReaderServiceImpl.java api-service/src/main/java/com/comicatlas/api/comic/dto/ChapterVO.java
git commit -m "Chapter.status 迁移为 ChapterLifecycleStatus 枚举，VO 保持字符串输出"
```

---

### Task 6: P2-D2 UploadSession.status → UploadSessionStatus

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/upload/entity/UploadSession.java`（L24）
- Modify: `api-service/src/main/java/com/comicatlas/api/upload/UploadSessionService.java`（L118、L207、L282、L356、L415、L418、L424、L442）
- Modify: `api-service/src/main/java/com/comicatlas/api/upload/UploadStorageService.java`（L113-114）
- Modify: `api-service/src/main/java/com/comicatlas/api/common/handler/EnumTypeHandlers.java`（新增 Handler）

**Interfaces:**
- Consumes: `com.comicatlas.api.upload.UploadSessionStatus`（已存在枚举：ACTIVE/COMPLETED/CANCELLED/EXPIRED/FAILED）
- Produces: `UploadSession.getStatus()` 返回 `UploadSessionStatus`；`UploadSessionStatusResponse.status` 保持 String。

- [ ] **Step 1: 修改 `UploadSession` 实体**

L24 `private String status;` → `private UploadSessionStatus status;`，import `com.comicatlas.api.upload.UploadSessionStatus;`。

- [ ] **Step 2: 适配 `UploadSessionService`**

- L118：`session.setStatus(UploadSessionStatus.ACTIVE.name())` → `session.setStatus(UploadSessionStatus.ACTIVE)`
- L207：`resp.setStatus(session.getStatus())` → `session.getStatus() == null ? null : session.getStatus().name()`
- L282：`!UploadSessionStatus.ACTIVE.name().equals(session.getStatus())` → `session.getStatus() != UploadSessionStatus.ACTIVE`
- L356：`setStatus(UploadSessionStatus.COMPLETED.name())` → `setStatus(UploadSessionStatus.COMPLETED)`
- L415：`UploadSessionStatus.CANCELLED.name().equals(session.getStatus())` → `session.getStatus() == UploadSessionStatus.CANCELLED`
- L418：`UploadSessionStatus.COMPLETED.name().equals(...)` → `== COMPLETED`
- L424：`setStatus(UploadSessionStatus.CANCELLED.name())` → `setStatus(UploadSessionStatus.CANCELLED)`
- L442：`setStatus(UploadSessionStatus.EXPIRED.name())` → `setStatus(UploadSessionStatus.EXPIRED)`

- [ ] **Step 3: 适配 `UploadStorageService`**

L113：`!UploadSessionStatus.ACTIVE.name().equals(session.getStatus())` → `session.getStatus() != UploadSessionStatus.ACTIVE`

- [ ] **Step 4: `EnumTypeHandlers` 新增 Handler**

在 `UploadSessionStatus` import 后，`// ======================== api-service 枚举 ========================` 区块内新增：
```java
    @MappedTypes(UploadSessionStatus.class)
    public static class UploadSessionStatusHandler extends BaseTypeHandler<UploadSessionStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, UploadSessionStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public UploadSessionStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(UploadSessionStatus.class, rs.getString(c)); }
        @Override public UploadSessionStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(UploadSessionStatus.class, rs.getString(c)); }
        @Override public UploadSessionStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(UploadSessionStatus.class, cs.getString(c)); }
    }
```

- [ ] **Step 5: 编译 + 相关 IT**

```bash
".\mvnw -pl api-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl api-service -am test \"-Dtest=MediaUploadManagementIT,UnifiedTaskCompatibilityIT\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS。

- [ ] **Step 6: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/upload/entity/UploadSession.java api-service/src/main/java/com/comicatlas/api/upload/UploadSessionService.java api-service/src/main/java/com/comicatlas/api/upload/UploadStorageService.java api-service/src/main/java/com/comicatlas/api/common/handler/EnumTypeHandlers.java
git commit -m "UploadSession.status 迁移为 UploadSessionStatus 枚举并注册 TypeHandler"
```

---

### Task 7: P2-D3 ExportTask / RecoveryTask / DirectoryScanTask 状态枚举

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/common/enums/ExportTaskStatus.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/enums/RecoveryTaskStatus.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/enums/DirectoryScanTaskStatus.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/export/entity/ExportTask.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/entity/RecoveryTask.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/entity/DirectoryScanTask.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/export/service/impl/ExportServiceImpl.java`（L63、L123）
- Modify: `api-service/src/main/java/com/comicatlas/api/export/event/ExportStartedHandler.java`（L40）、`ExportFailedHandler.java`（L41）、`ExportCompletedHandler.java`（L43）
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/service/impl/RecoveryTaskServiceImpl.java`（L48、L99、L103、L139、L172）
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/service/impl/DirectoryScanTaskServiceImpl.java`（L46、L81、L101、L107、L114、L122）
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/event/RecoveryEventHandler.java`（L49、L92、L100、L159、L177、L178、L214、L221）
- Modify: `api-service/src/main/java/com/comicatlas/api/management/service/LegacyTaskBackfillService.java`（L87-88、L106-107、L125-126）
- Modify: `api-service/src/main/java/com/comicatlas/api/common/handler/EnumTypeHandlers.java`（3 个新 Handler）
- Modify: 测试 `RecoveryTaskServiceTest.java`、`RecoveryEventHandlerTest.java`、`UnifiedTaskCompatibilityIT.java`

**Interfaces:**
- Consumes: 三个新建枚举（见下）；`ManagementTaskStatus`（comic-common，已存在）
- Produces:
  - `ExportTaskStatus`：PENDING/RUNNING/SUCCESS/FAILED
  - `RecoveryTaskStatus`：QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED
  - `DirectoryScanTaskStatus`：PENDING/SUCCESS/FAILED
  - VO 层（`ExportTaskVO`/`RecoveryTaskVO`/`DirectoryScanTaskVO`）`status` 保持 String（`.name()` 输出）。

- [ ] **Step 1: 创建 3 个枚举**

`ExportTaskStatus.java`：
```java
package com.comicatlas.api.common.enums;

/** 导出任务状态。 */
public enum ExportTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}
```

`RecoveryTaskStatus.java`：
```java
package com.comicatlas.api.common.enums;

/** 存储恢复任务状态。终态：SUCCEEDED / FAILED / CANCELLED。 */
public enum RecoveryTaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
```

`DirectoryScanTaskStatus.java`：
```java
package com.comicatlas.api.common.enums;

/** 目录扫描任务状态。 */
public enum DirectoryScanTaskStatus {
    PENDING,
    SUCCESS,
    FAILED
}
```

- [ ] **Step 2: 修改 3 个实体字段类型**

- `ExportTask` L17：`private String status;` → `private ExportTaskStatus status;`；`isPending()/isRunning()/isSuccess()/isFailed()` 改为 `status == ExportTaskStatus.X`（方法签名保持 boolean 不变）
- `RecoveryTask` L16：`private String status;` → `private RecoveryTaskStatus status;`
- `DirectoryScanTask` L16：`private String status;` → `private DirectoryScanTaskStatus status;`

- [ ] **Step 3: 适配 Export 链路**

- `ExportServiceImpl` L63：`task.setStatus("PENDING")` → `ExportTaskStatus.PENDING`；L123 `vo.setStatus(task.getStatus())` → `task.getStatus() == null ? null : task.getStatus().name()`
- `ExportStartedHandler` L40：`setStatus("RUNNING")` → `ExportTaskStatus.RUNNING`
- `ExportFailedHandler` L41：`setStatus("FAILED")` → `ExportTaskStatus.FAILED`
- `ExportCompletedHandler` L43：`setStatus("SUCCESS")` → `ExportTaskStatus.SUCCESS`

- [ ] **Step 4: 适配 Recovery 链路**

- `RecoveryTaskServiceImpl` L48/103：`setStatus("QUEUED")` → `RecoveryTaskStatus.QUEUED`
- `RecoveryTaskServiceImpl` L99：`!"FAILED".equals(recoveryTask.getStatus())` → `recoveryTask.getStatus() != RecoveryTaskStatus.FAILED`
- `RecoveryTaskServiceImpl` L139：`recoveryTask.setStatus(vo.getStatus())` → `recoveryTask.setStatus(vo.getStatus() == null ? null : RecoveryTaskStatus.valueOf(vo.getStatus()))`（VO 为 String）
- `RecoveryTaskServiceImpl` L172：`vo.setStatus(recoveryTask.getStatus())` → `recoveryTask.getStatus() == null ? null : recoveryTask.getStatus().name()`
- `RecoveryEventHandler` L49：`private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");` → `private static final EnumSet<RecoveryTaskStatus> TERMINAL_STATUSES = EnumSet.of(RecoveryTaskStatus.SUCCEEDED, RecoveryTaskStatus.FAILED, RecoveryTaskStatus.CANCELLED);`
- `RecoveryEventHandler` L100：`setStatus("RUNNING")` → `RecoveryTaskStatus.RUNNING`；L159 `SUCCEEDED`；L178/L221 `FAILED`

- [ ] **Step 5: 适配 DirectoryScan 链路**

- `DirectoryScanTaskServiceImpl` L46：`setStatus("PENDING")` → `DirectoryScanTaskStatus.PENDING`
- L81：`vo.setStatus(task.getStatus())` → `task.getStatus() == null ? null : task.getStatus().name()`
- L101：`setStatus("SUCCESS")` → `DirectoryScanTaskStatus.SUCCESS`
- L107/L122：`setStatus("FAILED")` → `DirectoryScanTaskStatus.FAILED`
- L114：`ManagementTaskStatus status = "SUCCESS".equals(task.getStatus()) ? ... : ...;` → `ManagementTaskStatus status = task.getStatus() == DirectoryScanTaskStatus.SUCCESS ? ... : ...;`

- [ ] **Step 6: 适配 `LegacyTaskBackfillService`**

- L87-88（RecoveryTask）：`recoveryTask.getStatus()` 传入 `baseTask`/`baseItem`（参数为 String）→ `recoveryTask.getStatus() == null ? null : recoveryTask.getStatus().name()`
- L106-107（ExportTask）：`task.getStatus()` → `task.getStatus() == null ? null : task.getStatus().name()`
- L125-126（DirectoryScanTask）：同样处理

- [ ] **Step 7: `EnumTypeHandlers` 新增 3 个 Handler**

参照 Task 6 Step 4 模式，在 api-service 枚举区块新增 `ExportTaskStatusHandler`、`RecoveryTaskStatusHandler`、`DirectoryScanTaskStatusHandler`（`@MappedTypes` + 四个方法，`safeValueOf` 兜底）。

- [ ] **Step 8: 更新受影响测试**

- `RecoveryTaskServiceTest.java` L110/145/160/175：`failed.setStatus("FAILED")` → `RecoveryTaskStatus.FAILED`；`"SUCCESS"`→`SUCCESS`；`"RUNNING"`→`RUNNING`；`"PENDING"`→`PENDING`（注意 L110 的 `failed` 是变量名保留；L145 `success`、L160 `running`、L175 `pending`）
- `RecoveryEventHandlerTest.java` L83/121/144/166/207/235：`setStatus("...")` → `RecoveryTaskStatus.X`
- `UnifiedTaskCompatibilityIT.java` L351/356/360：`rt.setStatus("SUCCEEDED")` → `RecoveryTaskStatus.SUCCEEDED`；`et.setStatus("FAILED")` → `ExportTaskStatus.FAILED`；`st.setStatus("SUCCESS")` → `DirectoryScanTaskStatus.SUCCESS`

- [ ] **Step 9: 编译 + 相关测试**

```bash
".\mvnw -pl api-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl api-service -am test \"-Dtest=RecoveryTaskServiceTest,RecoveryEventHandlerTest,UnifiedTaskCompatibilityIT,ExportTaskStatusTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS。

- [ ] **Step 10: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/common/enums api-service/src/main/java/com/comicatlas/api/export api-service/src/main/java/com/comicatlas/api/importer api-service/src/main/java/com/comicatlas/api/management/service/LegacyTaskBackfillService.java api-service/src/main/java/com/comicatlas/api/common/handler/EnumTypeHandlers.java api-service/src/test/java/com/comicatlas/api/importer api-service/src/test/java/com/comicatlas/api/management/UnifiedTaskCompatibilityIT.java
git commit -m "Export/Recovery/DirectoryScan 任务状态迁移为枚举并注册 TypeHandler"
```
若 `ExportTaskStatusTest` 不存在，从测试命令与 git add 中移除。

---

### Task 8: P2-D4 ComicStatus / ComicLifecycleStatus 合并

**Files:**
- Delete: `comic-common/src/main/java/com/comicatlas/common/enums/ComicLifecycleStatus.java`
- Modify: `api-service/src/main/java/com/comicatlas/api/common/handler/EnumTypeHandlers.java`（移除 ComicLifecycleStatusHandler + import）
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java`（L490-497）
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicListQueryServiceImpl.java`（L161-168）
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicDetailVO.java`（L31）
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicListVO.java`（L24）

**Interfaces:**
- Consumes: `com.comicatlas.api.common.enums.ComicStatus`（84 处引用，含 REFRESHING）
- Produces: 全库唯一漫画状态枚举 `ComicStatus`；删除 `ComicLifecycleStatus`；VO `lifecycle` 字段类型改为 `ComicStatus`（JSON 输出仍是枚举名，前端契约不变）。

- [ ] **Step 1: 确认 `ComicStatus` 具备所需辅助方法**

`ComicStatus` 目前只有 `isTerminal()`。`ComicLifecycleStatus` 有 `isTerminal()/isReadable()/isTransient()`。检查全库是否使用 `ComicLifecycleStatus.isReadable()/isTransient()`：
```bash
git grep -n "isReadable()\|isTransient()" -- "*.java" | Select-String -NotMatch "ComicLifecycleStatus.java"
```
若被使用，需将这两个方法合并进 `ComicStatus`。若仅 `ComicLifecycleStatus` 自身定义而未被他处调用，则直接删除（YAGNI）。

- [ ] **Step 2: 删除 `ComicLifecycleStatus.java`**

```bash
git rm comic-common/src/main/java/com/comicatlas/common/enums/ComicLifecycleStatus.java
```

- [ ] **Step 3: `EnumTypeHandlers` 移除 ComicLifecycleStatus**

删除 `import com.comicatlas.common.enums.ComicLifecycleStatus;` 与 `ComicLifecycleStatusHandler` 类（L73-79），保留 `ComicStatusHandler`。

- [ ] **Step 4: 适配两个 `toLifecycle`**

- `ComicServiceImpl` L490-497：`private static ComicLifecycleStatus toLifecycle(String status)` → `private static ComicStatus toLifecycle(String status)`，内部 `ComicLifecycleStatus.valueOf(status)` → `ComicStatus.valueOf(status)`；若返回值被赋给 `ComicDetailVO.lifecycle`（ComicStatus 类型）则类型一致
- `ComicListQueryServiceImpl` L161-168：同样改为 `ComicStatus` + `ComicStatus.valueOf`

- [ ] **Step 5: 修改 VO 字段类型**

- `ComicDetailVO` L31：`private ComicLifecycleStatus lifecycle;` → `private ComicStatus lifecycle;`（import 改为 `com.comicatlas.api.common.enums.ComicStatus`）
- `ComicListVO` L24：同样改为 `ComicStatus`

- [ ] **Step 6: 全量编译 + 相关测试**

```bash
".\mvnw -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl api-service -am test \"-Dtest=ComicListQueryServiceTest,ComicDetailViewIT,CatalogCacheTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS。若 `toLifecycle` 有测试断言 `ComicLifecycleStatus`，同步改为 `ComicStatus`。

- [ ] **Step 7: 提交**

```bash
git add -u && git add api-service/src/main/java/com/comicatlas/api/common/handler/EnumTypeHandlers.java api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicListQueryServiceImpl.java api-service/src/main/java/com/comicatlas/api/comic/dto/ComicDetailVO.java api-service/src/main/java/com/comicatlas/api/comic/dto/ComicListVO.java
git commit -m "合并 ComicStatus 与 ComicLifecycleStatus 双枚举，统一为单一漫画状态"
```

---

### Task 9: P2-E 敏感下载凭据日志脱敏（worker-service）

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/download/ArchiveDownloader.java`（L41）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/download/TorrentDownloader.java`（L21）

**Interfaces:**
- Consumes: 无
- Produces: 日志不含完整 URL/magnet（只含 gid、btih 摘要、目标路径）

- [ ] **Step 1: 脱敏 `ArchiveDownloader` 日志**

将（L41）：
```java
        log.info("Archive download: {}", url);
```
替换为：
```java
        log.info("Archive download: gid={}, dest={}", gid, destFile);
```

- [ ] **Step 2: 脱敏 `TorrentDownloader` 日志**

将（L21）：
```java
        log.info("Torrent: magnet={}, dest={}", magnetUrl, destDir);
```
替换为：
```java
        log.info("Torrent: btih={}..., dest={}", summarizeMagnet(magnetUrl), destDir);
```
并在类内新增私有方法：
```java
    /** 提取 magnet URI 的 btih 哈希摘要（前 32 位）；缺失时降级为长度描述，不打印完整 URI。 */
    private String summarizeMagnet(String magnetUrl) {
        int idx = magnetUrl.indexOf("btih:");
        if (idx >= 0) {
            String hash = magnetUrl.substring(idx + 5);
            int end = hash.indexOf('&');
            if (end >= 0) { hash = hash.substring(0, end); }
            if (hash.length() > 32) { return hash.substring(0, 32); }
            return hash;
        }
        return "magnet?" + magnetUrl.length() + "chars";
    }
```

- [ ] **Step 3: 编译**

```bash
".\mvnw -pl worker-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
```

- [ ] **Step 4: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/file/download/ArchiveDownloader.java worker-service/src/main/java/com/comicatlas/worker/file/download/TorrentDownloader.java
git commit -m "下载日志脱敏：不记录完整下载 URL 与 magnet URI"
```

---

### Task 10: 最终全量验证

**Files:**
- 无代码改动（仅验证）

**Interfaces:**
- Consumes: Task 1-9 全部
- Produces: 全绿验证结论

- [ ] **Step 1: 全量 `clean verify`**

```bash
".\mvnw clean verify -DskipTests=false" ; echo "EXIT=$LASTEXITCODE"
```
Expected: BUILD SUCCESS，五模块 Checkstyle 0 violations，API 272+ + Worker 26+ 全绿，0 failures/errors。

- [ ] **Step 2: 边界复查**

```bash
git grep -n "ComicLifecycleStatus\|extends BaseMapper" -- "*.java" | Select-String -NotMatch "test"
git diff --check
git log --oneline -12
```
Expected: 生产代码无 `ComicLifecycleStatus`、无 Worker `extends BaseMapper`；`git diff --check` 无输出。

- [ ] **Step 3: 更新文档（如需要）**

若 `.env`/README 示例有 Worker 数据源账号说明，核对与 Task 2 一致。

- [ ] **Step 4: 汇报**

汇总各任务 commit SHA、测试结论、Checkstyle 结果，报告用户。

---

## Self-Review

**1. Spec coverage:**
- P1-A 中断拆分 → Task 1 ✅
- P1-B 生产配置只读 → Task 2 ✅；Mapper DML 能力 → Task 3 ✅；测试补洞 → Task 2 配置契约测试 + 既有 WorkerDatabasePermissionIT ✅
- P2-C 命名残留 + 守卫 → Task 4 ✅
- P2-D Chapter → Task 5；UploadSession → Task 6；三任务枚举 → Task 7；Comic 合并 → Task 8 ✅
- P2-E 日志脱敏 → Task 9 ✅
- 最终验证 → Task 10 ✅

**2. Placeholder scan:** 无 TBD/TODO；每个代码步骤含完整代码块 ✅

**3. Type consistency:**
- `ChapterLifecycleStatus`、`UploadSessionStatus` 为既有枚举；`ExportTaskStatus`/`RecoveryTaskStatus`/`DirectoryScanTaskStatus` 在本计划 Task 7 定义并被 Task 7 自身消费 ✅
- VO 层 status 全部保持 String（`.name()`），前端契约不变 ✅
- `EnumTypeHandlers` 新增 Handler 与 `safeValueOf` 模式一致 ✅
