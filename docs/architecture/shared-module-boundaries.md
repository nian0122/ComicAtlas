# 跨服务模块边界

`api-service` 与 `reading-service` 只依赖一个 `comic-shared` 模块；模块内部按职责分包，避免为本地个人项目引入额外的 Maven 模块维护成本。

| 模块 | 包前缀 | 职责 | 禁止内容 |
|---|---|---|---|
| `comic-shared` 的 contract 包 | `com.comicatlas.contract` | 请求/响应 DTO、统一结果、异常、枚举、缓存名称 | Entity、Mapper、SQL、文件访问实现、Spring Web 适配 |
| `comic-shared` 的 web 包 | `com.comicatlas.web` | Web 异常映射与 HTTP 框架适配 | 业务 DTO、Entity、Mapper、SQL |
| `comic-shared` 的 persistence 包 | `com.comicatlas.persistence` | Entity、Mapper、存储布局、类型处理器、跨服务装配器 | Controller、业务写编排、MQ 消费 |
| `api-service` | `com.comicatlas.api` | 管理 HTTP 协议、写业务编排、事务与 MQ 结果消费 | 阅读查询实现 |
| `reading-service` | `com.comicatlas.reading` | 阅读 HTTP 协议、查询服务与唯一的阅读进度写入 | 管理写操作与 MQ 消费 |

依赖方向为：`api-service` / `reading-service` → `comic-shared`。`contract` 包不得反向引用 `persistence` 包或 Spring Web；`web` 包可以依赖 contract，但不依赖业务服务。两个服务分别扫描 `com.comicatlas.web`，确保异常映射只注册一次。`comic-common` 继续只提供跨服务 MQ 事件契约和按能力归类的共享实现。

DTO 不持有 Entity 转换方法，分页 DTO 不暴露 MyBatis-Plus 类型；实体到视图对象的转换保留在服务或持久化装配器中，以保持契约层对框架和数据库的独立性。

## comic-common 共享契约业务索引

| 业务/能力 | 类型索引 | 说明 |
|---|---|---|
| importer | `DirectoryScan*Event`、`Scan*DTO`、`Import*Event` | 导入与目录扫描契约 |
| exporter | `ExportTask*Event`、`ExportFormats` | 导出任务与格式 |
| trash | `TrashManifest*DTO` | 回收清单与逐项文件元数据 |
| metadata | `MetadataRefresh*Event`、`MetadataRefreshSnapshotDTO`、`common.metadata.*` | 元数据刷新与结构版本 |
| media | `VideoMetadataFix*Event`、`common.media.image.*`、`common.media.video.*` | 媒体分析、视频修复与可播放性 |
| task | `ManagementCommand*Event`、`TaskStatusChangedEvent`、`CancelTaskEvent` | 管理命令与任务生命周期 |
| recovery | `Recovery*Event` | 磁盘恢复进度与结果 |
| MQ 基础设施 | `MqExchanges`、`MqQueues`、`MqRoutingKeys` | RabbitMQ 拓扑契约，不归业务 DTO |

普通 DTO 与常量暂保留在现有根包，只有形成稳定业务边界后再拆包。`ComicEvent` 的 `eventType` 名称由 `EventTypeNames` 集中维护；现有值、事件类全限定名、Rabbit 类型头及历史消息兼容性均视为冻结契约。
