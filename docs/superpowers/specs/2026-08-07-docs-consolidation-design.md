# 项目核心文档全面整理设计

**状态**: 历史归档

**日期**: 2026-08-07
**状态**: 设计待审阅
**范围**: 根目录 3 篇 + docs/ 63 篇 = 66 篇核心文档

## 1. 背景与动机

项目文档库已膨胀至 **90+ 篇 md**（根目录 3 + docs/ 63 + `.omo/drafts` 19 + evidence 等），存在三类问题（用户确认全选）：

| 问题 | 现状 |
|------|------|
| **过时** | `AGENTS.md` 写 event"16 个 record"实际 37 个、未反映 `constant/dto/enums/metadata/mq/util` 包；`architecture/01-system-overview`/`02-import-pipeline`/`03-storage` 引用旧包路径（`file/parse`→`importer` 等）；`docs/api.md` 未反映 `/api/storage` 统一端点 |
| **结构不统一** | `docs/README.md` 索引目录不准（adr 位置、superpowers/specs+plans）；文档头部模板（日期/状态/维护者）仅部分文档具备 |
| **冗余** | `issues/BUG-001~007` 状态未标注；`superpowers/specs+plans` 20 篇历史产物无归档标记 |

## 2. 范围与原则

- **范围**：根目录 3 篇（`AGENTS.md`/`README.md`/`DESIGN.md`）+ `docs/` 63 篇。
- **不动**：`.omo/drafts` 历史草稿（19 篇，非核心）。
- **ADR 例外**：`architecture/adr/` 是**决策记录**——不改写历史内容，仅同步交叉引用。
- **阿里规范**：文档与代码同步、命名统一、格式一致、避免冗余。

## 3. 整理批次（5 批，每批独立提交）

### 批次 1：AGENTS.md + docs/README.md 索引

- `AGENTS.md`：
  - event"16 个 record"→ 37 个（含 `ComicEvent` sealed 接口 + 各域事件）
  - 补充 `constant`/`dto`/`enums`/`metadata`/`mq`/`util` 包条目
  - 复查 worker 模块化路径（command/importer/media/storage 已部分更新，需完整核对）
  - MQ 表、IMPORT FLOW、CONFIG 表同步
- `docs/README.md`：索引目录修正（adr 位置、superpowers/specs+plans、issues 状态、新增文件）

### 批次 2：architecture/（12 篇）

- `01-system-overview`/`02-import-pipeline`/`03-storage`：旧包路径引用更新（`file/parse`→`importer`、`file/parse/MediaAnalyzer`→`media`、`file/storage`→`storage`、`LocalStorageService`→`StorageService`）
- `00-index` 交叉引用更新
- 核对 `04-management`/`05-domain`/`06-api`/`07-frontend`/`08-migration` 是否过时并修正

### 批次 3：api + 接口文档

- `docs/api.md`：核对 `/api/storage` 统一端点（LQ/HQ/导出/转码/统计）、事件表（16→37）、新接口（回收站/DLQ/批量操作）
- `architecture/06-api.md` 同步

### 批次 4：guide + operations + frontend

- `development-guide.md`：git 流程/分支约定核对
- `development/java-naming.md`：旧路径引用
- `operations/management.md`：worker 只读账号、MANGA_ROOT 约定核对
- `frontend/` 9 篇：路由（14 routes）/接口/页面核对
- `testing/`、`troubleshooting/`：核对

### 批次 5：归档清理 + 收尾

- `issues/BUG-001~007`：核对状态，已解决→标注"已解决"
- `docs/superpowers/specs+plans`（20 篇）：加"历史归档"标记（不删内容，保留决策轨迹）
- `DESIGN.md`：核对现状或标注归档
- 全库交叉链接校验（`docs/README.md` 索引链接有效性）

## 4. 格式统一（文档模板）

统一文档头部模板（应用到缺失文档）：

```markdown
# 标题

**更新日期**: YYYY-MM-DD
**状态**: 有效 | 待更新 | 历史归档
**维护者**: ComicAtlas 团队

---
```

标题层级（`#` 文档标题 / `##` 章节 / `###` 小节）、表格、代码块风格统一。

## 5. 验证

- 每批次：文档更新 + 提交（中文"动作 + 内容"）
- 批次间 grep 校验：旧包路径引用清零（除 ADR 历史记录）：
  - `file/parse`、`file/handler`、`file/storage`、`LocalStorageService`、`FilePathBuilder`、`16 个 record`
- 收尾：`docs/README.md` 索引链接有效性检查

## 6. 风险与缓解

| 风险 | 缓解 |
|------|------|
| ADR 历史记录被误改 | 批次 2 明确 ADR 仅同步交叉引用，不改写决策内容 |
| 机械替换误伤语义 | 每批次人工核对上下文，非纯 sed 替换 |
| 文档数量大、遗漏 | 批次划分 + 每批 grep 校验 + 收尾链接检查 |
| 与代码二次漂移 | 整理后 AGENTS.md 为唯一索引，定期同步机制（后续） |

## 7. 排除项（YAGNI）

- 不处理 `.omo/drafts` 历史草稿。
- 不删除 `superpowers/specs+plans` 内容（保留决策轨迹，仅加归档标记）。
- 不重写 ADR 决策内容。
