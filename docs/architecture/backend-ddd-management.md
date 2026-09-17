# 管理后端 DDD 目录与依赖规范

更新日期：2026-09-17。

本文定义 `api-service` 的目标结构。`api-service` 是管理侧限界上下文集合，负责管理 HTTP、业务写事务、任务编排和 MQ 结果落库；`reading-service` 与 `worker-service` 不得依赖管理侧内部领域对象。

## 1. 目标目录

每个管理业务域均采用以下结构；只创建实际需要的目录，不创建空层：

```text
com.comicatlas.api.<bounded-context>/
├── domain/
│   ├── model/              # 聚合、实体、值对象、领域事件、状态
│   ├── repository/         # 领域所需的持久化接口，不含 MyBatis 类型
│   ├── service/            # 无法归属单一实体的领域规则
│   └── policy/             # 可组合的领域策略
├── application/
│   ├── command/            # 写用例输入
│   ├── query/              # 查询用例输入
│   ├── service/            # 用例接口
│   │   └── impl/           # 事务、编排和状态流转实现
│   ├── assembler/          # 领域结果到应用结果的装配
│   └── port/
│       ├── in/             # 应用层入站端口
│       └── out/            # 外部系统出站端口
├── interfaces/
│   ├── rest/               # Controller、请求/响应 DTO、Web 装配
│   └── messaging/          # MQ 消费者、事件 DTO 适配、ACK/DLQ
└── infrastructure/
    ├── persistence/        # Entity、Mapper、Mapper XML、Repository 实现
    ├── messaging/          # Outbox 发布器及 MQ 技术实现
    ├── adapter/            # 文件系统、Redis、RabbitMQ 等外部适配器
    ├── config/             # 该域的 Spring 配置和属性
    └── bootstrap/          # 启动任务和定时触发器
```

## 2. 依赖方向

依赖只能由外向内，领域层不得依赖 Spring、MyBatis、RabbitMQ、Redis、HTTP DTO 或宿主机文件 API：

```text
interfaces ──┐
infrastructure ──┼──> application ──> domain
                │          │
                └──────────┴──> application.port
```

- `domain`：纯 Java 业务模型和规则；不得出现 `@Service`、`@Transactional`、Mapper、Entity、MQ 事件契约。
- `application`：实现用例和事务边界，只通过 `domain.repository` 或 `application.port.out` 访问外部能力；不得返回数据库 Entity 或 MyBatis-Plus 分页类型。
- `interfaces`：只做协议适配、校验、反序列化、ACK/DLQ 和响应转换；不得直接访问 Mapper。
- `infrastructure`：实现 repository/out port，负责 ORM、MQ、Redis、文件系统和 Spring 装配；不得承载业务状态机。
- 跨域调用必须通过应用端口、领域事件或明确的防腐层，禁止直接引用另一域的 Entity、Mapper 和 `service.impl`。

## 3. 管理域映射

| 限界上下文 | 当前代码域 | DDD 迁移重点 |
|---|---|---|
| Comic Metadata | `metadata` | Comic 聚合、标签/分类规则、元数据刷新用例 |
| Catalog | `catalog` | 目录/章节聚合与顺序规则 |
| Import | `importer` | 导入计划、最终化状态机、导入用例 |
| Management Task | `task` | 管理任务聚合、任务项状态机、命令结果应用 |
| Media | `media` | LQ/HQ/转码操作用例与媒体状态规则 |
| Storage | `storage` | 存储查询端口与路径/容量适配器 |
| Trash | `trash` | TRASHED/RESTORING/PURGING 生命周期聚合 |
| Recovery | `recovery` | 恢复计划领域服务和恢复用例 |
| Export | `exporter` | 导出任务、清单和产物发布用例 |
| Upload | `upload` | 上传会话聚合与完成用例 |
| Outbox / DLQ | `outbox` / `dlq` | 共享基础设施能力，不进入具体领域模型 |

## 4. 迁移规则

1. 先迁移一个完整用例：领域模型/规则 → 应用端口与服务 → Repository/Adapter → REST/MQ 适配器。
2. 移动包时必须同步 Java import、Mapper XML namespace、组件扫描、测试和文档；HTTP、JSON、数据库列、MQ routing key 不变。
3. Controller 和 MQ Handler 迁移后只允许调用 `application.port.in`，不得调用 `application.service.impl`。
4. `@Transactional` 只允许位于 application service 的实现；事务内不得执行下载、解压、外部进程或长时间文件 IO。
5. 领域状态转换必须集中在聚合或领域服务中；Mapper 不得决定业务状态，Worker 不得写管理数据库。
6. 旧包删除前必须通过包边界测试、对应模块测试、Checkstyle、`git diff --check` 和 `./mvnw verify`。

## 5. 当前分支交付边界

本分支以 `api-service` 为迁移对象，保留现有 API 和 MQ 契约。源码迁移按上述限界上下文逐域完成；未完成迁移的域不得伪装成 DDD，必须继续使用原目录并在本文件补充迁移状态。

当前迁移状态：

- [x] `task`：状态机、状态聚合规则、领域异常、应用服务、REST/MQ 接口、持久化与基础设施已按职责分目录；任务聚合已通过 `domain.repository` 端口访问持久化适配器，入站用例统一位于 `application.port.in`，任务重试发布位于 `application.port.out`。
- [x] `metadata`、`catalog`、`media`、`storage`、`trash`、`recovery`、`exporter`、`upload`、`library`、`settings`、`outbox`、`dlq`：已完成现有代码的领域/应用/接口/基础设施目录迁移，并已将主要写用例收敛到快照/命令端口。
- [~] `importer`：目录迁移及导入命令、重试、最终化部分端口已完成；元数据首次落库仍需继续拆分共享漫画实体访问。
- [~] `task`：目录迁移及查询模型已完成；统一任务写模型仍需继续拆分管理任务实体访问。
- [ ] 下一阶段：完成 `importer` 元数据落库和 `task` 写模型的实体隔离，并统一检查所有应用层不得返回数据库 Entity 或 MyBatis-Plus 分页类型；在此之前，本分支不宣称严格端口隔离已全部完成。
