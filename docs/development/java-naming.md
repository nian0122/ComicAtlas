# Java 命名规范（语义重命名）

**更新日期：** 2026-08-22
**适用范围：** `comic-atlas` 各 Java 模块（api-service / reading-service / worker-service / comic-common / comic-shared / gateway）
**依据：** 阿里 Java 开发手册《Java 开发手册（黄山版）》（禁止用魔法值、禁止拼音与单字母命名、缩写需为行业通用等约定）

> **强制规范：** 阿里 Java 开发规范和阿里 Java 命名规范是本项目必须遵守的门禁。新增代码不得引入本规范禁止的命名；历史代码整改必须按批次完成，未完成整改前不得宣称全项目完全合规。Checkstyle 通过只是基础语法门禁，不替代本规范的语义命名审查。

---

## 1. 内部名与 Wire 名分离原则

Java 源码中的**内部变量名/字段名**与**对外契约名**（DB 列名、REST JSON 键、MQ 事件键）是**三个独立命名空间**，互不耦合。修改内部名**绝不**允许改动任何 wire 契约；wire 契约一旦上线即为冻结，修改属于破坏性变更。

| 命名空间 | 语言/形态 | 决定方式 | 是否冻结 |
|----------|-----------|----------|----------|
| 内部字段/变量名 | Java camelCase（如 `batch`） | 编码规范自由改进 | 可随重构演进 |
| DB 列名 | snake_case（如 `is_batch`） | `@TableField` / DDL | 冻结（迁移成本高） |
| REST JSON 键 | camelCase（如 `isBatch`） | `@JsonProperty` | 冻结（前端/外部调用方依赖） |
| MQ 事件键 | 点分小写（如 `comic.import.task.completed`） | RabbitMQ routing key | 冻结（见 AGENTS.md 事件命名规范） |

### 示例：`ManagementTask.batch`

```java
// 内部名：batch（可自由演进）
/** 是否批量任务（DB 列 is_batch，内部名 batch） */
@TableField("is_batch")
private Boolean batch;
```

```java
// REST JSON 键：isBatch（冻结契约，内部名仍为 batch）
/** 是否批量任务（REST 键 isBatch，内部名 batch） */
@JsonProperty("isBatch")
private Boolean batch;
```

- 内部名取 `batch`（避免 `isBatch` 的 getter 歧义：`isBatch()` 会被 Lombok 当作 `batch` 的 getter 而非布尔描述）。
- DB 列必须用 `@TableField("is_batch")` 显式声明，因为列名是 wire 契约。
- REST 键必须用 `@JsonProperty("isBatch")` 显式声明，因为 JSON 键是 wire 契约。

**执行规则：**
1. 重命名内部名时，同时检查该字段是否有 `@TableField` / `@JsonProperty` / MQ DTO 字段，只允许改 Java 侧名字，**wire 名原样保留**。
2. 禁止通过修改 DB 列名、JSON 键名、MQ routing key 来“同步”内部名。
3. 新增 wire 契约前先确认是否为既有接口的演进；既有契约一律通过注解显式映射，杜绝隐式依赖 Lombok/Jackson 默认命名。

---

## 2. 允许清单（允许使用）

以下短名/缩写/后缀属于项目内**明确允许**的例外，不需要展开为全名，也不纳入禁止映射。

| 类别 | 允许写法 | 理由 |
|------|----------|------|
| 类型后缀 | `FooDTO` / `FooVO` / `FooResponse` / `FooRequest` | 分层类型后缀，行业惯例 |
| ID | `id` / `comicId` / `chapterId` / `taskId` | 通用领域词，`id` 单字母但语义完整 |
| 循环索引 | `i` / `j` / `k` | 仅限三层以内的循环下标，且循环体内不产生其它单字母局部变量 |
| 存储/尺寸缩写 | `HQ` / `LQ` / `MQ` / `LQ_PATH` / `HQ_PATH` | 项目领域既有缩写（High Quality / Low Quality / Medium Quality），禁止再展开为 `highQuality` 等新名 |
| 图片字节解析局部变量 | `ImageDimensionsReader` 中的 `w` / `h` / `b` | 像素解析热路径，作用域为一个方法，语义封闭（width/height/byte）；列入允许，禁止改动 |
| 合法谓词方法 | `isTerminal()` / `isReadable()` / `isProcessing()` / `isTransient()` | 标准 JavaBean 布尔谓词，`is` + 形容词的既定形态 |
| 既有 wire 契约 | `pageNumber` / `pageId` / `isBatch` 等 | 已上线的 REST/DB/MQ 契约字段名，作为内部名同样保留（见第 1 节） |

> **循环索引边界：** `i/j/k` 只在 `for` / `for-each` 循环头中允许；一旦索引被提出循环成为“业务值”，必须改名（如 `chapterIndex` → 依语义取 `chapterNo`/`position`）。

