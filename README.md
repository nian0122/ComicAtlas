# ComicAtlas 3.0.0-rc.5

ComicAtlas 是个人漫画仓库，支持导入 ZIP、CBZ 和本地目录，管理漫画与章节，阅读图片和视频，以及记录进度、收藏和评价。本版使用 Go、SQLite 和 Vue。

当前为 3.0.0 预发布版，变更和升级说明见 [3.0.0-rc.5 发布说明](docs/releases/v3.0.0-rc.5.md)。

## 安装

需要 Docker Desktop（Windows/macOS）或 Docker Engine + Compose 插件，Compose v2.17 或更高版本。

1. 下载本版本的源码压缩包并解压，或检出 `v3.0.0-rc.5`。
2. 在项目目录执行一键启动：
   - Windows：双击 `start.cmd`，或在终端执行 `.\start.cmd`
   - Linux/macOS：执行 `sh start.sh`（从 ZIP 解压时没有执行权限，用 `sh` 运行即可）

   首次运行会创建 `.env` 和 `storage/imports`、`storage/media`，然后构建并启动；首次构建需要网络。
3. 打开[漫画库](http://127.0.0.1:18078/)或[管理后台](http://127.0.0.1:18079/manage)。端口可在 `.env` 中调整。

已有 `.env` 时脚本不会覆盖；升级时保留 `.env` 和 `storage`，替换源码后再次执行同一命令。

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

## 导入和维护

- 将待导入文件或目录放入存储目录的 `imports`，在管理后台选择导入并填写该目录内的完整宿主机路径。目录导入可能移动原件，请使用专用导入目录。
- `media` 存放托管媒体，Docker 命名卷 `database` 存放 SQLite 数据。升级前停止写入并备份两处数据。使用 `docker compose down` 停止实例并保留数据库卷。同一个 Docker 主机默认只运行一个实例；不要用不同源码目录同时启动多个实例，它们会共用 `comicatlas_database` 卷。
- 两个入口默认仅绑定本机。管理接口面向可信个人环境，直接提供管理操作，请保持其仅本机可达。
- 可选 AI 分析需要兼容的模型服务。一键脚本默认不启用 AI；在项目目录的 `.env` 中设置模型提供方和模型参数，将 `COMICATLAS_AI_ENDPOINT` 设为 `http://ai:8080`，再执行 `docker compose --profile ai up -d --build --wait`。
- 在项目目录查看服务状态和日志可使用 `docker compose ps` 和 `docker compose logs --tail=100`。

旧版 MySQL 数据需要单独迁移。首次尝试新版时，请使用新的存储目录并保留旧库备份。详见 [3.0.0-rc.5 发布说明](docs/releases/v3.0.0-rc.5.md)和 [OpenAPI 文档](go/docs/openapi.json)。

## 许可证

仓库未声明开源许可证。使用范围以项目所有者授权为准。
