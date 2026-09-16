# 后端代码分类

更新日期：2026-09-16。

后续分类待办见 [后端文件分类 TODO](backend-classification-todo.md)。该清单仅标记待调整归属，不执行代码迁移；下文“本次归类”和验证记录描述此前已经完成的整理。

采用 **Maven 模块划分运行边界，Java 包先按业务、再按框架职责分类**。查找恢复功能时，先进入 `recovery`，再找 `controller`、`service`、`event` 或 `persistence`。目录迁移不等于职责已经解耦；待拆分代码见 [解耦清单](backend-decoupling.md)。

## 模块边界

| 模块 | 职责 | 组织方式 |
|---|---|---|
| api-service | 管理 HTTP、业务写事务、任务编排、结果消费 | 业务域 + Spring 分层 |
| reading-service | 漫画查询、目录、阅读、阅读历史 | library / catalog / reader / history + controller / service / dto |
| worker-service | 文件处理、下载、外部进程、命令执行 | 业务域 + event / command / handler / parser 等执行职责 |
| comic-shared | API 与 Reading 共享的 HTTP 契约和持久化模型 | contract 与 persistence，遵循已有单向依赖 |
| comic-common | 跨服务 MQ 契约、元数据模型和通用能力 | event / constant / metadata / mq 等既有契约包 |
| gateway | 路由与服务发现 | 启动类、框架配置 |

共享事件类的包名可能用于 MQ 类型头，故本次保持 `comic-common` 事件全限定名。数据库表名、HTTP 路径、JSON 字段和 MQ 路由键保持既有契约。

## 管理服务业务域

| 包 | 文件归属 |
|---|---|
| library | 管理漫画列表与详情查询 |
| metadata | 漫画属性、分类、标签及元数据更新协调 |
| catalog | 目录和章节结构管理、目录缓存失效 |
| importer | 导入、导入重试、最终化落库、导入前目录扫描 |
| exporter | 导出任务、导出产物及导出结果消费 |
| recovery | 从存储元数据恢复数据库、恢复任务、兼容恢复 |
| trash | 回收、恢复回收内容、永久清理、回收清单和对账 |
| media | LQ/HQ/转码等媒体操作编排、结果应用 |
| storage | 存储统计、空间查询、根目录和路径布局抽象 |
| task | 通用任务/任务项、状态机、聚合、操作策略；batch 为批量操作子域 |
| upload | 分块上传会话、上传存储、上传完成处理及媒体编辑预留能力 |
| outbox | 可靠消息发件箱、收件幂等、relay、清理、统计接口 |
| dlq | 死信管理与该功能的安全配置 |
| settings | 应用设置 |
| config | 应用级框架装配：MyBatis、Redis、RabbitMQ、CORS 等 |
| shared | 本服务跨业务使用的监控、摘要计算和异常 |

`recovery` 表示从磁盘重建数据库，`trash` 表示已入库内容的回收生命周期。目录扫描用于发现导入候选，因此 API 与 Worker 均归 `importer`。

## 框架职责

```text
com.comicatlas.api.<业务域>
├── controller          HTTP 适配、参数校验
├── dto                 请求、响应、查询条件
├── service             业务编排、事务边界、领域服务
│   └── impl            已有服务接口的实现
├── persistence
│   ├── entity          MyBatis 实体
│   ├── mapper          Mapper 接口
│   └── handler         该业务的数据库类型处理器
├── event               MQ 接收、结果适配
├── config              该业务专属 Spring 配置及配置属性
├── enums / domain      状态枚举、领域值对象和规则
└── exception           该业务的异常
```

按需要创建子包，不为每个业务预建空层。已有明确用途的 `engine`、`policy`、`cache`、`relay` 等包继续保留。`recovery.engine` 中的解析辅助 record 与引擎同组，避免仅为搬目录扩大包内类型的可见性。存储路径抽象是内聚组件，可继续在 `storage` 根包。

Worker 使用同样的业务域优先原则：`importer`、`exporter`、`recovery`、`trash`、`media`、`task`、`storage`。其数据库访问集中在只读 `persistence.mapper` / `persistence.record`，不套用 API 的写实体模型。`shared.archive`、`shared.process` 等复用能力保持独立。

## 本次归类

- 将恢复任务的 Controller、DTO、Service、Entity、Mapper、事件处理器从 importer 迁入 recovery。
- 将回收站 Controller、Service、DTO 和清单实体从 recovery.trash 拆分到独立 trash 域；从 task 迁入回收 Mapper 和响应 DTO。
- 将 API 各域散落的 entity / mapper 统一到 persistence.entity / persistence.mapper，并更新 XML namespace 和扫描配置。
- 将媒体操作入口与服务分别归入 media.controller / media.service；HQ 删除异常归 media.exception。
- 将 Outbox 统计入口归 outbox.controller；批量 DTO、异常、原因枚举归 task.batch 对应职责包。
- 将上传配置、上传类型处理器、死信安全配置归各自 config / persistence.handler。
- 将 Worker 导入候选扫描归 importer，回收执行器和清单读取归 trash。
- 将阅读列表查询规范化组件归 library.support；同步迁移相关测试及引用。

## 维护与验证

新增文件先确定业务所有者，再确定框架角色。只有多个业务实际复用的类型才进入 shared；只有跨服务契约才进入 comic-common。不要因多个类都叫 Service 就集中到顶层 service。

API 的 `ApiPackageBoundaryTest` 检查 Controller/Mapper/Entity 的包归属、Controller 不依赖 Mapper，以及实际 MyBatis 扫描配置能发现每个业务 Mapper。包迁移后必须清理构建输出，避免旧 class 掩盖扫描问题：

```powershell
pwsh -NoProfile -File scripts/dev/run-tests.ps1 clean verify
git diff --check
```

测试中的 `*IT` 是否执行取决于 Maven 配置；默认 verify 不代表已执行所有真实基础设施集成场景。HTTP 和 MQ 行为修改仍须运行对应真实链路测试。

### 本次验证记录

2026-09-16 执行上述 `clean verify` 成功：共 830 项测试，824 项通过、6 项跳过，失败与错误均为 0。各模块 Checkstyle 均为 0 违规；语义命名测试及 4 项 API 包边界测试通过，`git diff --check` 通过。

额外检查确认：57 个生产类型迁移目标齐全，Java/XML/配置中无旧全限定名残留；忽略包声明、import 和新增解耦注释后，迁移类的方法内容与迁移前一致。此验证记录不代表历史代码已经完全符合全部命名和架构规范。