---

## 3. 禁止映射（短名 → 标准全名）

以下短名是**明确禁止**的命名，出现即应改为右侧标准全名。映射一旦建立即为规范，新增代码不允许回退。

| 禁止的短名 | 标准全名 | 说明 |
|------------|----------|------|
| `Comic c` | `comic` | 实体短名禁用 |
| `Chapter ch` | `chapter` | 实体短名禁用 |
| `Media m` | `media` | 实体短名禁用 |
| `RecoveryTask t` | `recoveryTask` | 实体短名禁用（`t` 同时是泛型常用符号，务必回避） |
| `ManagementTask mt` | `managementTask` | 实体短名禁用 |
| `ComicTag ct` | `comicTag` | 实体短名禁用 |
| `ProcessBuilder pb` | `processBuilder` | JDK 类型短名禁用 |
| `Process proc` | `process` | JDK 类型短名禁用（`proc` 同时是 Windows 术语，易混淆） |
| `LambdaUpdateWrapper uw` | `<领域>Update` | 泛型包装器必须带领域前缀，如 `comicUpdate` / `chapterUpdate` |
| `List<Media> pages` | `mediaItems` | 集合名必须表达元素语义（元素是 Media 而非 Page） |
| `Media page` | `media` | 元素语义与类型名必须一致，禁止用 `page` 指代 Media |
| `Result<T> r` | `result` | 静态工厂方法的返回容器（本计划已修复，见第 4 节） |
| `tmp`（临时路径） | `tempPath` | 文件/目录临时路径变量（本计划已修复，见第 4 节） |

### 判断标准

1. **实体类实例**一律用全名小驼峰（类名去首字母大写），禁止 `c`/`ch`/`m` 等单字符别名。
2. **集合**变量名用 `<元素语义>s`/`Items`，禁止用首字母缩写或与元素类型无关的名字。
3. **包装器/构建器**（Wrapper / Builder / Update）必须带领域前缀，禁止裸 `uw`/`wb`。
4. **临时文件路径**统一 `tempPath`；临时目录统一 `tempDir`；禁止 `tmp`（与 `.tmp` 扩展名含义不同）。
5. 遇到新短名时先问“它是否在允许清单”，不在即查本节映射，仍无映射的按第 1 节原则命名全名。

---

## 4. 本计划的完整执行上下文

本规范由一次**共享工具类局部变量语义重命名**任务落地。该任务为阿里编码规范在 `comic-atlas` 上的第一批定点修复，范围与验收如下。

### 4.1 本次修改的 3 处

| 文件 | 修改前 | 修改后 | 语义 |
|------|--------|--------|------|
| `api-service/.../common/Result.java` | `Result<T> r`（3 个静态工厂方法内） | `result` | 返回容器对象，全名可读 |
| `worker-service/.../storage/SafeMoveStrategy.java` | `tmp`（`moveCrossVolume` 局部 + `verifyCopySize` 参数） | `tempPath` | 跨卷复制的临时路径 |
| `worker-service/.../importer/ImportManifestManager.java` | `tmp`（`write` 方法局部） | `tempPath` | 清单原子写入的临时文件路径 |

### 4.2 修复边界（明确不动）

- **`comic-common/.../util/ImageDimensionsReader.java`** 中的局部 `w` / `h` / `b`：图片字节解析热路径，作用域封闭，**保留**（列入第 2 节允许清单）。
- `DTO` / `VO` 后缀、`id`、循环索引 `i/j/k`、`HQ/LQ/MQ` 缩写：**保留**。
- `AGENTS.md`：**不改**。
- 全部 test 源码：**不改**（`SafeMoveStrategy.verifyCopySize` 等包私有方法签名参数名不参与调用契约，测试不受影响）。

### 4.3 执行方式与验收

- 采用**逐处精确替换**：先读取完整方法体确认作用域，再替换局部声明及其全部引用；**禁止全局文本替换**（防止误伤 `.tmp` 扩展名、`tmpSize` 等近似标识符）。
- 编译验证：`.\mvnw.cmd -pl comic-common,gateway -am -DskipTests compile`。
- 验收标准：
  - `Result.java` 无单字母结果变量（`\bResult<T> r\b` 匹配 0）。
  - `SafeMoveStrategy.java` / `ImportManifestManager.java` 无 `\btmp\b`（`tmpSize` 不在词边界内，符合）。
  - `comic-common` / `gateway` 编译通过。

### 4.4 后续批次（本次未执行）

本计划只覆盖 3 处定点修改。第 3 节禁止映射表已登记其余高频短名（`Comic c`、`Chapter ch`、`Media m`、`LambdaUpdateWrapper uw` 等），后续可按文件分批执行，遵循同一精确替换 + 编译验证流程，并在每次完成后回填“已修复”标记。
