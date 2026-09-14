# 项目目录维护约定

仓库根目录保留模块、构建入口、Compose、Nginx 配置和项目说明；专项资料按用途归档。

| 目录 | 用途 |
| --- | --- |
| `api-service/`、`reading-service/`、`worker-service/`、`gateway/` | 服务实现 |
| `comic-common/`、`comic-shared/` | 共享事件、契约和持久化模块 |
| `frontend/` | 前端源码与前端测试 |
| `config/checkstyle/` | Java 静态检查规则 |
| `config/maven/settings.xml` | Docker 构建使用的 Maven 镜像配置 |
| `docs/frontend/` | 前端设计文档，视觉规范入口为 `design-system.md` |
| `docs/development/` | 命名规范和目录维护约定 |
| `scripts/dev/`、`scripts/qa/`、`scripts/db/`、`scripts/release/` | 开发、测试、数据库和发布脚本 |
| `tools/` | 迁移、维护和第三方工具 |
| `e2e/` | 跨服务端到端测试 |

本地 `.runtime/`、`logs/`、各模块 `target/`、`node_modules/` 和 IDE 缓存不进入提交。旧 `comic-persistence/` 已不在父 POM 模块列表中，当前本地目录仅残留 `target/`，不应恢复为源码模块。

移动配置或文档时同步更新 Dockerfile、脚本、源码说明及文档索引。正式发布仍遵循 `AGENTS.md` 的部署树边界，不能将研发目录整体合入 `main`。
