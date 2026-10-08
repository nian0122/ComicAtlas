# ComicAtlas 文档索引

| 入口 | 说明 |
|------|------|
| [API 文档](api.md) | HTTP 接口与事件状态 |
| [用户指南](user-guide.md) | 安装、导入、阅读和管理 |
| [开发流程](development-guide.md) | 分支、提交和发布 |
| [前端设计系统](frontend/design-system.md) | 视觉规范；实现令牌位于 `frontend/src/app/styles/tokens.css` |
| [`architecture/README.md`](architecture/README.md) | 当前架构总览；专题设计与 ADR 位于 `architecture/` |
| [共享模块边界](architecture/shared-module-boundaries.md) | 跨服务契约与持久化模块边界 |
| [管理端存储统计](architecture/storage-statistics.md) | 统计口径、后台容量快照与升级方式 |
| [后端代码分类](architecture/backend-package-organization.md) | 业务域、框架职责与文件归属 |
| [后端待解耦清单](architecture/backend-decoupling.md) | 源码标记、拆分约束与回归要求 |
| [后端三层架构检查](architecture/backend-layer-audit.md) | 接口层越界、持久化框架类型泄漏和处理约束 |
| [后端实现问题标记](architecture/backend-implementation-issues.md) | 状态并发、事务、计算与异常处理问题 |
| [部署运维](operations/management.md) | 部署、升级与故障处理 |
| [公网阅读](operations/public-reading.md) | FRP 阅读入口、访问白名单与进度保存 |
| [发布说明](releases/v2.2.0.md) | 当前稳定版 v2.2.0；旧版本见 `releases/` |
| [Java 命名规范](development/java-naming.md) | 开发约定 |
| [`development/export-performance.md`](development/export-performance.md) | 导出压缩策略、完整性校验与性能基准 |
| [`development/project-layout.md`](development/project-layout.md) | 项目目录用途、配置位置和本地产物边界 |
| [`database/schema.md`](database/schema.md) | 数据库结构 |
| [`frontend/README.md`](frontend/README.md) | 前端文档与设计规范导航 |
| [`../scripts/README.md`](../scripts/README.md) | 发布与维护入口 |
| [`../tools/README.md`](../tools/README.md) | 维护工具入口 |

## 阅读规则

- 当前行为以 `README.md`、`user-guide.md`、`api.md`、`operations/` 和架构索引中的“现行”文档为准。
- `architecture/archive/v0.2/` 与 `frontend/archive/` 只保留历史设计背景，不应作为当前接口、目录结构或视觉实现依据。
- `releases/` 中的旧版本说明是历史记录，不代表当前接口或部署流程。
