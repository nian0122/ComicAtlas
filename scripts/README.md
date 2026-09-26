# 脚本导航

以下路径均相对于仓库根目录。脚本按运行场景归类，直接使用已有入口。

| 目录或入口 | 用途 |
| --- | --- |
| [dev/start-dev.ps1](dev/start-dev.ps1) | 启动本地开发环境 |
| [dev/run-tests.ps1](dev/run-tests.ps1) | 从本地环境配置注入连接变量并执行 Maven 测试 |
| [QA 目录](qa/README.md) | 管理链路验收、证据检查及 QA 配置 |
| [数据库说明](db/README.md) | 数据库工具与迁移位置 |
| [发布门禁](release/README.md) | 正式发布树校验 |

后端测试示例：

```powershell
pwsh -NoProfile -File scripts/dev/run-tests.ps1 -pl api-service -am test
```

运行维护工具位于 [tools/maintenance](../tools/README.md)。开发与 QA 脚本保留在 `develop`，发布树边界见 [项目目录维护约定](../docs/development/project-layout.md)。
