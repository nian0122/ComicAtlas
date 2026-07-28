# ComicAtlas 用户指南

本文面向第一次部署和使用 ComicAtlas 的用户，覆盖 1.0 版本的常用流程。

## 一、部署前准备

ComicAtlas 由前端、Gateway、API、Worker、MySQL、Redis、RabbitMQ、Nacos 和 Nginx 组成。漫画文件由 Worker 写入本地存储，Nginx 以只读方式向浏览器提供文件。

准备以下环境：

- Docker Desktop（Windows）或 Docker Engine（Linux）
- MySQL 8
- Redis
- RabbitMQ
- Nacos 2.x
- 可写的漫画存储目录，例如 `D:/manga`

Windows 用户建议使用正斜杠书写路径，例如 `D:/manga`，并确认 Docker Desktop 已共享该磁盘。

## 二、配置存储和基础设施

在项目根目录创建 `.env`：

```dotenv
MANGA_ROOT=D:/manga
REMOTE_NACOS_USERNAME=nacos
REMOTE_NACOS_PASSWORD=nacos
REMOTE_REDIS_PORT=6379
REMOTE_REDIS_PASSWORD=
REMOTE_RABBITMQ_USER=guest
REMOTE_RABBITMQ_PASSWORD=guest
```

创建目录：

```text
D:/manga/hq
D:/manga/lq
D:/manga/thumbs
D:/manga/metadata
D:/manga/temp
```

启动或准备好 Redis、RabbitMQ、Nacos 后，再启动应用：

```bash
docker compose up -d --build
docker compose ps
```

访问地址：

- 用户端：`http://localhost`
- 管理后台：`http://localhost/manage`
- Gateway/API：`http://localhost:8000`

如果基础设施运行在远程主机，可使用仓库中的 `tools/start-remote-infra-tunnel.ps1` 建立 SSH 隧道，并让 Docker 容器通过 `host.docker.internal` 访问宿主机端口。

## 三、导入漫画

### 1. ZIP 导入

打开管理后台的“导入”，选择 ZIP 来源并填写宿主机路径，例如：

```text
D:/downloads/comic.zip
```

提交后，任务会进入任务中心。Worker 会解压、解析目录、分析媒体并把文件搬入 MANAGED 存储。

### 2. 本地目录导入

选择本地目录来源，填写漫画目录，例如：

```text
D:/downloads/ComicA
```

目录中可以包含章节目录，也可以直接包含图片或视频。章节顺序以解析后的全局顺序为准。

### 3. EHENTAI 导入

选择 EHENTAI 来源并填写画廊 URL。该流程需要可用的网络连接、下载工具和代理配置；请只导入你有权保存的内容。

### 4. 查看任务

进入“导入任务”查看状态：

```text
PENDING → PARSING → IMPORTING → SUCCESS
                         └──────→ FAILED
```

失败任务可查看错误信息并重试。导入中不建议直接移动或删除源文件及 `MANGA_ROOT/temp` 下的临时文件。

## 四、浏览和阅读

### 漫画库

用户端首页或“漫画库”可以按标题、作者、标签、分类和状态筛选，也可以按创建时间、更新时间、标题或页数排序。

### 漫画详情

打开漫画卡片进入详情页。详情页提供封面、元数据、标签、目录树和章节入口；管理后台可修改标题、作者、描述、分类、标签以及封面候选。

### 阅读器

在章节中可以使用上一章、下一章和页码导航。常用操作：

- 方向键：翻页
- 空格：翻页
- 返回：回到漫画详情

图片和视频页面会按照章节中的顺序混排显示。离开阅读器后，章节和页码会保存到阅读历史；从“阅读历史”点击“继续阅读”即可恢复。

## 五、存储管理

管理后台的“存储管理”用于查看漫画、章节、HQ/LQ 大小和文件状态。

- LQ 不会自动生成，需要手动触发。
- 删除 HQ 后，页面仍保留数据库记录，但 HQ 状态会变为 `DELETED`。
- 删除整本漫画会同时清理数据库记录及对应文件。
- 发现文件被外部移动或删除时，优先使用扫描/重建元数据功能，不要直接修改数据库路径。

默认布局：

```text
MANGA_ROOT/hq/{comicId}/{chapterId}/文件名
MANGA_ROOT/lq/{comicId}/{chapterId}/文件名
```

## 六、常见问题

### 页面能打开，但导入任务不动

检查 RabbitMQ、Redis、Nacos 是否可连接，并查看 API 和 Worker 日志。任务需要由 RabbitMQ 投递给 Worker，只有 API 正常并不代表导入链路完整。

### 图片显示 404

检查 `MANGA_ROOT` 是否被 API 和 Nginx 挂载到同一份数据；确认 `hq`、`lq`、`thumbs` 目录存在，并检查文件状态是否为 `READY`。

### Windows 下目录导入失败

确认 Docker Desktop 已共享漫画所在磁盘，路径使用宿主机绝对路径，并检查 Worker 的宿主机路径与容器路径映射。

### 移动端无法打开管理后台

这是 1.0 的预期行为：移动设备默认只开放阅读端，管理后台建议使用桌面浏览器。

### 如何迁移漫画存储目录

停止写入任务，完整复制 `hq`、`lq`、`thumbs`、`metadata` 目录到新位置，然后修改 `MANGA_ROOT` 并重启服务。不要修改数据库中的相对路径。

## 七、数据安全建议

- 定期备份 MySQL 数据库和 `MANGA_ROOT` 目录。
- 删除 HQ 或整本漫画前先确认备份状态。
- 不要把 `.env`、数据库密码和远程基础设施凭据提交到 Git。
- 仅导入和保存合法来源的漫画内容。
