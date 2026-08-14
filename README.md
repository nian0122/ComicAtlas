# ComicAtlas 1.5

ComicAtlas 是一个面向个人收藏的本地漫画仓库平台。它把 ZIP、本地目录和 EHENTAI 来源统一导入到受控存储中，提供漫画管理、章节目录、图片与视频混排阅读、阅读历史以及存储维护能力。

## 版本定位

`main` 分支是面向用户使用的 1.5 稳定版本；日常功能开发进入 `develop` 分支。完整的安装、导入、阅读和维护说明见 [用户指南](docs/user-guide.md)。

## 功能概览

- ZIP、本地目录、EHENTAI 来源导入
- 异步导入任务、进度查看、取消和重试
- 漫画库搜索、筛选、排序和详情管理
- 目录树与章节导航
- 图片、视频混排阅读
- 阅读进度与历史记录
- LQ 生成、HQ 删除、存储统计和死信任务管理
- 存储恢复（异步任务中心），从 HQ 文件重建数据库记录
- 统一 MANAGED 存储，数据库只保存相对路径

### 管理控制台

管理后台位于 `/manage`，提供：

- **漫画工作区**：列表、详情、编辑（乐观锁 `version`）、元数据、标签、封面
- **目录/章节管理**：目录树的创建、重命名、移动、排序、删除；章节的创建、重排、回收
- **媒体管理**：媒体重排、回收；图片/视频上传/替换为预留接口能力（后端接口可用、当前无前端页面入口）
- **任务中心**：管理任务列表/详情/逐项进度、取消、重试，Outbox 积压监控
- **回收站**：删除进回收站（软删除），7 天保留期后可恢复或永久清理，支持对账修复
- **批量操作**：跨页按筛选或 ID 选择，预览 + 二次确认，批量 LQ/HQ/转码/元数据/回收/恢复/清理
- **允许操作查询**：按钮权限统一由后端 `OperationPolicyService` 判定，前端不自算
- **危险区**：永久清理、存储恢复、DLQ 管理

> 删除默认进入回收站而非直接物理删除；永久清理必须经过回收站并二次确认。详见[用户指南](docs/user-guide.md)的“管理后台”章节与[部署运维](docs/operations/management.md)。

## 快速开始

### 运行环境

- Docker Desktop 或 Docker Engine
- Java 21（源码运行时）
- Node.js 20+（前端开发时）
- MySQL 8、Redis、RabbitMQ、Nacos

### 使用 Docker 部署

1. 复制 `.env.example` 为不受 Git 跟踪的 `.env`，按分组填写漫画存储、远端基础设施和 FRP 配置：

   ```dotenv
   MANGA_ROOT=F:/manga
   REMOTE_INFRA_HOST=host.docker.internal
   MYSQL_ROOT_PASSWORD=请设置强密码
   API_MYSQL_USER=comicatlas_api
   API_MYSQL_PASSWORD=请设置强密码
   WORKER_MYSQL_USER=comicatlas_ro
   WORKER_MYSQL_PASSWORD=请设置另一组强密码
   REMOTE_MYSQL_PORT=3306
   REMOTE_REDIS_PORT=6379
   REMOTE_RABBITMQ_PORT=5672
   REMOTE_RABBITMQ_MANAGEMENT_PORT=15672
   REMOTE_NACOS_HTTP_PORT=8848
   REMOTE_NACOS_GRPC_PORT=9848
   REMOTE_NACOS_USER=nacos
   REMOTE_NACOS_PASSWORD=nacos
   REMOTE_REDIS_PASSWORD=
   REMOTE_RABBITMQ_USER=guest
   REMOTE_RABBITMQ_PASSWORD=guest
   FRP_SERVER_ADDR=远端服务器公网地址
   FRP_SERVER_PORT=7000
   FRP_DASHBOARD_PORT=7500
   ```

   > 仓库级 `.env` 使用 `API_MYSQL_*` 和 `WORKER_MYSQL_*` 区分写账号与只读账号。启动脚本或 Compose 会在进程边界映射为 Spring 使用的 `MYSQL_USER` / `MYSQL_PASS`；Worker 账号仅授予 `SELECT`，详见[部署运维](docs/operations/management.md)的"数据库账号"小节。

2. 确认 `MANGA_ROOT` 下存在 `hq`、`lq`、`thumbs`、`metadata`、`temp` 目录。

