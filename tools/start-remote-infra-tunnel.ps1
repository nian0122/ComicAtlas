[CmdletBinding()]
param(
    [string]$RemoteHost = "101.37.66.217",
    [string]$BindAddress = "0.0.0.0"
)

$ErrorActionPreference = "Stop"

$keyPath = Join-Path $env:USERPROFILE ".ssh\comicatlas_infra_ed25519"
$sshPath = (Get-Command ssh.exe).Source
$tunnelMappings = @(
    @{ Local = 3306; Remote = 3306 },
    @{ Local = 6379; Remote = 6379 },
    @{ Local = 5672; Remote = 5672 },
    @{ Local = 15672; Remote = 15672 },
    @{ Local = 8848; Remote = 8848 },
    @{ Local = 9848; Remote = 9848 }
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
