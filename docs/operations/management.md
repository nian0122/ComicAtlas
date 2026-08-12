# ComicAtlas 部署运维手册

**更新日期：** 2026-08-12
**状态：** 生效
**维护者：** ComicAtlas 运维组

> 适用版本：v1.5。配套文档：[用户指南](../user-guide.md)、[API 文档](../api.md)。所有命令示例均可在 `scripts/qa/verify-management-docs.ps1` 中校验。

本手册覆盖管理控制台（回收站、批量操作、媒体上传、任务中心）上线后所需的运维知识：数据库账号、存储卷、保留期、磁盘阈值、备份、升级与回滚。

---

## 一、数据库账号

ComicAtlas 分为 API 与 Worker 两个进程，二者对 MySQL 的权限不同，**不要混用同一个写账号**。

| 账号 | 权限 | 用途 | 强制措施 |
|------|------|------|---------|
| API 写账号 | 全部 DDL/DML（库级） | API 服务读写业务表、outbox/inbox、管理任务 | 由 Flyway 执行迁移，`GRANT ALL` |
| Worker 只读账号（`comicatlas_ro`，生产默认） | 仅 `SELECT` | Worker 文件处理侧只读查询（导出、扫描、转码状态读取） | HikariCP `read-only=true` + `GRANT SELECT` 双层兜底 |

仓库级 `.env` 按服务角色命名：API 使用 `API_MYSQL_USER` / `API_MYSQL_PASSWORD`，Worker 使用 `WORKER_MYSQL_USER` / `WORKER_MYSQL_PASSWORD`。Docker Compose 和开发启动脚本只在启动具体 JVM 时，将对应账号映射为 Spring 通用变量 `MYSQL_USER` / `MYSQL_PASS`，避免两个进程误用同一账号。

### 最小授权示例（MySQL 8）

```sql
-- API 写账号（用于 api-service 的 MYSQL_USER / MYSQL_PASS）
CREATE USER IF NOT EXISTS 'comicatlas_api'@'%' IDENTIFIED BY '请设置强密码';
GRANT ALL PRIVILEGES ON comic_atlas.* TO 'comicatlas_api'@'%';

-- Worker 只读账号（worker-service 生产默认值 comicatlas_ro，用于其 MYSQL_USER / MYSQL_PASS）
CREATE USER IF NOT EXISTS 'comicatlas_ro'@'%' IDENTIFIED BY '请设置强密码';
GRANT SELECT ON comic_atlas.* TO 'comicatlas_ro'@'%';
FLUSH PRIVILEGES;
```

Worker 侧生产默认配置已把 `spring.datasource.hikari.read-only` 设为 `true`（`worker-service/src/main/resources/application.yml`），并有配置契约测试防止回退。Worker 写数据库被拒绝属于预期行为：所有状态回写通过 MQ 事件由 API 完成。

Worker 只读账号的密码没有固定默认值，必须在仓库 `.env` 中通过 `WORKER_MYSQL_PASSWORD` 显式提供；启动 Worker 时再映射为进程级 `MYSQL_PASS`。未设置时 Worker 启动即失败，避免误用默认凭据连接数据库。

> 本手册不包含任何真实凭据，仅示范账号结构与授权方式。

---

## 二、存储卷与目录

### 目录清单

| 目录 | 读写方 | 是否对外暴露 | 说明 |
|------|--------|-------------|------|
| `{MANGA_ROOT}/hq/` | Worker 写，Nginx 只读 | 是（`/files/hq`） | 高清原图，60d 缓存 |
| `{MANGA_ROOT}/lq/` | Worker 写，Nginx 只读 | 是（`/files/lq`） | LQ 缩略图，30d 缓存 |
| `{MANGA_ROOT}/thumbs/` | Worker 写，Nginx 只读 | 是（`/files/thumbs`） | 封面缩略图，7d 缓存 |
| `{MANGA_ROOT}/metadata/` | Worker 写 | 否 | 每本漫画的 `metadata/{comicId}.json` |
| `{MANGA_ROOT}/staging/` | API 写（上传），Worker 读 | **否** | 上传临时文件，不可下载 |
| `{MANGA_ROOT}/trash/` | API + Worker 写 | **否** | 回收站文件卷（软删除移入） |
| `{MANGA_ROOT}/export/` | Worker 写 | 否 | 导出产物 |

