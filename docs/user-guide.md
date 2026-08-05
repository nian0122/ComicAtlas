# ComicAtlas 用户指南

本文面向第一次部署和使用 ComicAtlas 的用户，覆盖 1.0 版本的常用流程。

> 管理控制台（回收站、批量操作、媒体上传、任务中心）为 v1.0 新增。部署与数据库账号等运维细节见[部署运维](operations/management.md)。

## 一、部署前准备

ComicAtlas 由前端、Gateway、API、Worker、MySQL、Redis、RabbitMQ、Nacos 和 Nginx 组成。漫画文件由 Worker 写入本地存储，Nginx 以只读方式向浏览器提供文件。

准备以下环境：

- Docker Desktop（Windows）或 Docker Engine（Linux）
- MySQL 8
- Redis
- RabbitMQ
- Nacos 2.x
- 可写的漫画存储目录，例如 `F:/manga`

Windows 用户建议使用正斜杠书写路径，例如 `F:/manga`，并确认 Docker Desktop 已共享该磁盘。

## 二、配置存储和基础设施

在项目根目录创建 `.env`：

```dotenv
MANGA_ROOT=F:/manga
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

创建目录：

```text
F:/manga/hq
F:/manga/lq
F:/manga/thumbs
F:/manga/metadata
F:/manga/temp
F:/manga/staging
F:/manga/trash
F:/manga/export
```

> v1.0 新增 `staging`（上传临时目录）与 `trash`（回收站文件卷）。`staging` 由 API 写入、不经 Nginx 暴露；`trash` 存放软删除后移入的文件，默认保留 7 天。

基础服务与项目服务使用不同的 Compose 文件。需要在当前主机运行基础服务时执行：

```bash
docker compose -f docker-compose.infra.yml up -d
docker compose -f docker-compose.infra.yml ps
```

基础服务准备好后，再启动项目服务：

```bash
docker compose -f docker-compose.yml up -d --build
docker compose -f docker-compose.yml ps
```

访问地址：

- 用户端：`http://localhost`
- 管理后台：`http://localhost/manage`
- Gateway/API：`http://localhost:8000`

`docker-compose.infra.yml` 包含 MySQL、Redis、RabbitMQ 和 Nacos，端口号保持为 3306、6379、5672、15672、8848、9848，并只绑定主机回环地址。如果基础设施运行在远程主机，本地不启动该文件；使用仓库中的 `tools/start-remote-infra-tunnel.ps1` 建立 SSH 隧道，让项目容器通过 `host.docker.internal` 访问宿主机映射端口。

### 可信本机部署

管理端接口（回收站、永久清理、批量操作、DLQ）默认不开启业务鉴权，因此 ComicAtlas 只适合部署在**可信本机**：

- 只在本机或受控内网使用，不要直接暴露 `8000`（Gateway）、`15672`（RabbitMQ 管理台）、`8848`（Nacos）、`3306`（MySQL）等端口到公网。
- 基础设施容器只绑定回环地址（见 `docker-compose.infra.yml`），远程访问通过 SSH 隧道。
- 管理后台 `/manage` 建议配合宿主机防火墙或反向代理做访问限制。
- 生产使用前先阅读[部署运维](operations/management.md)中的账号、备份与升级说明。

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
- **删除整本漫画会先进入回收站**（软删除），确认后再永久清理。
- 发现文件被外部移动或删除时，优先使用扫描/重建元数据功能，不要直接修改数据库路径。

默认布局：

```text
MANGA_ROOT/hq/{comicId}/{chapterId}/文件名
MANGA_ROOT/lq/{comicId}/{chapterId}/文件名
```

### 回收站（v1.0）

**删除语义变更**：v1.0 起“删除漫画/章节/媒体”不再直接物理删除，而是创建管理任务，把文件按清单移入 `MANGA_ROOT/trash/`，同时记录 `TrashManifest` 清单（文件恢复、对账的依据）。

- 进入回收站的对象生命周期为 `TRASHED`，可恢复或永久清理。
- **永久清理**必须在对象处于 `TRASHED` 状态、且已超过 **7 天保留期**后才能执行，并需二次确认 token。
- **恢复**把文件从 `trash` 卷移回原 `hq/lq` 位置，恢复生命周期为 `READY`。
- **对账**（reconcile）检查 DB 状态、Manifest 清单与实际文件的差异，可修复可安全自动恢复的 DB 状态；回收站页面或对象详情上提供入口。

