[CmdletBinding()]
param(
    [ValidateSet("Initialize", "Install", "PrepareServer", "Verify", "Start", "Stop", "Status", "InstallTask", "RemoveTask")]
    [string]$Action = "Start",
    [string]$Version = "0.70.1",
    [switch]$ReplaceSshTunnel
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$environmentFile = Join-Path $repositoryRoot ".env"
$runtimeDirectory = Join-Path $repositoryRoot ".runtime\frp"
$downloadDirectory = Join-Path $runtimeDirectory "downloads"
$visitorConfigPath = Join-Path $runtimeDirectory "frpc-visitor.toml"
$clientLogPath = Join-Path $runtimeDirectory "frpc-visitor.log"
$vendorDirectory = Join-Path $repositoryRoot "tools\vendor\frp\$Version\windows-amd64"
$frpcPath = Join-Path $vendorDirectory "frpc.exe"
$taskName = "ComicAtlas Remote Infra FRP"
$legacyTaskName = "ComicAtlas Remote Infra Tunnel"
$visitorUser = "comicatlas-local"
$heartbeatIntervalSeconds = 30
$serviceDefinitions = @(
    [ordered]@{ Name = "mysql"; PortSetting = "REMOTE_MYSQL_PORT" },
    [ordered]@{ Name = "redis"; PortSetting = "REMOTE_REDIS_PORT" },
    [ordered]@{ Name = "rabbitmq"; PortSetting = "REMOTE_RABBITMQ_PORT" },
    [ordered]@{ Name = "rabbitmq-management"; PortSetting = "REMOTE_RABBITMQ_MANAGEMENT_PORT" },
    [ordered]@{ Name = "nacos-http"; PortSetting = "REMOTE_NACOS_HTTP_PORT" },
    [ordered]@{ Name = "nacos-grpc"; PortSetting = "REMOTE_NACOS_GRPC_PORT" },
    [ordered]@{ Name = "frps-dashboard"; PortSetting = "FRP_DASHBOARD_PORT"; BindAddress = "127.0.0.1" }
)

function Get-ProjectEnvironment {
    $settings = @{}
    if (-not (Test-Path -LiteralPath $environmentFile)) {
        return $settings
    }
    Get-Content -LiteralPath $environmentFile | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]*?)\s*=\s*(.*)\s*$') {
            $settings[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    return $settings
}

function Add-EnvironmentSetting {
    param([string]$Name, [string]$Value)
    $line = "$Name=$Value$([Environment]::NewLine)"
    [System.IO.File]::AppendAllText($environmentFile, $line, [System.Text.UTF8Encoding]::new($false))
}