> `staging` 为上传临时目录，不经 Nginx 暴露，避免未完成上传被公网访问；`trash` 为回收站文件卷，存放软删除文件，配合 7 天保留期。

### 磁盘布局建议

- `hq`、`lq`、`trash` 建议与系统盘分离，使用独立数据卷，避免日志/系统盘写满影响漫画存储。
- 迁移存储时优先修改 `MANGA_ROOT` 或 `storage.roots.*.path` 配置，不要手动改写数据库里的相对路径。
- 上传与回收都涉及同卷文件移动（`sameFileStore()` 校验），`staging`、`trash`、`hq` 建议放在同一文件系统内，避免跨卷 move 失败。

---

## 三、保留期与磁盘阈值

### 回收站保留期

- 对象进入回收站后生命周期为 `TRASHED`。
- **永久清理前置条件**：处于 `TRASHED`、距 `trashed_at` 超过 **7 天**（`TrashLifecycleService.RETENTION_DAYS = 7`）、二次确认 token。
- 恢复（restore）不受保留期限制，保留期内可随时恢复。

### 上传磁盘阈值

上传新分块前检查磁盘剩余空间，低于阈值拒绝写入：

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `storage.upload.free-space-min-bytes` | 5 GiB | 剩余字节数下限 |
| `storage.upload.free-space-min-ratio` | 0.10 | 剩余比例下限 |

建议通过磁盘监控（宿主层面）在达到阈值前预警，避免上传任务大面积失败。

### Outbox 清理保留期（`outbox.cleanup.*`）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `published-retention-days` | 30 | 已发布 outbox 消息保留天数 |
| `processed-retention-days` | 30 | 已消费 inbox 记录保留天数 |
| `failed-retention-days` | 90 | 失败消息保留天数 |
| `task-retention-days` | 90 | 历史管理任务保留天数 |

---

## 四、备份

### 备份内容

1. **MySQL 数据库**：`comic_atlas` 全库（含 `outbox_message`、`inbox_receipt`、`management_task`）。
2. **存储卷**：`hq`、`lq`、`thumbs`、`metadata` 目录。
3. **配置**：`.env`（妥善保管，不提交 Git）。

### 备份命令示例

```bash
# 数据库备份（需停写入任务或使用一致性快照）
docker exec comicatlas-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction comic_atlas' > comic_atlas_$(date +%F).sql

# 存储卷备份（示例：rsync 到备份目录；需在目标主机安装 rsync）
rsync -a --delete /data/manga/hq /data/backup/hq
rsync -a --delete /data/manga/lq /data/backup/lq
rsync -a --delete /data/manga/thumbs /data/backup/thumbs
rsync -a --delete /data/manga/metadata /data/backup/metadata
```

> 备份前确认当前无进行中的永久清理（`PURGING`）与删除任务，或备份后立即做一次恢复演练。回收站文件属于软删除对象，`trash` 目录是否备份取决于你是否希望保留已删除内容，建议纳入备份。

### 恢复演练建议

恢复 = 还原数据库 dump + 还原存储卷。数据库与文件必须**同一时间点**对齐，否则会出现文件存在但 DB 无记录（可用恢复任务重建）或 DB 有记录但文件缺失（状态 `MISSING`）的情况。

---

## 五、升级

### 升级前

1. 阅读 [当前发布说明](../releases/v1.5.0.md) 与历史发布说明中的版本迁移信息。
2. 备份数据库与存储卷（见上文）。
3. 确认 Worker、API 无进行中的任务，或接受任务中断由 DLQ/Outbox 补偿。

### 升级步骤

```bash
# 1. 拉取新版本
git pull origin main

# 2. 重新构建并滚动重启（Flyway 自动执行 V10+ 迁移）
docker compose -f docker-compose.yml up -d --build

# 3. 观察迁移与健康状态
docker compose -f docker-compose.yml ps
docker compose logs -f api-service
```

