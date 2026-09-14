# 工具导航

| 入口 | 用途 |
| --- | --- |
| [maintenance/manage-remote-infra-frp.ps1](maintenance/manage-remote-infra-frp.ps1) | 管理远端基础设施 FRP 连接 |
| [maintenance/start-remote-infra-tunnel.ps1](maintenance/start-remote-infra-tunnel.ps1) | 启动远端基础设施隧道 |
| [maintenance/backup-remote-mysql.ps1](maintenance/backup-remote-mysql.ps1) | 远端 MySQL 备份 |
| [vendor/README.md](vendor/README.md) | 第三方工具目录说明 |

Worker 使用的运行工具放在 `worker-service/tools/`，其中 [image-optimizer](../worker-service/tools/image-optimizer/README.md) 含构建与使用说明。FRP 部署说明见 [基础设施连接](../docs/operations/frp-infrastructure.md)。

开发与验收入口统一从 [scripts](../scripts/README.md) 查找，数据库版本迁移由管理服务的 Flyway 目录维护。
