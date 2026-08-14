[CmdletBinding()]
param(
    [string]$RemoteHost,
    [string]$BindAddress = "0.0.0.0"
)

$ErrorActionPreference = "Stop"

$keyPath = Join-Path $env:USERPROFILE ".ssh\comicatlas_infra_ed25519"
$sshPath = (Get-Command ssh.exe).Source
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$environmentFile = Join-Path $repositoryRoot ".env"
$projectEnvironment = @{}
Get-Content -LiteralPath $environmentFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*?)\s*=\s*(.*)\s*$') {
        $projectEnvironment[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}

function Get-RequiredSetting {
    param([string]$Name)
    $value = $projectEnvironment[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "缺少远端隧道配置：$Name"
    }
    return $value
}

function Get-RequiredPort {
    param([string]$Name)
    $rawPort = Get-RequiredSetting $Name
    $port = 0
    if (-not [int]::TryParse($rawPort, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        throw "$Name 不是有效端口：$rawPort"
    }
    return $port
}

if ([string]::IsNullOrWhiteSpace($RemoteHost)) {
    $RemoteHost = Get-RequiredSetting "FRP_SERVER_ADDR"
}
$tunnelMappings = @(
    @{ Local = Get-RequiredPort "REMOTE_MYSQL_PORT"; Remote = Get-RequiredPort "REMOTE_MYSQL_PORT" },
    @{ Local = Get-RequiredPort "REMOTE_REDIS_PORT"; Remote = Get-RequiredPort "REMOTE_REDIS_PORT" },
    @{ Local = Get-RequiredPort "REMOTE_RABBITMQ_PORT"; Remote = Get-RequiredPort "REMOTE_RABBITMQ_PORT" },
    @{ Local = Get-RequiredPort "REMOTE_RABBITMQ_MANAGEMENT_PORT"; Remote = Get-RequiredPort "REMOTE_RABBITMQ_MANAGEMENT_PORT" },
    @{ Local = Get-RequiredPort "REMOTE_NACOS_HTTP_PORT"; Remote = Get-RequiredPort "REMOTE_NACOS_HTTP_PORT" },
    @{ Local = Get-RequiredPort "REMOTE_NACOS_GRPC_PORT"; Remote = Get-RequiredPort "REMOTE_NACOS_GRPC_PORT" }
)
$tunnelPorts = $tunnelMappings.Local

if (-not (Test-Path -LiteralPath $keyPath)) {
    throw "未找到 ComicAtlas 中间件隧道密钥：$keyPath"
}

$existingTunnel = Get-CimInstance Win32_Process -Filter "Name = 'ssh.exe'" |
    Where-Object { $_.CommandLine -like "*comicatlas_infra_ed25519*" } |
    Select-Object -First 1

if ($existingTunnel) {
    Write-Output "ComicAtlas 中间件隧道已运行，PID=$($existingTunnel.ProcessId)"
    exit 0
}

$occupiedPorts = foreach ($port in $tunnelPorts) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        $port
    }
}

if ($occupiedPorts) {
    throw "以下本地端口已被占用，无法建立隧道：$($occupiedPorts -join ', ')"
}

$sshArguments = @(
    "-N",
    "-T",
    "-i", $keyPath,
    "-o", "BatchMode=yes",
    "-o", "ExitOnForwardFailure=yes",
    "-o", "ServerAliveInterval=30",
    "-o", "ServerAliveCountMax=3",
    "-o", "StrictHostKeyChecking=accept-new",
    "-o", "LogLevel=ERROR"
)

foreach ($mapping in $tunnelMappings) {
    $sshArguments += @(
        "-L",
        "${BindAddress}:$($mapping.Local):127.0.0.1:$($mapping.Remote)"
    )
}

$sshArguments += "root@$RemoteHost"
$process = Start-Process -FilePath $sshPath `
    -ArgumentList $sshArguments `
    -PassThru `
    -WindowStyle Hidden

try {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        if ($process.HasExited) {
            throw "SSH 隧道进程提前退出，退出码：$($process.ExitCode)"
        }

        $ready = $true
        foreach ($port in $tunnelPorts) {
            $client = [System.Net.Sockets.TcpClient]::new()
            try {
                $connectTask = $client.ConnectAsync("127.0.0.1", $port)
                if (-not $connectTask.Wait(1000) -or -not $client.Connected) {
                    $ready = $false
                    break
                }
            }
            finally {
                $client.Dispose()
            }
        }

        if ($ready) {
            Write-Output "ComicAtlas 中间件隧道已启动，PID=$($process.Id)"
            exit 0
        }

        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "SSH 隧道启动超时"
}
catch {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
    throw
}
