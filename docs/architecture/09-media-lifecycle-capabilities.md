# 媒体处理与生命周期总览

**适用版本**：2.1 及后续开发
**最后更新**：2026-09-04
**状态**：现行

本文把导入、媒体维护、导出、回收站和恢复放在同一张架构图里，作为实现与排障时的边界速查。接口细节以 [`docs/api.md`](../api.md) 为准，数据库字段以 [`database/schema.md`](../database/schema.md) 为准。

## 服务边界

| 服务 | 负责 | 不负责 |
|------|------|--------|
| `reading-service` | 漫画查询、目录、章节阅读、阅读历史 | 管理任务、MQ 消费、文件搬运 |
| `api-service` | 管理 HTTP、事务、数据库写入、Outbox/Inbox、MQ 结果消费 | 解析压缩包、执行外部文件工具 |
| `worker-service` | 目录/压缩包解析、媒体分析、文件移动、LQ/转码/导出 | 写业务数据库、提供业务 HTTP |
| Gateway/Nginx | API 路由与受控文件读取 | 业务编排、媒体状态写入 |

固定约束：管理端路径为 `/api/manage/**`，阅读端路径为 `/api/**`；Worker 只读 MySQL，所有业务状态由 API 根据事件落库；文件字节不经过 HTTP。

## 任务通用链路

```text
管理 API 事务
  → 写入 management_task / task_item / outbox_message
  → Outbox 发布 RabbitMQ 命令
  → Worker 执行文件或外部工具操作
  → Worker 发布完成/失败/进度事件
  → API Inbox 幂等消费
  → 更新任务项、媒体状态和派生统计
```

任务项使用目标 ID 与 attempt 做幂等边界。文件产物先写临时位置，验证成功后再发布；取消、超时和中断必须清理临时文件并回收外部进程。主队列的基础设施失败进入既定重试/DLQ，业务失败则发布明确失败结果并确认消息。

## 导入

支持的 `sourceType` 为 `ZIP`、`CBZ`、`DIRECTORY` 和后端保留的 `EHENTAI`。前端当前提供 ZIP、CBZ 和本地目录入口。

```text
API 创建 comic(IMPORTING) + import_task(PENDING)
  → Worker 解析目录或解压 ZIP/CBZ
  → 解析可选 ComicInfo.xml，分析图片尺寸/视频元数据
  → 文件暂存到 HQ/{comicId}/{globalOrder}
  → 写 metadata.json 并发送导入完成事件
  → API 写入 catalog/chapter/page，取得 chapterId
  → Worker 逐章移动到 HQ/{comicId}/{chapterId}
  → API 确认全部章节 READY，comic → READY、task → SUCCESS
```

标准分卷的唯一入口是同 basename 的最后一个 `.zip` 或 `.cbz`；`.z01` 等分卷不能作为入口，缺卷时导入失败。CBZ 中的 `ComicInfo.xml` 缺失不阻断导入；存在时用于补充标题、作者、描述、发行信息、分类和标签。

## 媒体维护

- **LQ 生成/重生成**：只处理 HQ 可用的图片；生成 `lq/{comicId}/{chapterId}/...` 后由 API 回写 LQ 状态和大小。视频不生成 LQ。
- **视频转码**：只处理被标记为需要转码的视频。Worker 用 ffmpeg 生成临时产物并用 ffprobe 验证，成功后 API 更新视频元数据和状态。
- **刷新元数据**：按指定 `comicId` 逐章扫描 HQ，与数据库已有媒体按规范化路径匹配。数据库有记录但文件缺失时标记 `HQ MISSING`；磁盘新增文件只作为“已发现”结果，不自动创建媒体行。
- **统计**：HQ/LQ 大小、章节页数和总页数由 API 从媒体/章节记录重算，不能以旧的单一 `file_size` 字段作为事实来源。

## 导出、回收与恢复

导出由 `comicId` 创建异步任务，Worker 校验媒体完整性后生成 ZIP 或 CBZ。大文件使用标准分卷，主卷是最后的 `.zip` 或 `.cbz`；CBZ 额外写入 `ComicInfo.xml`。产物只保存于宿主机 `MANGA_ROOT/export/{taskId}/`，接口返回任务和卷元数据，不提供 HTTP 字节下载。

删除语义是“移入回收站”而不是直接硬删：文件按不可变 Trash Manifest 移入 `trash` 卷，DB 生命周期变为 `TRASHED`。恢复按清单移回原位置；永久清理只接受已 `TRASHED` 且达到 `trash.retention-days` 配置保留期的对象（默认 `0`，表示不等待），并要求二次确认 token。

数据库恢复是显式的灾难恢复流程：Worker 扫描 HQ 和 metadata 索引，API 校验快照后重建缺失的业务记录。普通“刷新元数据”不会枚举整库，也不会因发现磁盘新文件而自动插入媒体。

## 相关文档

- [系统全景](01-system-overview.md)
- [导入流水线](02-import-pipeline.md)
- [存储模型](03-storage.md)
- [API 文档](../api.md)
- [数据库 Schema](../database/schema.md)