操作路径：管理后台 → 漫画列表（筛选 `TRASHED`）→ 漫画工作区 → 危险区；或直接进入“回收站”页面。

### 批量操作（v1.0）

管理后台漫画列表支持**跨页批量操作**：

1. 在列表选择目标：按勾选 ID 或按当前筛选条件（FILTER，可排除部分 ID）。
2. 点击批量操作按钮（批量生成 LQ、批量删除 HQ、批量转码、批量更新元数据、批量回收/恢复/永久清理）。
3. 系统先执行**预览**，显示命中数量、可执行数量与被阻塞原因。
4. **危险操作**（如批量永久清理）必须二次确认，确认后 5 分钟内有效；期间筛选条件变化或 token 过期会拒绝提交，需重新预览。
5. 提交后生成批量任务（含逐项快照），可在任务中心查看每一项的进度与失败原因。

批量上限默认 10000 个目标（`comic.batch.max-items`），超限时预览阶段即提示。

### 媒体上传（v1.0）

在漫画工作区的“媒体”页可向章节上传图片/视频：

- 支持大文件**分块断点续传**（默认每块 16 MiB），网络中断后可继续未完成的分块。
- 单文件上限 20 GiB，单次会话上限 100 GiB，会话 24 小时未完成自动过期。
- 上传先进入 `staging`，完成后由后台分析并搬入 HQ；视频会保留元数据用于阅读器混排。
- 上传、重排（`POST /chapters/{id}/media/reorder`）、媒体回收均在“媒体”页完成。

### 从存储恢复数据库记录

当数据库中的漫画记录被意外删除（例如通过"仅删除数据库"操作），但 HQ 磁盘文件仍然完好时，可以使用恢复任务从 HQ 文件重建数据库记录。这相当于"用文件反向恢复数据库"。

#### 适用场景

- 数据库记录被删除，但 `MANGA_ROOT/hq/` 下的漫画文件仍然存在。
- 迁移存储后需要重建数据库目录。
- 意外数据丢失后的批量恢复。

> **不适用**：如果文件已通过 `DELETE_FILES` 模式删除，恢复任务无法找回已删除的文件。删除文件前请务必确认备份。

#### 操作步骤

1. 登录管理后台，进入**任务中心**（`/manage/import/tasks`）。
2. 在任务中心页面找到并点击 **"从存储恢复数据库记录"** 按钮。
3. 在弹出的确认对话框中点击"确认"。
4. 任务创建后自动进入异步处理：
   - PENDING：任务已创建，等待 Worker 扫描 HQ 目录。
   - RUNNING：Worker 已完成扫描，API 正在逐本恢复数据库记录。
   - SUCCESS：全部漫画处理完成。
   - FAILED：扫描或恢复过程出现错误，可重试。

处理过程中，任务详情页会实时更新计数器（total / recovered / skipped / placeholder / error），无需刷新页面即可查看进度。

#### 处理结果解读

| 计数 | 含义 |
|------|------|
| totalComics | HQ 目录下扫描到的漫画目录总数 |
| recoveredComics | 有 metadata 文件且成功恢复的漫画数（状态 READY，可在漫画库查看） |
| skippedComics | 数据库已有记录，跳过的漫画数 |
| placeholderComics | 找不到 metadata 文件时创建的占位漫画数（标题为"未知漫画 {comicId}"，不参与普通列表） |
| errorComics | 处理异常的漫画数（例如 metadata 文件损坏等） |

成功恢复的漫画会立即出现在漫画库的漫画列表中。PLACEHOLDER 漫画可通过筛选状态 `PLACEHOLDER` 在管理后台查看，需要手动补全元数据后再在列表中正常显示。

#### 限制与注意事项

- **同一时刻仅允许一个恢复任务运行**。创建时若有 PENDING 或 RUNNING 状态的任务，会返回"已有恢复任务正在执行"提示。
- **不支持取消**。恢复任务创建后必须等待至终态（SUCCESS 或 FAILED），无法中途取消。
- **仅恢复有 HQ 文件的漫画**。PLACEHOLDER 漫画无封面图片和章节信息，不会出现在用户端列表中。
- **不依赖 metadata 文件就能创建 PLACEHOLDER**，但完整信息（章节、页面顺序、视频元数据等）仅能通过 metadata 恢复。
- 大量漫画时恢复流程可能耗时较长，请耐心等待。可以通过任务详情页的计数器观察进度。

