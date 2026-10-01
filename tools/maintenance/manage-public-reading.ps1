[CmdletBinding()]
param(
    [ValidateSet('Prepare', 'Start', 'Stop', 'Status', 'InstallTask', 'RemoveTask')]
    [string]$Action = 'Status'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$requestedAction = $Action
# 复用环境解析及版本校验；独立程序路径避免基础设施脚本误停阅读隧道。
. (Join-Path $PSScriptRoot 'manage-remote-infra-frp.ps1')
$readingRoot = Join-Path $repositoryRoot '.runtime/public-reading'
$readingClient = Join-Path $readingRoot 'frpc.exe'
$readingConfig = Join-Path $readingRoot 'frpc-publisher.toml'
$readingTask = 'ComicAtlas Public Reading FRP'

function Get-ReadingProcess {
    Get-Process -Name frpc -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $readingClient }
}

function Stop-ReadingClient {
    $scheduled = Get-ScheduledTask -TaskName $readingTask -ErrorAction SilentlyContinue
    if ($scheduled -and $scheduled.State -eq 'Running') { Stop-ScheduledTask -TaskName $readingTask }
    Get-ReadingProcess | Stop-Process
}

function Write-ReadingBundle {
    $settings = Get-ProjectEnvironment
    if (-not $settings.ContainsKey('PUBLIC_READING_STCP_SECRET')) {
        Add-EnvironmentSetting 'PUBLIC_READING_STCP_SECRET' (New-Secret)
        $settings = Get-ProjectEnvironment
    }
    $serverAddress = ConvertTo-TomlString (Get-RequiredSetting $settings 'FRP_SERVER_ADDR')
    $serverPort = Get-RequiredPort $settings 'FRP_SERVER_PORT'
    $authToken = ConvertTo-TomlString (Get-RequiredSetting $settings 'FRP_AUTH_TOKEN')
    $secret = ConvertTo-TomlString (Get-RequiredSetting $settings 'PUBLIC_READING_STCP_SECRET')
    $localAddress = ConvertTo-TomlString (Get-RequiredSetting $settings 'PUBLIC_READING_LOCAL_HOST')
    $localPort = Get-RequiredPort $settings 'PUBLIC_READING_LOCAL_PORT'
    $publicPort = Get-RequiredPort $settings 'PUBLIC_READING_REMOTE_PORT'
    $tunnelPort = Get-RequiredPort $settings 'PUBLIC_READING_TUNNEL_PORT'
    $personalSiteMode = if ($settings.ContainsKey('PUBLIC_SITE_MODE')) { $settings['PUBLIC_SITE_MODE'] } else { 'closed' }
    if ($personalSiteMode -notin @('closed', 'open')) { throw 'PUBLIC_SITE_MODE 只能是 closed 或 open' }
    $personalSitePage = Get-Content (Join-Path $repositoryRoot 'site/personal-blog/index.html') -Raw
    $icpFooter = ''
    if ($personalSiteMode -eq 'open') {
        $icpNumber = Get-RequiredSetting $settings 'PUBLIC_SITE_ICP_NUMBER'
        if ($icpNumber -notmatch '^[\p{L}\p{N}-]+$') { throw 'PUBLIC_SITE_ICP_NUMBER 格式无效' }
        $encodedIcpNumber = [System.Net.WebUtility]::HtmlEncode($icpNumber)
        $icpFooter = "<a href=`"https://beian.miit.gov.cn/`" target=`"_blank`" rel=`"noopener noreferrer`">$encodedIcpNumber</a>"
    }
    if ($publicPort -eq $tunnelPort) { throw '公网端口与内部隧道端口不能相同' }
    New-Item -ItemType Directory -Path $readingRoot -Force | Out-Null
    $personalSiteRoot = Join-Path $readingRoot 'site'
    New-Item -ItemType Directory -Path $personalSiteRoot -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $personalSiteRoot 'index.html'), $personalSitePage.Replace('<!-- ICP_FOOTER -->', $icpFooter))
    Install-WindowsClient
    if (-not (Test-Path -LiteralPath $readingClient)) { Copy-Item -LiteralPath $frpcPath -Destination $readingClient }
    $publisher = @"
serverAddr = "$serverAddress"
serverPort = $serverPort
user = "comicatlas-reading-local"
loginFailExit = false
auth.method = "token"
auth.token = "$authToken"
auth.additionalScopes = ["HeartBeats", "NewWorkConns"]
transport.tls.enable = true
transport.wireProtocol = "v2"
transport.heartbeatInterval = 30
transport.tcpMuxKeepaliveInterval = 30
log.to = "$(ConvertTo-TomlString (Join-Path $readingRoot 'publisher.log'))"
log.maxDays = 7
[[proxies]]
name = "reading-web"
type = "stcp"
secretKey = "$secret"
allowUsers = ["comicatlas-reading-remote"]
localIP = "$localAddress"
localPort = $localPort
"@
    $visitor = @"