function New-Secret {
    return [Convert]::ToHexString(
        [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    ).ToLowerInvariant()
}

function Initialize-Environment {
    if (-not (Test-Path -LiteralPath $environmentFile)) {
        [System.IO.File]::WriteAllText($environmentFile, "", [System.Text.UTF8Encoding]::new($false))
    }
    $settings = Get-ProjectEnvironment
    if (-not $settings.ContainsKey("FRP_SERVER_ADDR")) {
        throw "请先在 .env 中设置 FRP_SERVER_ADDR"
    }
    if (-not $settings.ContainsKey("FRP_AUTH_TOKEN")) {
        Add-EnvironmentSetting "FRP_AUTH_TOKEN" (New-Secret)
    }
    if (-not $settings.ContainsKey("FRP_STCP_SECRET")) {
        Add-EnvironmentSetting "FRP_STCP_SECRET" (New-Secret)
    }
    if (-not $settings.ContainsKey("FRP_VISITOR_BIND_ADDR")) {
        Add-EnvironmentSetting "FRP_VISITOR_BIND_ADDR" "0.0.0.0"
    }
    if (-not $settings.ContainsKey("FRP_PROVIDER_USER")) {
        Add-EnvironmentSetting "FRP_PROVIDER_USER" "comicatlas-infra"
    }
    if (-not $settings.ContainsKey("FRP_DASHBOARD_USER")) {
        Add-EnvironmentSetting "FRP_DASHBOARD_USER" "admin"
    }
    if (-not $settings.ContainsKey("FRP_DASHBOARD_PASSWORD")) {
        Add-EnvironmentSetting "FRP_DASHBOARD_PASSWORD" (New-Secret)
    }
    $settings = Get-ProjectEnvironment
    $null = Get-ServerPort $settings
    $null = @(Get-ServiceMappings $settings)
    Write-Output "FRP 环境变量已在未跟踪的 .env 中就绪"
}

function Get-RequiredSetting {
    param([hashtable]$Settings, [string]$Name)
    if (-not $Settings.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace($Settings[$Name])) {
        throw "缺少 FRP 配置：$Name"
    }
    return $Settings[$Name]
}

function ConvertTo-TomlString {
    param([string]$Value)
    return $Value.Replace('\', '\\').Replace('"', '\"')
}

function Get-RequiredPort {
    param([hashtable]$Settings, [string]$Name)
    $rawPort = Get-RequiredSetting $Settings $Name
    $port = 0
    if (-not [int]::TryParse($rawPort, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        throw "$Name 不是有效端口：$rawPort"
    }
    return $port
}

function Get-ServerPort {
    param([hashtable]$Settings)
    return Get-RequiredPort $Settings "FRP_SERVER_PORT"
}

function Get-ServiceMappings {
    param([hashtable]$Settings)
    foreach ($definition in $serviceDefinitions) {
        $mapping = [ordered]@{
            Name = $definition.Name
            Port = Get-RequiredPort $Settings $definition.PortSetting
            PortSetting = $definition.PortSetting
        }
        if ($definition.Contains("BindAddress")) {
            $mapping.BindAddress = $definition.BindAddress
        }
        $mapping
    }
}

function Get-ReleaseArchive {
    param([string]$Target, [string]$Extension)
    New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
    $archiveName = "frp_${Version}_${Target}.${Extension}"
    $archivePath = Join-Path $downloadDirectory $archiveName
    $checksumPath = Join-Path $downloadDirectory "frp_${Version}_sha256_checksums.txt"
    $releaseBase = "https://github.com/fatedier/frp/releases/download/v$Version"
    if (-not (Test-Path -LiteralPath $archivePath)) {
        Invoke-WebRequest -Uri "$releaseBase/$archiveName" -OutFile $archivePath
    }
    Invoke-WebRequest -Uri "$releaseBase/frp_sha256_checksums.txt" -OutFile $checksumPath
    $checksumLine = Get-Content -LiteralPath $checksumPath |
        Where-Object { $_ -match "\s$([regex]::Escape($archiveName))$" } |
        Select-Object -First 1
    if (-not $checksumLine) {
        throw "官方校验文件中未找到 $archiveName"
    }
    $expectedHash = ($checksumLine -split '\s+')[0]
    $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash.ToLowerInvariant()) {
        throw "$archiveName 的 SHA256 校验失败"
    }
    return $archivePath
}

function Install-WindowsClient {
    if (Test-Path -LiteralPath $frpcPath) {
        return
    }
    $archivePath = Get-ReleaseArchive "windows_amd64" "zip"
    $extractDirectory = Join-Path $downloadDirectory "windows-$Version"
    if (Test-Path -LiteralPath $extractDirectory) {
        Remove-Item -LiteralPath $extractDirectory -Recurse -Force
    }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $extractDirectory
    $source = Get-ChildItem -LiteralPath $extractDirectory -Filter "frpc.exe" -Recurse -File |
        Select-Object -First 1
    if (-not $source) {
        throw "FRP Windows 压缩包中未找到 frpc.exe"
    }
    New-Item -ItemType Directory -Path $vendorDirectory -Force | Out-Null
    Copy-Item -LiteralPath $source.FullName -Destination $frpcPath
}

function Write-VisitorConfig {
    $settings = Get-ProjectEnvironment
    $serviceMappings = @(Get-ServiceMappings $settings)
    $serverAddress = ConvertTo-TomlString (Get-RequiredSetting $settings "FRP_SERVER_ADDR")
    $serverPort = Get-ServerPort $settings
    $authToken = ConvertTo-TomlString (Get-RequiredSetting $settings "FRP_AUTH_TOKEN")
    $stcpSecret = ConvertTo-TomlString (Get-RequiredSetting $settings "FRP_STCP_SECRET")
    $bindAddress = ConvertTo-TomlString (Get-RequiredSetting $settings "FRP_VISITOR_BIND_ADDR")
    $providerUser = ConvertTo-TomlString (Get-RequiredSetting $settings "FRP_PROVIDER_USER")
    $logPath = ConvertTo-TomlString $clientLogPath
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("serverAddr = `"$serverAddress`"")
    $lines.Add("serverPort = $serverPort")
    $lines.Add("user = `"$visitorUser`"")
    $lines.Add('loginFailExit = false')
    $lines.Add('auth.method = "token"')
    $lines.Add("auth.token = `"$authToken`"")
    $lines.Add('auth.additionalScopes = ["HeartBeats", "NewWorkConns"]')
    $lines.Add('transport.tls.enable = true')
    $lines.Add('transport.wireProtocol = "v2"')
    $lines.Add('transport.tcpMuxKeepaliveInterval = 30')
    $lines.Add("transport.heartbeatInterval = $heartbeatIntervalSeconds")
    $lines.Add("log.to = `"$logPath`"")
    $lines.Add('log.level = "info"')
    $lines.Add('log.maxDays = 7')
    foreach ($service in $serviceMappings) {
        $serviceBindAddress = if ($service.Contains("BindAddress")) {
            ConvertTo-TomlString $service.BindAddress
        }
        else {
            $bindAddress
        }
        $lines.Add('')
        $lines.Add('[[visitors]]')
        $lines.Add("name = `"$($service.Name)-visitor`"")
        $lines.Add('type = "stcp"')
        $lines.Add("serverUser = `"$providerUser`"")
        $lines.Add("serverName = `"$($service.Name)`"")
        $lines.Add("secretKey = `"$stcpSecret`"")
        $lines.Add("bindAddr = `"$serviceBindAddress`"")
        $lines.Add("bindPort = $($service.Port)")
    }
    New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
    [System.IO.File]::WriteAllLines($visitorConfigPath, $lines, [System.Text.UTF8Encoding]::new($false))
}

function Write-ServerBundle {
    $settings = Get-ProjectEnvironment
    $serverPort = Get-ServerPort $settings
    $dashboardPort = Get-RequiredPort $settings "FRP_DASHBOARD_PORT"
    $serviceMappings = @(Get-ServiceMappings $settings)
    $authToken = Get-RequiredSetting $settings "FRP_AUTH_TOKEN"
    $stcpSecret = Get-RequiredSetting $settings "FRP_STCP_SECRET"
    $providerUser = ConvertTo-TomlString (Get-RequiredSetting $settings "FRP_PROVIDER_USER")
    $dashboardUser = Get-RequiredSetting $settings "FRP_DASHBOARD_USER"
    $dashboardPassword = Get-RequiredSetting $settings "FRP_DASHBOARD_PASSWORD"
    $archivePath = Get-ReleaseArchive "linux_amd64" "tar.gz"
    $extractDirectory = Join-Path $downloadDirectory "linux-$Version"
    $bundleDirectory = Join-Path $runtimeDirectory "server-bundle"
    if (Test-Path -LiteralPath $extractDirectory) {
        Remove-Item -LiteralPath $extractDirectory -Recurse -Force
    }
    if (Test-Path -LiteralPath $bundleDirectory) {
        Remove-Item -LiteralPath $bundleDirectory -Recurse -Force
    }
    New-Item -ItemType Directory -Path $extractDirectory, $bundleDirectory -Force | Out-Null
    & tar.exe -xzf $archivePath -C $extractDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "FRP Linux 压缩包解压失败"
    }
    $linuxRoot = Get-ChildItem -LiteralPath $extractDirectory -Directory | Select-Object -First 1
    Copy-Item -LiteralPath (Join-Path $linuxRoot.FullName "frps") -Destination $bundleDirectory
    Copy-Item -LiteralPath (Join-Path $linuxRoot.FullName "frpc") -Destination $bundleDirectory
    $frpsConfig = @"
bindAddr = "0.0.0.0"
bindPort = {{ .Envs.FRP_SERVER_PORT }}
auth.method = "token"
auth.token = "{{ .Envs.FRP_AUTH_TOKEN }}"
auth.additionalScopes = ["HeartBeats", "NewWorkConns"]
transport.tls.force = true
transport.tcpMuxKeepaliveInterval = 30
transport.heartbeatTimeout = 90
webServer.addr = "127.0.0.1"
webServer.port = {{ .Envs.FRP_DASHBOARD_PORT }}
webServer.user = "{{ .Envs.FRP_DASHBOARD_USER }}"
webServer.password = "{{ .Envs.FRP_DASHBOARD_PASSWORD }}"
log.to = "/var/log/frp/frps.log"
log.level = "info"
log.maxDays = 7
"@
    $providerLines = [System.Collections.Generic.List[string]]::new()
    $providerLines.Add('serverAddr = "127.0.0.1"')
    $providerLines.Add('serverPort = {{ .Envs.FRP_SERVER_PORT }}')
    $providerLines.Add("user = `"$providerUser`"")
    $providerLines.Add('loginFailExit = false')
    $providerLines.Add('auth.method = "token"')
    $providerLines.Add('auth.token = "{{ .Envs.FRP_AUTH_TOKEN }}"')
    $providerLines.Add('auth.additionalScopes = ["HeartBeats", "NewWorkConns"]')
    $providerLines.Add('transport.tls.enable = true')
    $providerLines.Add('transport.wireProtocol = "v2"')
    $providerLines.Add('transport.tcpMuxKeepaliveInterval = 30')
    $providerLines.Add("transport.heartbeatInterval = $heartbeatIntervalSeconds")
    $providerLines.Add('log.to = "/var/log/frp/frpc-provider.log"')
    $providerLines.Add('log.level = "info"')
    $providerLines.Add('log.maxDays = 7')
    foreach ($service in $serviceMappings) {
        $providerLines.Add('')
        $providerLines.Add('[[proxies]]')
        $providerLines.Add("name = `"$($service.Name)`"")
        $providerLines.Add('type = "stcp"')
        $providerLines.Add('secretKey = "{{ .Envs.FRP_STCP_SECRET }}"')
        $providerLines.Add("allowUsers = [`"$visitorUser`"]")
        $providerLines.Add('localIP = "127.0.0.1"')
        $providerLines.Add("localPort = {{ .Envs.$($service.PortSetting) }}")
        $providerLines.Add('healthCheck.type = "tcp"')
        $providerLines.Add('healthCheck.timeoutSeconds = 3')
        $providerLines.Add('healthCheck.maxFailed = 3')
        $providerLines.Add('healthCheck.intervalSeconds = 10')
    }
    $environmentContent = @"
FRP_AUTH_TOKEN=$authToken
FRP_STCP_SECRET=$stcpSecret
FRP_DASHBOARD_USER=$dashboardUser
FRP_DASHBOARD_PASSWORD=$dashboardPassword
FRP_SERVER_PORT=$serverPort
FRP_DASHBOARD_PORT=$dashboardPort
REMOTE_MYSQL_PORT=$($settings["REMOTE_MYSQL_PORT"])
REMOTE_REDIS_PORT=$($settings["REMOTE_REDIS_PORT"])
REMOTE_RABBITMQ_PORT=$($settings["REMOTE_RABBITMQ_PORT"])
REMOTE_RABBITMQ_MANAGEMENT_PORT=$($settings["REMOTE_RABBITMQ_MANAGEMENT_PORT"])
REMOTE_NACOS_HTTP_PORT=$($settings["REMOTE_NACOS_HTTP_PORT"])
REMOTE_NACOS_GRPC_PORT=$($settings["REMOTE_NACOS_GRPC_PORT"])
"@
    $frpsService = @'
[Unit]
Description=ComicAtlas FRP Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/comicatlas-frp.env
ExecStart=/usr/local/bin/frps -c /opt/comicatlas-frp/frps.toml
Restart=always
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
'@
    $providerService = @'
[Unit]
Description=ComicAtlas FRP Infrastructure Provider
After=network-online.target frps.service
Wants=network-online.target
Requires=frps.service

[Service]
Type=simple
EnvironmentFile=/etc/comicatlas-frp.env
ExecStart=/usr/local/bin/frpc -c /opt/comicatlas-frp/frpc-provider.toml
Restart=always
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
'@
    [System.IO.File]::WriteAllText((Join-Path $bundleDirectory "frps.toml"), $frpsConfig, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllLines((Join-Path $bundleDirectory "frpc-provider.toml"), $providerLines, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText((Join-Path $bundleDirectory "comicatlas-frp.env"), $environmentContent, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText((Join-Path $bundleDirectory "frps.service"), $frpsService, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText((Join-Path $bundleDirectory "frpc-provider.service"), $providerService, [System.Text.UTF8Encoding]::new($false))
    Write-Output "远端安装包已生成：$bundleDirectory"
}

function Get-ClientProcess {
    return Get-Process -Name "frpc" -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $frpcPath } |
        Select-Object -First 1
}

function Stop-LegacyTunnel {
    if (-not $ReplaceSshTunnel) {
        return
    }
    $legacyTask = Get-ScheduledTask -TaskName $legacyTaskName -ErrorAction SilentlyContinue
    if ($legacyTask) {
        if ($legacyTask.State -eq "Running") {
            Stop-ScheduledTask -TaskName $legacyTaskName
        }
        Disable-ScheduledTask -TaskName $legacyTaskName | Out-Null
    }
}

function Get-ListeningServiceMappings {
    param([System.Collections.IEnumerable]$Mappings)
    return @($Mappings | Where-Object {
        $candidatePort = $_.Port
        Test-LocalTcpPort -Port $candidatePort
    })
}

function Test-LocalTcpPort {
    param([int]$Port, [int]$TimeoutMilliseconds = 500)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connectTask = $client.ConnectAsync("127.0.0.1", $Port)
        return $connectTask.Wait($TimeoutMilliseconds) -and $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Start-Client {
    Stop-LegacyTunnel
    $serviceMappings = @(Get-ServiceMappings (Get-ProjectEnvironment))
    $existingProcess = Get-ClientProcess
    if ($existingProcess) {
        Write-Output "FRP visitor 已运行，PID=$($existingProcess.Id)"
        return
    }
    $occupiedPorts = foreach ($service in $serviceMappings) {
        if (Test-LocalTcpPort -Port $service.Port) {
            $service.Port
        }
    }
    if ($occupiedPorts) {
        throw "以下端口已被占用：$($occupiedPorts -join ', ')。如由旧 SSH 隧道占用，请使用 -ReplaceSshTunnel"
    }
    $process = Start-Process -FilePath $frpcPath -ArgumentList @("-c", $visitorConfigPath) -PassThru -WindowStyle Hidden
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        if ($process.HasExited) {
            throw "frpc 提前退出，退出码：$($process.ExitCode)，请检查 $clientLogPath"
        }
        $readyPorts = @(Get-ListeningServiceMappings -Mappings $serviceMappings)
        if ($readyPorts.Count -eq $serviceMappings.Count) {
            Write-Output "FRP visitor 已启动，PID=$($process.Id)"
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    Write-Warning "frpc 正在后台重连，远端 frps/provider 就绪后会自动建立本地端口"
}

function Stop-Client {
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($task -and $task.State -eq "Running") {
        Stop-ScheduledTask -TaskName $taskName
    }
    $process = Get-ClientProcess
    if ($process) {
        Stop-Process -Id $process.Id -Force
    }
    Write-Output "FRP visitor 已停止"
}

function Show-Status {
    $serviceMappings = @(Get-ServiceMappings (Get-ProjectEnvironment))
    $process = Get-ClientProcess
    if ($process) {
        Write-Output "FRP visitor 进程：运行中，PID=$($process.Id)"
    }
    else {
        Write-Output "FRP visitor 进程：未运行"
    }
    foreach ($service in $serviceMappings) {
        $state = if (Test-LocalTcpPort -Port $service.Port) { "监听中" } else { "未监听" }
        Write-Output "$($service.Name) 127.0.0.1:$($service.Port) $state"
    }
}

function Install-ClientTask {
    Stop-Client
    $taskAction = New-ScheduledTaskAction -Execute $frpcPath -Argument "-c `"$visitorConfigPath`""
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $settings = New-ScheduledTaskSettingsSet `
        -RestartCount 999 `
        -RestartInterval (New-TimeSpan -Minutes 1) `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -StartWhenAvailable
    Register-ScheduledTask `
        -TaskName $taskName `
        -Action $taskAction `
        -Trigger $trigger `
        -Settings $settings `
        -Description "ComicAtlas 远端基础设施 FRP STCP visitor" `
        -Force | Out-Null
    Stop-LegacyTunnel
    Start-ScheduledTask -TaskName $taskName
    Write-Output "FRP 登录自启动任务已安装：$taskName"
}

if ($MyInvocation.InvocationName -eq ".") {
    return
}

if ($Action -in @("Initialize", "Install", "PrepareServer", "Verify", "Start", "InstallTask")) {
    Initialize-Environment
}
if ($Action -in @("Install", "Verify", "Start", "InstallTask")) {
    Install-WindowsClient
    Write-VisitorConfig
}

switch ($Action) {
    "Initialize" { break }
    "Install" { Write-Output "FRP Windows 客户端已安装：$frpcPath" }
    "PrepareServer" { Write-ServerBundle }
    "Verify" {
        & $frpcPath verify -c $visitorConfigPath
        if ($LASTEXITCODE -ne 0) { throw "FRP visitor 配置校验失败" }
    }
    "Start" {
        & $frpcPath verify -c $visitorConfigPath
        if ($LASTEXITCODE -ne 0) { throw "FRP visitor 配置校验失败" }
        Start-Client
    }
    "Stop" { Stop-Client }
    "Status" { Show-Status }
    "InstallTask" {
        & $frpcPath verify -c $visitorConfigPath
        if ($LASTEXITCODE -ne 0) { throw "FRP visitor 配置校验失败" }
        Install-ClientTask
    }
    "RemoveTask" {
        Stop-Client
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
        Write-Output "FRP 登录自启动任务已删除"
    }
}
