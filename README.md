# ComicAtlas

ComicAtlas 是自托管的个人漫画与视频库：导入 ZIP、CBZ 和本地目录后，在浏览器里阅读图片和视频，管理章节、进度、收藏与评价。3.0 使用 Go + SQLite 后端和 Vue 前端，通过 Docker Compose 一键部署。

**当前版本**：v3.0.0-rc.6（预发布）。变更与升级说明见 [3.0.0-rc.6 发布说明](docs/releases/v3.0.0-rc.6.md)，历史版本见 [Releases](https://github.com/nian0122/ComicAtlas/releases)。

本仓库只包含发布所需的运行源码、构建配置、发布工具和公开文档；应用开发与测试在独立仓库维护。

## 功能

- 导入：ZIP、CBZ（含 `.z01` 起的分卷）和本地目录。
- 阅读：影院式图片阅读、视频连续播放、阅读进度、收藏、评价和阅读标记，支持续读。
- 整理：分类、标签、元数据与封面编辑、章节排序、批量编辑和目录移动。
- 媒体：轻量副本生成、高清副本释放、视频转码、存储统计与存储浏览。
- 维护：任务中心、回收站与恢复、永久清理、定时 SQLite 快照、ZIP/CBZ 导出。
- 可选 AI：对接 OpenAI 兼容的视觉模型，按需生成元数据候选，默认关闭。

## 系统要求

- Docker Desktop（Windows/macOS）或 Docker Engine + Compose 插件。
- Docker Compose v2.17 或更高版本（一键脚本会检查 `--wait` 支持）。
- 首次构建会从源码编译 FFmpeg、x264、libjpeg-turbo 和 libwebp，需要访问 GitHub、Debian 软件源和 Google 存储；构建时间取决于机器和网络，通常需要十几分钟到更久，并占用数 GB 磁盘。
- 两个入口默认只绑定 `127.0.0.1`。同一个 Docker 主机只运行一个实例。

## 快速开始

1. 获取源码：下载 [v3.0.0-rc.6 源码包](https://github.com/nian0122/ComicAtlas/archive/refs/tags/v3.0.0-rc.6.zip) 并解压，或克隆后检出标签：

   ```sh
   git clone --branch v3.0.0-rc.6 https://github.com/nian0122/ComicAtlas.git
   ```

2. 在项目目录执行一键启动：
   - Windows：双击 `start.cmd`，或在终端执行 `.\start.cmd`
   - Linux/macOS：执行 `sh start.sh`（从 ZIP 解压时没有执行权限，用 `sh` 运行即可）

3. 打开[漫画库](http://127.0.0.1:18078/)或[管理后台](http://127.0.0.1:18079/manage)。

首次运行会创建 `.env`、`storage/imports` 和 `storage/media`，然后构建并启动。已有 `.env` 不会被覆盖；升级时保留 `.env`、存储目录和数据库卷。

### 自定义存储目录

默认存储目录是项目内的 `storage`。首次启动时可指定其他目录：

```powershell
.\start.cmd -StorageRoot D:\ComicAtlas-storage
```

```sh
sh start.sh --storage-root /srv/comicatlas-storage
```

也可以先把 `.env.example` 复制为 `.env` 并修改 `COMICATLAS_STORAGE_ROOT`，再执行启动命令。路径必须是 Docker 主机上的绝对路径；Linux 下脚本会自动把属主设为 `10001:10001`，也可以手动执行 `sudo chown -R 10001:10001 /srv/comicatlas-storage`。

### 不重新构建启动

```powershell
.\start.cmd -NoBuild
```

```sh
sh start.sh --no-build
```

镜像不存在时会提示先执行一次完整构建。

## 数据与端口

| 项目 | 默认位置 | 说明 |
| --- | --- | --- |
| 漫画库 | `127.0.0.1:18078` | 可通过 `.env` 的 `COMICATLAS_WEB_PORT` 调整 |
| 管理后台 | `127.0.0.1:18079/manage` | 可通过 `COMICATLAS_ADMIN_PORT` 调整；没有登录认证 |
| SQLite 数据 | Docker 卷 `comicatlas_database`，容器内 `/data` | 升级和恢复时备份 |
| 导入目录 | `<存储根>/imports` | 管理后台导入时填写该目录内的完整宿主机路径 |
| 托管媒体 | `<存储根>/media` | 备份时与数据库一起备份 |
| 配置 | 项目内 `.env` | 端口、存储根目录和可选 AI 参数 |

## 备份、恢复与升级

以下命令都在项目目录执行。升级或恢复前先停止实例，避免复制到写入中的数据库：

```sh
docker compose down
```

> 不要使用 `docker compose down -v`，它会删除 `comicatlas_database` 卷。

### 备份

Linux/macOS：

```sh
docker run --rm -v comicatlas_database:/data -v "$PWD":/backup alpine \
  tar czf "/backup/comicatlas-database-$(date +%F).tar.gz" -C /data .
tar czf "comicatlas-media-$(date +%F).tar.gz" -C storage media
cp .env "comicatlas-env-$(date +%F).bak"
```

Windows PowerShell：

```powershell
docker run --rm -v comicatlas_database:/data -v "${PWD}:/backup" alpine `
  tar czf "/backup/comicatlas-database-$(Get-Date -Format yyyyMMdd).tar.gz" -C /data .
tar -czf "comicatlas-media-$(Get-Date -Format yyyyMMdd).tar.gz" -C storage media
Copy-Item .env "comicatlas-env-$(Get-Date -Format yyyyMMdd).bak"
```

### 恢复

停止实例后，把数据库卷和媒体目录恢复回原位置，再启动：

```sh
docker run --rm -v comicatlas_database:/data -v "$PWD":/backup alpine sh -c '
  find /data -mindepth 1 -delete &&
  tar xzf /backup/comicatlas-database-2026-11-26.tar.gz -C /data &&
  chown -R 10001:10001 /data'
tar xzf comicatlas-media-2026-11-26.tar.gz -C storage
```

Windows PowerShell 使用同样的 `docker run` 命令，把 `"$PWD"` 换成 `"${PWD}"`，再用 `tar -xzf` 解压媒体。恢复后执行 `start.cmd` 或 `sh start.sh`。

### 升级与回滚

1. 停止实例，按上面的步骤备份数据库、媒体和 `.env`。
2. 用新版本源码替换当前项目目录，保留 `.env` 和存储目录。
3. 执行 `start.cmd` 或 `sh start.sh`；启动时会应用数据库迁移。
4. 回滚时恢复升级前的数据库和媒体备份，再使用旧版本源码启动。

## 从 2.x 迁移

2.x 使用 Java、MySQL、Redis 和 RabbitMQ，3.0 使用 Go 和 SQLite，数据库结构不兼容，**没有自动迁移**。可选的做法是在旧版管理后台把漫画导出为 ZIP/CBZ（产物在旧存储的 `export` 目录），再把文件放入 3.0 的 `imports` 重新导入；阅读进度、收藏和评价等数据库记录不会随之迁移。升级前请保留旧数据库和媒体备份。

## 可选 AI 分析

AI 分析默认关闭。启用前准备一个 OpenAI 兼容的视觉模型服务（Base URL 不含 `/chat/completions`、模型名和 API Key），在 `.env` 中设置：

```dotenv
COMICATLAS_AI_ENDPOINT=http://ai:8080
COMICATLAS_AI_PROVIDER_URL=https://your-provider.example/v1
COMICATLAS_AI_MODEL=your-vision-model
COMICATLAS_AI_PROVIDER_KEY=your-api-key
```

然后执行：

```sh
docker compose --profile ai up -d --build --wait
```

AI 只在管理端手动触发时分析采样页面，结果作为整理候选，需要人工确认。

## 安全说明

- 两个入口默认只绑定 `127.0.0.1`。管理接口没有登录和权限系统，请勿通过反向代理、FRP 或其他方式暴露到公网。
- 使用专用导入目录；目录导入可能移动或重命名原始文件。
- 不要用不同源码目录在同一 Docker 主机上启动多个实例，它们会共用 `comicatlas_database` 卷。

## 常用命令

```sh
docker compose ps
docker compose logs --tail=100
```

## 卸载

```sh
docker compose down -v
rm -rf storage
```

`down -v` 会删除数据库卷，`storage`（默认存储目录）包含导入和托管媒体；执行前确认已有备份。

## 文档与反馈

- [发布说明](docs/releases/v3.0.0-rc.6.md) · [历史版本](https://github.com/nian0122/ComicAtlas/releases)
- [OpenAPI 定义](go/docs/openapi.json)
- 问题反馈请提交 [Issues](https://github.com/nian0122/ComicAtlas/issues)。这是个人项目，不承诺响应时间。

## 许可证

本项目采用 [MIT 许可证](LICENSE)。Copyright (c) 2026 nian0122。

构建过程中下载的第三方组件（如 FFmpeg、x264、libjpeg-turbo、libwebp）遵循各自许可证；分发构建产物时需要同时遵守对应组件的许可条件。
