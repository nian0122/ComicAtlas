# 后端架构决策

更新日期：2026-09-16。

## 强制分层架构

后端业务代码必须按业务域和框架职责分层，禁止将接口契约、业务实现和数据库访问混在同一类中。

```text
Controller / MQ Handler
        ↓
Service 接口（业务功能契约）
        ↓
ServiceImpl（业务编排、事务、状态机）
        ↓
Mapper（持久化方法与 SQL）
        ↓
Entity / Database
```

### Service 规则

- `service` 包只放 Service 接口、业务功能契约和必要的领域服务抽象。
- 具体 Spring 实现必须放在 `service.impl` 包，并命名为 `*ServiceImpl`。
- 每个对外或跨业务调用的 Service 功能必须有接口；不得用一个具体 `@Service` 类同时承担功能注册和实现。
- ServiceImpl 负责业务校验、状态机、事务边界和跨 Mapper 编排，但不应直接拼装持久化条件。
- Service 接口不得暴露 Entity、MyBatis-Plus `IPage` 或其他持久化框架类型；使用领域 DTO/分页 DTO。

### Controller 与 MQ Handler 规则

- Controller 只负责协议适配、参数校验和响应转换，不得直接依赖 Mapper。
- MQ Handler 只负责消息反序列化、ACK/DLQ 和调用 Service，不得直接执行业务状态变更或数据库写入。

## Mapper 持久化边界

Mapper 是数据库访问的唯一边界。Mapper 接口统一位于 `persistence.mapper`，对应 SQL 方法和 XML 位于 Mapper 层；Mapper 不负责业务状态机、跨表流程或 DTO 业务组装。

### LambdaWrapper 迁移规则

ServiceImpl 中直接使用 `LambdaQueryWrapper` 或 `LambdaUpdateWrapper` 访问 Mapper 的代码视为待整改项，必须记录对应的 MAPPER-02 编号。

- 简单单表查询可在后续整理时迁移到 Mapper 查询方法。
- 条件更新、批量更新、CAS 更新、状态流转和跨表删除必须优先迁移到具名 Mapper 方法。
- Mapper 方法可以在接口上使用 MyBatis-Plus 条件参数、注解 SQL 或 XML 实现；调用方不应感知 SQL 细节。
- 迁移时必须保留原有事务边界、乐观锁、受影响行数检查和状态转换语义。

`@Mapper` 和 `@MapperScan` 只是 Bean 注册机制，不因 SQL 使用 Lambda 而删除；其扫描范围另以 MAPPER-01 编号跟踪评估。

## 现有整改标记

- `LAYER-14`：Service 功能接口与具体实现尚未分离。
- `MAPPER-01`：Mapper Bean 扫描范围待统一评估。
- `MAPPER-02`：ServiceImpl/Service 中直接构造 Lambda 更新条件，待迁移到 Mapper。
- `LAYER-05/06`：MQ 入口仍包含导入/恢复业务编排或持久化。

新增或修改后端代码必须遵守上述分层；完成整改后删除对应待办标记，并补充对应模块测试、Checkstyle 和 `git diff --check` 验证记录。
