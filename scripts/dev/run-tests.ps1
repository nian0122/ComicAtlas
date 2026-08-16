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
$envFile = Join-Path $repoRoot ".env"
if (-not (Test-Path -LiteralPath $envFile)) {
    Write-Host "ERROR: 未找到 .env（$envFile），无法注入基础设施连接变量" -ForegroundColor Red
    exit 1
}

# 解析 .env（仅读取 KEY=VALUE 行，忽略注释）
$envValues = @{}
Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $parts = $line -split '=', 2
        $envValues[$parts[0]] = $parts[1]
    }
}

function Get-EnvValue([string]$key) {
    if (-not $envValues.ContainsKey($key)) {
        Write-Host "WARN: .env 缺少 $key，对应占位符可能无法解析" -ForegroundColor Yellow
        return ""
    }
    return $envValues[$key]
}

# 注入测试所需变量（映射与 start-dev.ps1 / docker-compose.yml 保持一致：
# 基础设施经 FRP 隧道从 localhost 访问远端）
$env:REDIS_HOST = "localhost"
$env:REDIS_PORT = Get-EnvValue "REMOTE_REDIS_PORT"
$env:REDIS_PASS = Get-EnvValue "REMOTE_REDIS_PASSWORD"
$env:RABBITMQ_HOST = "localhost"
$env:RABBITMQ_PORT = Get-EnvValue "REMOTE_RABBITMQ_PORT"
$env:RABBITMQ_USER = Get-EnvValue "REMOTE_RABBITMQ_USER"
$env:RABBITMQ_PASS = Get-EnvValue "REMOTE_RABBITMQ_PASSWORD"
$env:RABBITMQ_MANAGEMENT_PORT = Get-EnvValue "REMOTE_RABBITMQ_MANAGEMENT_PORT"
$env:NACOS_ADDR = "localhost:" + (Get-EnvValue "REMOTE_NACOS_HTTP_PORT")
$env:NACOS_USER = Get-EnvValue "REMOTE_NACOS_USER"
$env:NACOS_PASS = Get-EnvValue "REMOTE_NACOS_PASSWORD"
$env:MYSQL_HOST = "localhost"
$env:MYSQL_PORT = Get-EnvValue "REMOTE_MYSQL_PORT"
$env:MYSQL_USER = Get-EnvValue "WORKER_MYSQL_USER"
$env:MYSQL_PASS = Get-EnvValue "WORKER_MYSQL_PASSWORD"

Write-Host "=== 环境变量注入完成（FRP 隧道 localhost 直连远端基础设施）===" -ForegroundColor DarkGray
Write-Host "    MYSQL=$env:MYSQL_HOST`:$env:MYSQL_PORT REDIS=$env:REDIS_HOST`:$env:REDIS_PORT" -ForegroundColor DarkGray

# 透传参数执行 Maven 测试（当前进程退出码透传）
& (Join-Path $repoRoot "mvnw.cmd") @MavenArgs
exit $LASTEXITCODE
