# ComicAtlas 1.0

ComicAtlas 是一个面向个人收藏的本地漫画仓库平台。它把 ZIP、本地目录和 EHENTAI 来源统一导入到受控存储中，提供漫画管理、章节目录、图片与视频混排阅读、阅读历史以及存储维护能力。

## 1.0 版本定位

`main` 分支是面向用户使用的 1.0 稳定版本；日常功能开发进入 `develop` 分支。完整的安装、导入、阅读和维护说明见 [用户指南](docs/user-guide.md)。

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

## 快速开始

### 运行环境

- Docker Desktop 或 Docker Engine
- Java 21（源码运行时）
- Node.js 20+（前端开发时）
- MySQL 8、Redis、RabbitMQ、Nacos

### 使用 Docker 部署

1. 创建 `.env`，至少设置漫画存储目录及外部基础设施凭据：

   ```dotenv
   MANGA_ROOT=D:/manga
   MYSQL_ROOT_PASSWORD=请设置强密码
   REMOTE_MYSQL_USER=comicatlas
   REMOTE_MYSQL_PASSWORD=请设置强密码
   REMOTE_NACOS_USERNAME=nacos
   REMOTE_NACOS_PASSWORD=nacos
   REMOTE_REDIS_PORT=6379
   REMOTE_REDIS_PASSWORD=
   REMOTE_RABBITMQ_USER=guest
   REMOTE_RABBITMQ_PASSWORD=guest
   ```

2. 确认 `MANGA_ROOT` 下存在 `hq`、`lq`、`thumbs`、`metadata`、`temp` 目录。

3. 如需在当前主机运行基础服务，单独启动 MySQL、Redis、RabbitMQ 和 Nacos：

   ```bash
   docker compose -f docker-compose.infra.yml up -d
   ```

4. 启动 Gateway、API 和 Nginx：

   ```bash
   docker compose -f docker-compose.yml up -d --build
   ```

5. 浏览器打开 [http://localhost](http://localhost)。管理后台位于 `/manage`。

> `docker-compose.infra.yml` 只管理基础服务，`docker-compose.yml` 只管理项目服务。使用远端基础设施时，不要在本地启动基础服务文件，先运行 `tools/start-remote-infra-tunnel.ps1` 建立 SSH 隧道即可。首次部署前请阅读 [用户指南](docs/user-guide.md) 的基础设施章节。

### 源码开发

开发分支为 `develop`。在 Windows PowerShell 中可使用：

```powershell
.\start-dev.ps1
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
```

数据库中的页面只保存 `hq_root`、`hq_path` 等相对引用，不保存宿主机绝对路径。迁移存储时优先修改 `MANGA_ROOT` 或 `storage.roots.HQ.path` 配置，不要手动改写页面路径。

## 文档

- [用户指南](docs/user-guide.md)：安装、配置、导入、阅读、管理和故障排查
- [开发流程](docs/development-guide.md)：分支、提交、合并、推送与发布
- [API 文档](docs/api.md)：HTTP 接口与事件状态
- [发布说明](docs/release/v1.0.0.md)：1.0 功能范围与已知限制
- [架构索引](docs/architecture/00-index.md)：系统设计与模块说明

## 分支约定

| 分支 | 用途 |
|------|------|
| `main` | 用户使用的稳定版本，发布 1.0 |
| `develop` | 日常开发、实验性功能和下一版本准备 |

## 许可证

当前仓库未声明开源许可证。除非项目所有者另行授权，请仅在个人设备和合法取得的内容范围内使用。
