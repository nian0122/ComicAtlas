# ComicAtlas - Maven 测试运行辅助脚本（注入 .env 环境变量）
# 用法: pwsh -NoProfile -File scripts/dev/run-tests.ps1 [-pl <模块>] [test] [-Dtest=<测试类>] [-DfailIfNoTests=false]
# 示例: .\scripts\dev\run-tests.ps1 -pl api-service test -Dtest=MetadataRefreshServiceTest -DfailIfNoTests=false
# 说明: @SpringBootTest 集成测试（如 EntitySchemaContractTest）依赖 REDIS_*/RABBITMQ_*/NACOS_*/MYSQL_*
#       环境变量（占位符无默认值），这些变量由本脚本从 .env 注入当前进程，避免占位符解析失败。

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs
)

if ($MavenArgs.Count -eq 0) {
    Write-Host "用法: $($MyInvocation.MyCommand.Name) -pl <模块> test [-Dtest=<测试类>] ..." -ForegroundColor Yellow
    Write-Host "示例: .\scripts\dev\run-tests.ps1 -pl api-service test -Dtest=MetadataRefreshServiceTest" -ForegroundColor Yellow
    exit 1
}

# 仓库根目录 = 脚本所在 scripts/dev/ 向上两级
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
# 基础设施经 FRP visitor 从 localhost 访问远端；与开发启动共用映射。
. (Join-Path $PSScriptRoot 'load-infrastructure-env.ps1')
Import-InfrastructureEnvironment -RepositoryRoot $repoRoot

Write-Host "=== 环境变量注入完成（FRP visitor localhost 访问远端基础设施）===" -ForegroundColor DarkGray
Write-Host "    MYSQL=$env:MYSQL_HOST`:$env:MYSQL_PORT REDIS=$env:REDIS_HOST`:$env:REDIS_PORT" -ForegroundColor DarkGray

# API 的跨服务集成测试（MediaUploadManagementIT / TrashLifecycleIT 等）
# 需要显式启用 Worker 测试依赖；该属性只在 api-service profile 中生效。
$MavenArgs += "-Dwith-worker-integration-tests=true"

# 透传参数执行 Maven 测试（当前进程退出码透传）
& (Join-Path $repoRoot "mvnw.cmd") @MavenArgs
exit $LASTEXITCODE
