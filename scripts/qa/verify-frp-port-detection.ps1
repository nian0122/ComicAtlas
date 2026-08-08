$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scriptPath = Join-Path $repositoryRoot "tools\maintenance\manage-remote-infra-frp.ps1"

. $scriptPath
$null = Get-ClientProcess

function Get-ClientProcess {
    return [System.Diagnostics.Process]::GetCurrentProcess()
}

$statusOutput = @(Show-Status)
if (-not ($statusOutput -match "FRP visitor 进程：运行中，PID=$PID")) {
    throw "FRP 状态输出未使用 System.Diagnostics.Process.Id"
}

$dashboardMapping = $serviceMappings |
    Where-Object { $_.Name -eq "frps-dashboard" } |
    Select-Object -First 1
if (-not $dashboardMapping) {
    throw "FRP Dashboard 映射缺失"
}
if ($dashboardMapping.Port -ne 7500 -or $dashboardMapping.BindAddress -ne "127.0.0.1") {
    throw "FRP Dashboard 必须固定绑定 127.0.0.1:7500"
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