serverAddr = "127.0.0.1"
serverPort = $serverPort
user = "comicatlas-reading-remote"
loginFailExit = false
auth.method = "token"
auth.token = "$authToken"
auth.additionalScopes = ["HeartBeats", "NewWorkConns"]
transport.tls.enable = true
transport.wireProtocol = "v2"
transport.heartbeatInterval = 30
transport.tcpMuxKeepaliveInterval = 30
[[visitors]]
name = "reading-web-visitor"
type = "stcp"
serverUser = "comicatlas-reading-local"
serverName = "reading-web"
secretKey = "$secret"
bindAddr = "127.0.0.1"
bindPort = $tunnelPort
"@
    [IO.File]::WriteAllText($readingConfig, $publisher)
    [IO.File]::WriteAllText((Join-Path $readingRoot 'frpc-visitor.toml'), $visitor)
    $template = Get-Content (Join-Path $PSScriptRoot 'public-reading-nginx.conf.template') -Raw
    $personalSiteLocation = if ($personalSiteMode -eq 'open') {
        'root /opt/comicatlas-public-reading/site; index index.html; try_files $uri $uri/ =404;'
    } else {
        'return 403;'
    }
    $nginxConfiguration = $template.Replace('__PUBLIC_PORT__', "$publicPort").Replace('__TUNNEL_PORT__', "$tunnelPort").Replace('__PERSONAL_SITE_LOCATION__', $personalSiteLocation)
    [IO.File]::WriteAllText((Join-Path $readingRoot 'nginx.conf'), $nginxConfiguration)
    foreach ($service in @('tunnel', 'nginx')) {
        $command = if ($service -eq 'tunnel') {
            '/usr/local/bin/frpc -c /opt/comicatlas-public-reading/frpc-visitor.toml'
        } else {
            '/usr/sbin/nginx -c /opt/comicatlas-public-reading/nginx.conf -g "daemon off;"'
        }
        $unit = @"
[Unit]
Description=ComicAtlas public reading $service
After=network-online.target frps.service
Wants=network-online.target
[Service]
Type=simple
ExecStart=$command
Restart=always
RestartSec=5
[Install]
WantedBy=multi-user.target
"@
        [IO.File]::WriteAllText((Join-Path $readingRoot "comicatlas-reading-$service.service"), $unit.Replace("`r`n", "`n"))
    }
    & $readingClient verify -c $readingConfig
    if ($LASTEXITCODE -ne 0) { throw '阅读发布端配置校验失败' }
    & $readingClient verify -c (Join-Path $readingRoot 'frpc-visitor.toml')
    if ($LASTEXITCODE -ne 0) { throw '阅读访问端配置校验失败' }
    Write-Output '阅读隧道配置与远端安装包已生成（包含凭据，请勿提交）'
}

switch ($requestedAction) {
    'Prepare' { Write-ReadingBundle }
    'Start' {
        if (Get-ReadingProcess) { Write-Output '阅读发布端已运行'; break }
        Write-ReadingBundle
        $process = Start-Process -FilePath $readingClient -ArgumentList @('-c', "`"$readingConfig`"") -WindowStyle Hidden -PassThru
        Start-Sleep -Seconds 2
        if ($process.HasExited) { throw '阅读发布端启动失败，请检查 publisher.log' }
        Write-Output "阅读发布端已启动，PID=$($process.Id)；隧道连通性需通过公网入口验证"
    }
    'Stop' { Stop-ReadingClient }
    'Status' {
        $process = @(Get-ReadingProcess)
        Write-Output "阅读发布端进程数：$($process.Count)"
        Get-ScheduledTask -TaskName $readingTask -ErrorAction SilentlyContinue | Select-Object TaskName, State
    }
    'InstallTask' {
        Write-ReadingBundle
        Stop-ReadingClient
        $taskAction = New-ScheduledTaskAction -Execute $readingClient -Argument "-c `"$readingConfig`"" -WorkingDirectory $readingRoot
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
        $taskSettings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable
        Register-ScheduledTask -TaskName $readingTask -Action $taskAction -Trigger $trigger -Settings $taskSettings -Force | Out-Null
        Start-ScheduledTask -TaskName $readingTask
    }
    'RemoveTask' {
        Stop-ReadingClient
        Unregister-ScheduledTask -TaskName $readingTask -Confirm:$false -ErrorAction SilentlyContinue
    }
}
