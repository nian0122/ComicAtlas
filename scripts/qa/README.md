# QA 文件导航

| 文件 | 用途 |
| --- | --- |
| [run-management-e2e.ps1](run-management-e2e.ps1) | 管理链路端到端验收与证据生成 |
| [final-gate.ps1](final-gate.ps1) | 读取已有证据进行验收；不是全项目通用测试入口 |
| [verify-management-docs.ps1](verify-management-docs.ps1) | 管理接口、枚举、MQ 与文档对照 |
| [verify-frp-port-detection.ps1](verify-frp-port-detection.ps1) | FRP 端口探测验证 |
| [docker-compose.qa.yml](docker-compose.qa.yml) | QA 服务编排 |
| [nginx-e2e.conf](nginx-e2e.conf) | E2E 文件服务配置 |
| [init-qa.sql](init-qa.sql) | QA 数据库初始化 |

各入口的参数和依赖见脚本头部。证据、日志和测试报告不进入提交。浏览器测试套件的区别见 [测试导航](../../e2e/README.md)。
