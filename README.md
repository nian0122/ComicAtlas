# ComicAtlas 3.0.0-rc.2

ComicAtlas 是个人漫画仓库，支持导入 ZIP、CBZ 和本地目录，管理漫画与章节，阅读图片和视频，以及记录进度、收藏和评价。本版使用 Go、SQLite 和 Vue。

当前为 3.0.0 预发布版。浏览器测试仍有未通过的场景，详见发布说明。

## 安装

1. 安装并启动 Docker Desktop，或安装 Docker Engine 和 Compose 插件。下载本版本的源码压缩包并解压，或检出 `v3.0.0-rc.2`。
2. 将 `.env.example` 复制为 `.env`。将 `COMICATLAS_STORAGE_ROOT` 设置为 Docker 主机上的专用绝对路径。在该目录内创建 `imports` 和 `media`。Windows 路径可使用 `C:/comicatlas-storage` 这种写法。
3. 确认容器用户 UID/GID `10001:10001` 对两个子目录可读写。Linux 上可以用 `sudo chown 10001:10001 /srv/comicatlas-storage/{imports,media}` 设置刚创建的子目录，按需替换路径。
4. 在项目目录执行 `docker compose up -d --build --wait`。首次构建需要网络。
5. 打开[漫画库](http://127.0.0.1:18078/)或[管理后台](http://127.0.0.1:18079/manage)。调整 `.env` 中的端口后，使用对应的新地址。

## 导入和维护

- 将待导入文件或目录放入 `imports`，在管理后台选择导入并填写该目录内的完整宿主机路径。目录导入可能移动原件，请使用专用导入目录。
- `media` 存放托管媒体，Docker 命名卷 `database` 存放 SQLite 数据。升级前停止写入并备份两处数据。使用 `docker compose down` 停止实例并保留数据库卷。
- 两个入口默认仅绑定本机。管理接口面向可信个人环境，直接提供管理操作，请保持其仅本机可达。
- 可选 AI 分析需要兼容的模型服务。在 `.env` 中设置模型提供方和模型参数，将 `COMICATLAS_AI_ENDPOINT` 设为 `http://ai:8080`，再运行 `docker compose --profile ai up -d --build --wait`。
- 查看服务状态和日志可使用 `docker compose ps` 和 `docker compose logs --tail=100`。

旧版 MySQL 数据需要单独迁移。首次尝试新版时，请使用新的存储目录并保留旧库备份。详见 [3.0.0-rc.2 发布说明](docs/releases/v3.0.0-rc.2.md)和 [OpenAPI 文档](go/docs/openapi.json)。

## 许可证

仓库未声明开源许可证。使用范围以项目所有者授权为准。
