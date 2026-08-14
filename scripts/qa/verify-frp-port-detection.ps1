$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scriptPath = Join-Path $repositoryRoot "tools\maintenance\manage-remote-infra-frp.ps1"

. $scriptPath
$null = Get-ClientProcess

$configuredHeartbeatInterval = Get-Variable `
    -Name heartbeatIntervalSeconds `
    -ValueOnly `
    -ErrorAction SilentlyContinue
if ($configuredHeartbeatInterval -ne 30) {
    throw "FRP client 心跳必须为 30 秒，不能禁用后继续保留服务端 90 秒超时"
}

function Get-ClientProcess {
    return [System.Diagnostics.Process]::GetCurrentProcess()
}

$statusOutput = @(Show-Status)
if (-not ($statusOutput -match "FRP visitor 进程：运行中，PID=$PID")) {
    throw "FRP 状态输出未使用 System.Diagnostics.Process.Id"
}

$portSettings = @{
    REMOTE_MYSQL_PORT = "13306"
    REMOTE_REDIS_PORT = "16379"
    REMOTE_RABBITMQ_PORT = "15672"
    REMOTE_RABBITMQ_MANAGEMENT_PORT = "25672"
    REMOTE_NACOS_HTTP_PORT = "18848"
    REMOTE_NACOS_GRPC_PORT = "19848"
    FRP_DASHBOARD_PORT = "17500"
}
$configuredMappings = @(Get-ServiceMappings $portSettings)
$dashboardMapping = $configuredMappings |
    Where-Object { $_.Name -eq "frps-dashboard" } |
    Select-Object -First 1
if (-not $dashboardMapping) {
    throw "FRP Dashboard 映射缺失"
}
if ($dashboardMapping.Port -ne 17500 -or $dashboardMapping.BindAddress -ne "127.0.0.1") {
    throw "FRP Dashboard 必须使用环境变量端口并固定绑定 127.0.0.1"
}
if ($serviceDefinitions | Where-Object { $_.Contains("Port") }) {
    throw "FRP 服务定义不得写死端口"
}

$listener = [System.Net.Sockets.TcpListener]::new(
    [System.Net.IPAddress]::Loopback,
    0
)
$listener.Start()

try {
    $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    $mappings = @([ordered]@{ Name = "regression"; Port = $port })
    $detectedMappings = @(Get-ListeningServiceMappings -Mappings $mappings)
    if ($detectedMappings.Count -ne 1) {
        throw "FRP 端口检测失败：监听端口 $port 未被识别"
    }
    Write-Output "FRP 端口检测回归测试通过"
}
finally {
    $listener.Stop()
}
