# ComicAtlas 2.2.0

个人漫画仓库：导入 ZIP、CBZ、本地目录，管理漫画与章节，阅读图片和视频，记录进度与喜欢，维护存储和回收站。

main 是面向用户的 Docker 本地部署分支，只包含应用源码、生产构建配置、部署文件和文档。测试、演示页面、开发启动脚本和迁移工具保留在 develop / feature 分支。用户只需安装 Docker，无需安装 Java、Node.js、Maven 或启动开发服务器。

## 首次安装（本机基础设施）

1. 安装并启动 Docker Desktop（Windows）或 Docker Engine 与 Compose 插件（Linux）。
2. 下载 main 分支的部署源码包并解压，或检出 main。进入项目目录，将 .env.example 复制为 .env。
3. 设置 MANGA_ROOT 为实际存储绝对路径（Windows 示例 F:/manga，Linux 示例 /data/manga），填写 MYSQL_ROOT_PASSWORD、API_MYSQL_PASSWORD、WORKER_MYSQL_PASSWORD、REDIS_PASSWORD、RABBITMQ_USER / RABBITMQ_PASSWORD。全部基础设施在本机 Docker 内运行，无需服务器、隧道或注册中心账号。
4. 在存储根下创建 hq、lq、thumbs、metadata、staging、trash、export、import 目录。待导入漫画放入 import；Worker 容器只看得到 MANGA_ROOT 内的文件。
5. 启动基础设施：

       docker compose up -d --wait mysql redis rabbitmq nacos

6. 创建 Worker 只读数据库账号。执行下列命令并输入 .env 中的 MySQL root 密码：

       docker compose exec mysql mysql -uroot -p

   在 MySQL 中执行以下 SQL，将账号和密码替换为 .env 中 WORKER_MYSQL_USER / WORKER_MYSQL_PASSWORD 的实际值；密码含单引号时需按 SQL 规则转义：

       CREATE USER IF NOT EXISTS 'comicatlas_ro'@'%' IDENTIFIED BY '替换为 Worker 密码';
       GRANT SELECT ON comic_atlas.* TO 'comicatlas_ro'@'%';
       EXIT;

   API 账号由全新 MySQL 数据卷自动创建；已有数据库需按[部署运维](docs/operations/management.md)核对授权。修改 .env 不会自动修改已有数据库账号密码。

7. 构建并启动应用：

       docker compose up -d --build --wait

8. 打开 [漫画库](http://localhost) 或 [管理后台](http://localhost/manage)。首次构建会下载镜像和依赖，需要网络连接。查看状态与故障：

       docker compose ps
       docker compose logs --tail=100 api-service worker-service gateway reading-service

基础设施只绑定宿主机回环地址，本机应用通过容器服务名连接。管理端面向可信个人环境，默认无业务鉴权；不要直接将管理后台或 Gateway 暴露到公网。

## 使用

- 在管理后台导入 MANGA_ROOT/import 下的 ZIP、CBZ 或目录；宿主机绝对路径必须位于配置的 MANGA_ROOT 内。标准分卷只选择最后的 .zip，所有 .z01… 分卷必须齐全。
- 漫画库提供搜索、筛选、阅读历史、喜欢与继续阅读；阅读器支持图片/视频混排、短视频模式、全屏与章节导航。
- 管理后台支持元数据、目录/章节、上传/替换、导出、任务中心、LQ 生成、HQ 删除、存储统计和回收站。
- 删除先进入回收站；永久清理需在回收站预览确认。HQ 删除前先确认已有可用 LQ。
- 导出为本地文件，产物位于 MANGA_ROOT/export，不提供浏览器下载端点。

## 可选配置

- AI 分析默认连接 AI_BASE_URL；本地模型需另备兼容 NVIDIA GPU、驱动和模型文件，使用 local-ai profile。普通漫画导入和阅读不依赖本地模型。

## 升级与备份

升级前暂停任务并备份数据库和整个 MANGA_ROOT。保留实际账号密码，按新版 .env.example 核对变量后重复应用启动命令。旧版 REMOTE_REDIS_PASSWORD、REMOTE_RABBITMQ_USER、REMOTE_RABBITMQ_PASSWORD 分别改为 REDIS_PASSWORD、RABBITMQ_USER、RABBITMQ_PASSWORD；存量 Docker 数据卷继续使用原账号密码，配置文件不会自动修改它们。API 启动时执行 Flyway 迁移；先确认 API 健康，再检查阅读和任务功能。数据库升级后的回退必须配套恢复升级前数据库备份。不要使用 docker compose down -v 删除数据卷。

## 文档

- [用户指南](docs/user-guide.md)
- [部署与维护](docs/operations/management.md)
- [2.2.0 发布说明](docs/releases/v2.2.0.md)
- [API 文档](docs/api.md)

## 许可证

仓库未声明开源许可证；使用范围以项目所有者授权为准。
