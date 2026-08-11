# ComicAtlas - 开发环境启动
Write-Host "=== ComicAtlas 开发环境 ===" -ForegroundColor Cyan

# 仓库根目录 = 本脚本（scripts/dev/）的两级上级
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# 1. 启动 FRP visitor（连接远端中间件）
$frpScript = Join-Path $repoRoot "tools\maintenance\manage-remote-infra-frp.ps1"
if (Test-Path $frpScript) {
    Write-Host "正在建立远端中间件 FRP 连接..." -ForegroundColor DarkGray
    & $frpScript -Action Start
    if ($LASTEXITCODE -ne 0) {
        Write-Host "WARN: FRP 启动失败，Worker 可能无法连接 RabbitMQ/Nacos" -ForegroundColor Yellow
    }
} else {
    Write-Host "WARN: 未找到 FRP 脚本 tools\maintenance\manage-remote-infra-frp.ps1" -ForegroundColor Yellow
}

# 2. 从 .env 加载远端中间件凭证
$envFile = Join-Path $repoRoot ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -match '^\s*([^#].+?)\s*=\s*(.+)$' } | ForEach-Object {
        $key, $val = $Matches[1], $Matches[2]
        [Environment]::SetEnvironmentVariable($key, $val, "Process")
    }
}

# 3. 映射 .env 变量到 app 期望的变量名
$env:RABBITMQ_HOST = "localhost"
$env:RABBITMQ_PORT = "5672"
$env:RABBITMQ_USER = $env:REMOTE_RABBITMQ_USER
$env:RABBITMQ_PASS = $env:REMOTE_RABBITMQ_PASSWORD
$env:NACOS_ADDR    = "localhost:8848"
$env:NACOS_USER    = $env:REMOTE_NACOS_USERNAME
$env:NACOS_PASS    = $env:REMOTE_NACOS_PASSWORD
$env:REDIS_HOST    = "localhost"
$env:REDIS_PORT    = "6379"
$env:REDIS_PASS    = $env:REMOTE_REDIS_PASSWORD
$env:MYSQL_HOST    = "localhost"
$env:MYSQL_USER    = $env:WORKER_MYSQL_USER
$env:MYSQL_PASS    = $env:WORKER_MYSQL_PASSWORD
$env:MANGA_ROOT    = if ($env:MANGA_ROOT) { $env:MANGA_ROOT } else { "F:/manga" }

if ([string]::IsNullOrWhiteSpace($env:MYSQL_USER) -or [string]::IsNullOrWhiteSpace($env:MYSQL_PASS)) {
    throw "Worker 数据库凭据未配置，请在 .env 中设置 WORKER_MYSQL_USER 和 WORKER_MYSQL_PASSWORD"
}

# 4. 确保存储目录存在（HQ/LQ/EXPORT/thumb）
@("$env:MANGA_ROOT/hq", "$env:MANGA_ROOT/lq", "$env:MANGA_ROOT/export", "$env:MANGA_ROOT/thumbs") | ForEach-Object {
    if (-not (Test-Path $_)) { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
}

# 5. 检查 RabbitMQ AMQP 端口是否可达
$mqTest = Test-NetConnection -ComputerName 127.0.0.1 -Port 5672 -WarningAction SilentlyContinue -InformationLevel Quiet
if (-not $mqTest) {
    Write-Host "WARN: RabbitMQ 127.0.0.1:5672 不可达 — FRP 连接可能尚未建立" -ForegroundColor Yellow
}

# 5. 启动 Worker
Start-Process pwsh -WorkingDirectory "$repoRoot\worker-service" -ArgumentList "-NoExit", "-Command", "`$env:MANGA_ROOT='$env:MANGA_ROOT'; `$env:RABBITMQ_HOST='$env:RABBITMQ_HOST'; `$env:RABBITMQ_PORT='$env:RABBITMQ_PORT'; `$env:RABBITMQ_USER='$env:RABBITMQ_USER'; `$env:RABBITMQ_PASS='$env:RABBITMQ_PASS'; `$env:REDIS_HOST='$env:REDIS_HOST'; `$env:REDIS_PORT='$env:REDIS_PORT'; `$env:REDIS_PASS='$env:REDIS_PASS'; `$env:NACOS_ADDR='$env:NACOS_ADDR'; `$env:NACOS_USER='$env:NACOS_USER'; `$env:NACOS_PASS='$env:NACOS_PASS'; mvn clean spring-boot:run"
# Start-Process pwsh -WorkingDirectory "$repoRoot\api-service" -ArgumentList "-NoExit", "-Command", "`$env:RABBITMQ_HOST='$env:RABBITMQ_HOST'; `$env:RABBITMQ_PORT='$env:RABBITMQ_PORT'; `$env:RABBITMQ_USER='$env:RABBITMQ_USER'; `$env:RABBITMQ_PASS='$env:RABBITMQ_PASS'; `$env:NACOS_ADDR='$env:NACOS_ADDR'; `$env:NACOS_USER='$env:NACOS_USER'; `$env:NACOS_PASS='$env:NACOS_PASS'; `$env:REDIS_PASS='$env:REDIS_PASS'; mvn clean spring-boot:run"

Write-Host "Worker 已在独立窗口启动" -ForegroundColor Green