Flyway 会按版本号顺序执行 `api-service/src/main/resources/db/flyway/V*.sql`（生效迁移目录，见 `db/README.md`）。当前生效迁移：V1 初始化、V2 修正 schema 漂移、V10 生命周期/乐观锁、V11 管理任务、V12 管理任务外键、V13 outbox/inbox、V14 章节全局顺序唯一、V15 上传会话、V16 回收站生命周期、V17 REGISTER→DIRECTORY、V18 视频转码状态分类、V19 Outbox/阅读历史完整性、V20 TRASH 资产清单落库；V3–V9 等历史迁移已归档到 `db/migration-archive/`，不参与执行。迁移失败时 Flyway 会停在失败版本，需要修复后重试。

### 升级后的检查清单

- [ ] `GET /api/management/outbox/stats` 的 `pending`/`failed` 不为持续增长。
- [ ] 任务中心能查询到管理任务（`GET /api/management/tasks`）。
- [ ] 回收站页面能列出 `TRASHED` 对象。
- [ ] 旧客户端仍可调用兼容端点（见 API 文档第 19 章兼容窗口）。

### Broker 遗留 MQ 实体清理（可选）

代码已不再声明旧完整删除（`comic.delete`）的 exchange/queue/DLQ（`delete.task.queue` / `delete.result.queue` / `comic.delete.dlx` 等），升级部署后这些 durable 实体仍会残留在已运行的 RabbitMQ Broker 中，且不会被 Spring 自动删除。如需清理：

1. 在停服窗口确认对应队列无积压消息（RabbitMQ 管理台或 `rabbitmqctl list_queues`）。
2. 手动删除残留 exchange/queue（`rabbitmqctl delete_queue delete.task.queue` 等）。
3. 残留实体不影响新拓扑运行，本计划不执行 Broker 删除；不清理也不影响功能。

---

## 六、回滚

管理端新能力（回收站、任务中心、批量、上传）涉及数据库迁移与删除语义变更，**不建议直接回退代码版本**。若必须回滚：

1. **数据回滚**：用升级前的备份还原 MySQL 与存储卷（同一时间点）。
2. **代码回滚**：

```bash
git checkout <上一个稳定 tag>
docker compose -f docker-compose.yml up -d --build
```

3. **注意事项**：
   - 回滚后 `outbox_message` / `inbox_receipt` / `management_task` 表由还原的备份决定；若保留新表而代码退回旧版，旧版会忽略这些表，不影响旧功能。
   - 升级期间产生的新数据（新导入、回收站对象）在回滚到旧备份后会丢失，务必确认可接受。
   - 永久清理（purge）**不可恢复**：一旦执行，文件与 DB 记录均被删除，备份是唯一恢复手段。

---

## 七、故障索引

| 症状 | 可能原因 | 排查/处理 |
|------|---------|----------|
| 上传分块返回 500 | 磁盘剩余空间低于阈值（`free-space-min-*`） | 清理磁盘；确认 `staging` 卷可写 |
| 上传会话无法完成 | 文件清单 `size` 与 `Content-Range` 的 total 不一致 | 核对前端清单与分块头；重新创建会话 |
| 永久清理被拒 | 对象未到 7 天保留期 / 非 `TRASHED` / token 失效 | 等待保留期；重新预览确认 |
| 回收站列表空白 | `COMIC_DELETE` 任务失败或仍在 `TRASHING` | 查看任务中心任务与逐项错误 |
| Worker 写库报 Access denied | Worker 账号只读（预期行为） | 确认状态回写走 MQ；不要给 Worker 放开写权限 |
| 任务状态一直 `QUEUED` | Outbox Relay 未运行或 MQ 断开 | 检查 `outbox.relay.*` 与 RabbitMQ 连通性、`/api/management/outbox/stats` 积压 |
| DLQ 堆积 | 消费方异常或事件 payload 不兼容 | 管理后台“死信队列”查看/重放，或 `POST /api/admin/dlq/queues/{q}/replay` |
| 磁盘不足 | 上传阈值/保留期设置过低 | 调整 `free-space-min-*`，清理回收站（purge）与导出目录 |

---

## 八、安全基线

- 管理端接口默认无鉴权：只部署在可信本机，管理端口不暴露公网。
- RabbitMQ 管理台（15672）、Nacos（8848）只绑定回环地址。
- 不要把 `.env`、数据库密码、远程凭据提交到 Git 或写入文档。
- 定期轮换 `MYSQL_ROOT_PASSWORD` 等基础设施密码。
