# ComicAtlas 文档索引

| 入口 | 说明 |
|------|------|
| `api.md` | HTTP 接口与事件状态 |
| `user-guide.md` | 用户指南 |
| `development-guide.md` | 开发流程 |
| [`frontend/design-system.md`](frontend/design-system.md) | 前端视觉设计规范；实现令牌位于 `frontend/src/styles/tokens.css` |
| [`architecture/README.md`](architecture/README.md) | 当前架构总览；专题设计与 ADR 位于 `architecture/` |
| `architecture/shared-module-boundaries.md` | 跨服务契约与持久化模块边界 |
| [后端代码分类](architecture/backend-package-organization.md) | 业务域、框架职责与文件归属 |
| [后端待解耦清单](architecture/backend-decoupling.md) | 源码标记、拆分约束与回归要求 |
| [后端三层架构检查](architecture/backend-layer-audit.md) | 接口层越界、持久化框架类型泄漏和处理约束 |
| [后端实现问题标记](architecture/backend-implementation-issues.md) | 状态并发、事务、计算与异常处理问题 |
| `operations/` | 部署运维 |
| `releases/` | 发布说明（当前稳定版 v2.1.0，历史版本归档） |
| `development/` | 开发约定（java-naming） |
| [`development/export-performance.md`](development/export-performance.md) | 导出压缩策略、完整性校验与性能基准 |
| [`development/project-layout.md`](development/project-layout.md) | 项目目录用途、配置位置和本地产物边界 |
| `issues/` | 当前待办 |
| [`database/schema.md`](database/schema.md) | 数据库结构 |
| [`frontend/README.md`](frontend/README.md) | 前端文档与设计规范导航 |
| [`../scripts/README.md`](../scripts/README.md) | 开发、测试与发布脚本入口 |
| [`../tools/README.md`](../tools/README.md) | 维护工具入口 |
| [`../e2e/README.md`](../e2e/README.md) | 浏览器与前端测试目录说明 |

## 阅读规则

- 当前行为以 `README.md`、`user-guide.md`、`api.md`、`operations/` 和架构索引中的“现行”文档为准。
- `architecture/archive/v0.2/` 与 `frontend/archive/` 只保留历史设计背景，不应作为当前接口、目录结构或视觉实现依据。
- `releases/` 中的旧版本说明是历史记录，不代表当前接口或部署流程。
