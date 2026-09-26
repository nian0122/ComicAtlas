# 开发与测试共用的基础设施环境映射；FRP visitor 在本机暴露远端端口。
function Import-InfrastructureEnvironment {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $environmentFile = Join-Path $RepositoryRoot '.env'
    if (-not (Test-Path -LiteralPath $environmentFile)) {
        throw "未找到 .env：$environmentFile"
    }

    $settings = @{}
    Get-Content -LiteralPath $environmentFile | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]*?)\s*=\s*(.*)\s*$') {
            $settings[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }

    $defaultPorts = @{
        REMOTE_MYSQL_PORT = '3306'
        REMOTE_REDIS_PORT = '6379'
        REMOTE_RABBITMQ_PORT = '5672'
        REMOTE_RABBITMQ_MANAGEMENT_PORT = '15672'
        REMOTE_NACOS_HTTP_PORT = '8848'
        REMOTE_NACOS_GRPC_PORT = '9848'
    }
    foreach ($port in $defaultPorts.GetEnumerator()) {
        if (-not $settings.ContainsKey($port.Key)) {
            $settings[$port.Key] = $port.Value
        }
    }

    $requiredSettings = @(
        'WORKER_MYSQL_USER', 'WORKER_MYSQL_PASSWORD', 'REMOTE_MYSQL_PORT',
        'REMOTE_REDIS_PORT', 'REMOTE_REDIS_PASSWORD',
        'REMOTE_RABBITMQ_PORT', 'REMOTE_RABBITMQ_MANAGEMENT_PORT',
        'REMOTE_RABBITMQ_USER', 'REMOTE_RABBITMQ_PASSWORD',
        'REMOTE_NACOS_HTTP_PORT', 'REMOTE_NACOS_USER', 'REMOTE_NACOS_PASSWORD'
    )
    $missingSettings = @($requiredSettings | Where-Object {
        -not $settings.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($settings[$_])
    })
    if ($missingSettings.Count -gt 0) {
        throw "基础设施环境变量未配置：$($missingSettings -join ', ')"
    }

    foreach ($setting in $settings.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($setting.Key, $setting.Value, 'Process')
    }

    $mappedSettings = @{
        MYSQL_HOST = 'localhost'
        MYSQL_PORT = $settings.REMOTE_MYSQL_PORT
        MYSQL_USER = $settings.WORKER_MYSQL_USER
        MYSQL_PASS = $settings.WORKER_MYSQL_PASSWORD
        REDIS_HOST = 'localhost'
        REDIS_PORT = $settings.REMOTE_REDIS_PORT
        REDIS_PASS = $settings.REMOTE_REDIS_PASSWORD
        RABBITMQ_HOST = 'localhost'
        RABBITMQ_PORT = $settings.REMOTE_RABBITMQ_PORT
        RABBITMQ_MANAGEMENT_PORT = $settings.REMOTE_RABBITMQ_MANAGEMENT_PORT
        RABBITMQ_USER = $settings.REMOTE_RABBITMQ_USER
        RABBITMQ_PASS = $settings.REMOTE_RABBITMQ_PASSWORD
        NACOS_ADDR = "localhost:$($settings.REMOTE_NACOS_HTTP_PORT)"
        NACOS_USER = $settings.REMOTE_NACOS_USER
        NACOS_PASS = $settings.REMOTE_NACOS_PASSWORD
    }
    foreach ($setting in $mappedSettings.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($setting.Key, $setting.Value, 'Process')
    }
}