#### 重试

失败的任务可以点击"重试"按钮重新执行。重试时状态从 FAILED 重置为 PENDING，`retryCount` 递增，并重新通过 MQ 下发到 Worker。

如果重试后仍然失败，请检查 Worker 日志了解具体错误原因。常见失败原因包括：
- HQ 目录不可读或权限不足。
- RabbitMQ 连接异常。
- metadata 文件格式损坏（针对部分漫画报 error，但不会阻塞其他漫画）。




## 六、管理后台工作流（v1.0）

管理后台位于 `/manage`，桌面浏览器建议宽度 768px 以上。

### 入口导航

| 菜单 | 路由 | 用途 |
|------|------|------|
| 漫画 | `/manage/comics` | 列表、搜索、筛选、批量选择、进入工作区 |
| 导入 | `/manage/import` | ZIP / 本地目录 / EHENTAI 导入 |
| 任务 | `/manage/import/tasks` | 导入任务与恢复任务 |
| 任务中心 | `/manage/tasks` | 全部管理任务（LQ/HQ/转码/回收/恢复/清理/上传） |
| 回收站 | `/manage/trash` | 软删除对象：恢复 / 永久清理 / 对账 |
| 存储管理 | `/manage/storage` | HQ/LQ 占用、章节明细、视频转码补偿 |
| 元数据 | `/manage/metadata` | 分类与标签管理 |
| 死信队列 | `/manage/dlq` | 查看与重放死信消息 |
| 设置 | `/manage/settings` | 阅读默认设置 |

### 典型工作流

1. **导入**：`/manage/import` 提交 ZIP 或目录 → 任务进入 `/manage/import/tasks`，失败可重试。
2. **校验**：任务成功后，在漫画工作区（`/manage/comics/{id}`）查看目录树、章节、媒体与允许操作。
3. **维护**：需要清理空间时使用 HQ 删除（整本或单章）；需要生成缩略图时手动触发 LQ。
4. **删除**：确认不再需要时删除进回收站；7 天后在回收站永久清理。
5. **恢复**：数据库记录丢失但 HQ 文件仍在时，任务中心“从存储恢复数据库记录”。

### 移动端说明

移动设备默认只开放阅读端；访问 `/manage` 会显示拦截提示页。管理后台请使用桌面浏览器。

## 七、常见问题

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

### 回收站里找不到刚删除的漫画

删除操作是异步任务：先确认任务中心里 `COMIC_DELETE` 任务成功，再刷新回收站列表。任务处于 `TRASHING`（进行中）或失败时，漫画不会出现在回收站列表。

### 永久清理提示“未过保留期”

永久清理要求对象处于 `TRASHED` 状态且距进入回收站超过 **7 天**。刚删除的对象需要等待保留期结束，或在回收站列表上查看剩余时间提示。

### 批量操作提示“预览条件已变化”

危险批量操作（如批量永久清理）的二次确认 token 绑定目标指纹且 5 分钟有效。期间筛选结果变化或 token 过期都会拒绝提交；重新点击“预览”后再提交即可。

### 上传大文件中断后无法续传

分块上传支持断点续传：重新创建会话并携带相同的文件清单（`sha256` 一致）时，服务端返回已接收的分块区间，客户端跳过已上传部分。若提示磁盘剩余空间不足，请清理磁盘后重试（默认要求剩余 5 GiB 或 10%）。

### 管理后台按钮灰色不可点击

按钮权限由后端统一判定，状态为过渡期（如 `IMPORTING`、`TRASHING`）或条件不满足时按钮禁用。查看对象详情中的“允许操作”与阻塞原因即可了解原因。

## 八、数据安全建议

- 定期备份 MySQL 数据库和 `MANGA_ROOT` 目录。
- 永久清理（purge）不可恢复，执行前请确认备份状态；进入回收站的对象在 7 天保留期内仍可恢复。
- 不要把 `.env`、数据库密码和远程基础设施凭据提交到 Git。
- 管理端接口默认无鉴权，仅部署在可信本机，不暴露管理端口到公网。
- 仅导入和保存合法来源的漫画内容。
