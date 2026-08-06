# db 目录说明

- `flyway/` — **生效迁移目录**（`application.yml` `spring.flyway.locations: classpath:db/flyway`），仅此目录参与 Flyway 版本管理。
- `migration-archive/` — 历史遗留迁移（V2-V12，含与 flyway 重名的 V10/V11/V12）。已核实无任何引用，仅归档保留供审计；不参与 Flyway 执行。归档前核对：flyway 目录 V10/V12 与 archive 内容一致，V11 与 archive 有差异（index/constraint 命名不同），V2 语义不同（`fix_schema_drift` vs `core_architecture`）——以 `flyway/` 为准。
- `schema.sql` — 测试容器初始化 schema（`docker-compose.test.yml` 挂载）。
