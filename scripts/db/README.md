# 数据库维护

管理服务启动时执行 Flyway 迁移，无需用户运行开发或 QA 初始化脚本。

- [迁移说明](../../api-service/src/main/resources/db/README.md)
- [本机数据库账号、备份与恢复](../../docs/operations/management.md)

数据库通过 docker compose exec mysql 管理；升级前备份数据库和漫画存储，禁止用 down -v 清理持久化数据。
