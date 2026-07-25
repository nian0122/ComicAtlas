# ComicAtlas - 开发环境启动
Write-Host "=== ComicAtlas 开发环境 ===" -ForegroundColor Cyan

# 从 .env 加载远端中间件凭证
$envFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -match '^\s*([^#].+?)\s*=\s*(.+)$' } | ForEach-Object {
        $key, $val = $Matches[1], $Matches[2]
        [Environment]::SetEnvironmentVariable($key, $val, "Process")
    }
}

# 映射 .env 变量到 app 期望的变量名
$env:RABBITMQ_USER = $env:REMOTE_RABBITMQ_USER
$env:RABBITMQ_PASS = $env:REMOTE_RABBITMQ_PASSWORD
$env:NACOS_USER    = $env:REMOTE_NACOS_USERNAME
$env:NACOS_PASS    = $env:REMOTE_NACOS_PASSWORD
$env:REDIS_PASS    = $env:REMOTE_REDIS_PASSWORD

Start-Process pwsh -WorkingDirectory "$PSScriptRoot\worker-service" -ArgumentList "-NoExit", "-Command", "`$env:RABBITMQ_USER='$env:RABBITMQ_USER'; `$env:RABBITMQ_PASS='$env:RABBITMQ_PASS'; `$env:NACOS_USER='$env:NACOS_USER'; `$env:NACOS_PASS='$env:NACOS_PASS'; mvn clean spring-boot:run"
Start-Process pwsh -WorkingDirectory "$PSScriptRoot\api-service" -ArgumentList "-NoExit", "-Command", "`$env:RABBITMQ_USER='$env:RABBITMQ_USER'; `$env:RABBITMQ_PASS='$env:RABBITMQ_PASS'; `$env:NACOS_USER='$env:NACOS_USER'; `$env:NACOS_PASS='$env:NACOS_PASS'; `$env:REDIS_PASS='$env:REDIS_PASS'; mvn clean spring-boot:run"

Write-Host "Worker / API 已在独立窗口启动" -ForegroundColor Green