3. 如需在当前主机运行基础服务，单独启动 MySQL、Redis、RabbitMQ 和 Nacos：

   ```bash
   docker compose -f docker-compose.infra.yml up -d
   ```

4. 启动 Gateway、阅读服务、管理服务和 Nginx：

   ```bash
   docker compose -f docker-compose.yml up -d --build
   ```

5. 浏览器打开 [http://localhost](http://localhost)。管理后台位于 `/manage`。

> `docker-compose.infra.yml` 只管理基础服务，`docker-compose.yml` 只管理项目服务。使用远端基础设施时，不要在本地启动基础服务文件；通过 `tools/maintenance/manage-remote-infra-frp.ps1` 建立 FRP STCP 连接。部署步骤见 [FRP 基础设施连接](docs/operations/frp-infrastructure.md)。

### 源码开发

开发分支为 `develop`。在 Windows PowerShell 中可使用：

```powershell
.\scripts\dev\start-dev.ps1
```

前端单独启动：

```bash
cd frontend
pnpm install
pnpm dev
```

构建前端：

```bash
cd frontend
pnpm build
```

构建后端：

```bash
.\mvnw clean package
```

## 存储约定

漫画文件统一存放在：

```text
{MANGA_ROOT}/hq/{comicId}/{chapterId}/
{MANGA_ROOT}/lq/{comicId}/{chapterId}/
{MANGA_ROOT}/thumbs/
{MANGA_ROOT}/metadata/
{MANGA_ROOT}/staging/        # 上传临时目录（API 可写，不对外暴露）
{MANGA_ROOT}/trash/          # 回收站文件卷（软删除后移入，7 天保留期）
{MANGA_ROOT}/export/         # 导出产物目录
```

数据库中的页面只保存 `hq_root`、`hq_path` 等相对引用，不保存宿主机绝对路径。迁移存储时优先修改 `MANGA_ROOT` 或 `storage.roots.HQ.path` 配置，不要手动改写页面路径。

### 上传限制（默认）

| 项 | 默认值 | 环境变量 |
|----|--------|---------|
| 分块大小 | 16 MiB | `UPLOAD_CHUNK_SIZE` |
| 单文件上限 | 20 GiB | `UPLOAD_MAX_FILE_SIZE` |
| 单会话上限 | 100 GiB | `UPLOAD_MAX_SESSION_SIZE` |
| 单会话文件数 | 10000 | `UPLOAD_MAX_FILES` |
| 会话过期 | 24 小时 | `UPLOAD_SESSION_TTL` |
| 磁盘剩余下限 | 5 GiB 或 10% | `UPLOAD_FREE_SPACE_MIN_BYTES` / `UPLOAD_FREE_SPACE_MIN_RATIO` |

### 可信本机部署

ComicAtlas 面向单机个人仓库，管理端接口（回收站、永久清理、DLQ 等）默认不开启鉴权。请遵守：

- 仅部署在可信本机环境；基础服务（`docker-compose.infra.yml`）只绑定 `127.0.0.1` 回环地址。
- 不要把 Gateway 或 `.env` 中的数据库、管理台、注册中心端口直接暴露到公网；FRP 只开放 `FRP_SERVER_PORT`。
- 在宿主机或防火墙层限制对管理后台 `/manage` 的访问，需要远程访问时使用 SSH 隧道。

## 文档

- [用户指南](docs/user-guide.md)：安装、配置、导入、阅读、管理和故障排查
- [部署运维](docs/operations/management.md)：数据库账号、存储卷、备份、升级与回滚
- [开发流程](docs/development-guide.md)：分支、提交、合并、推送与发布
- [API 文档](docs/api.md)：HTTP 接口与事件状态
- [发布说明](docs/releases/v1.5.0.md)：1.5 功能范围与已知限制（历史版本见 [v1.0.0](docs/releases/v1.0.0.md)）
- [架构索引](docs/architecture/00-index.md)：系统设计与模块说明

## 分支约定

| 分支 | 用途 |
|------|------|
| `main` | 用户使用的稳定版本，发布 1.5 |
| `develop` | 日常开发、实验性功能和下一版本准备 |

## 许可证

当前仓库未声明开源许可证。除非项目所有者另行授权，请仅在个人设备和合法取得的内容范围内使用。
