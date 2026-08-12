# 跨服务模块边界

`api-service` 与 `reading-service` 共享的数据模型已按职责拆分，避免一个模块同时承载 Web 契约、持久化实现和业务服务。

| 模块 | 包前缀 | 职责 | 禁止内容 |
|---|---|---|---|
| `comic-contract` | `com.comicatlas.contract` | 请求/响应 DTO、统一结果、异常、枚举、缓存名称 | Entity、Mapper、SQL、文件访问实现 |
| `comic-persistence` | `com.comicatlas.persistence` | Entity、Mapper、存储布局、类型处理器、跨服务装配器 | Controller、业务写编排、MQ 消费 |
| `api-service` | `com.comicatlas.api` | 管理 HTTP 协议、写业务编排、事务与 MQ 结果消费 | 阅读查询实现 |
| `reading-service` | `com.comicatlas.reading` | 阅读 HTTP 协议、查询服务与唯一的阅读进度写入 | 管理写操作与 MQ 消费 |

依赖方向为：`api-service` / `reading-service` → `comic-persistence` → `comic-contract`。两个服务均可直接依赖契约模块；契约模块不得反向引用持久化实现。`comic-common` 继续只提供 MQ 事件契约和通用工具。

DTO 不持有 Entity 转换方法，分页 DTO 不暴露 MyBatis-Plus 类型；实体到视图对象的转换保留在服务或持久化装配器中，以保持契约层对框架和数据库的独立性。
