[CmdletBinding()]
param(
    [string]$RemoteHost,
    [string]$BackupPath,
    [string]$KeyPath = (Join-Path $env:USERPROFILE ".ssh\comicatlas_backup_ed25519")
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$environmentFile = Join-Path $projectRoot ".env"
$projectEnvironment = @{}
if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "未找到项目环境文件：$environmentFile"
}
Get-Content -LiteralPath $environmentFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*?)\s*=\s*(.*)\s*$') {
        $projectEnvironment[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}

if ([string]::IsNullOrWhiteSpace($RemoteHost)) {
    $RemoteHost = $projectEnvironment["FRP_SERVER_ADDR"]
}
if ([string]::IsNullOrWhiteSpace($BackupPath)) {
    $mangaRoot = $projectEnvironment["MANGA_ROOT"]
    if (-not [string]::IsNullOrWhiteSpace($mangaRoot)) {
        $BackupPath = Join-Path $mangaRoot "backups\comic_atlas-latest.sql.gz"
    }
}
if ([string]::IsNullOrWhiteSpace($RemoteHost)) {
    throw "未设置远端主机，请在 .env 中配置 FRP_SERVER_ADDR"
}
if ([string]::IsNullOrWhiteSpace($BackupPath)) {
    throw "未设置备份路径，请在 .env 中配置 MANGA_ROOT 或通过参数传入 BackupPath"
}

$sshPath = (Get-Command ssh.exe).Source
$backupDirectory = Split-Path -Parent $BackupPath
$temporaryPath = "$BackupPath.tmp"
$errorPath = "$BackupPath.error.tmp"
$previousPath = "$BackupPath.previous.tmp"

if (-not (Test-Path -LiteralPath $KeyPath)) {
    throw "未找到 MySQL 备份密钥：$KeyPath"
}

New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
Remove-Item -LiteralPath $temporaryPath, $errorPath, $previousPath -Force -ErrorAction SilentlyContinue

$sshArguments = @(
    "-T",
    "-i", $KeyPath,
    "-o", "BatchMode=yes",
    "-o", "ConnectTimeout=15",
    "-o", "ServerAliveInterval=30",
    "-o", "ServerAliveCountMax=3",
    "-o", "StrictHostKeyChecking=accept-new",
    "-o", "LogLevel=ERROR",
    "root@$RemoteHost"
)

try {
    $process = Start-Process -FilePath $sshPath `
        -ArgumentList $sshArguments `
        -RedirectStandardOutput $temporaryPath `
        -RedirectStandardError $errorPath `
        -PassThru `
        -Wait `
        -WindowStyle Hidden

    if ($process.ExitCode -ne 0) {
        $errorMessage = Get-Content -LiteralPath $errorPath -Raw -ErrorAction SilentlyContinue
        throw "远端 MySQL 导出失败，退出码 $($process.ExitCode)：$errorMessage"
    }

    $backupFile = Get-Item -LiteralPath $temporaryPath
    if ($backupFile.Length -lt 128) {
        throw "远端 MySQL 备份文件异常，大小仅 $($backupFile.Length) 字节"
    }

    $stream = [System.IO.File]::OpenRead($temporaryPath)
    try {
        $gzip = [System.IO.Compression.GZipStream]::new(
            $stream,
            [System.IO.Compression.CompressionMode]::Decompress
        )
        try {
            $buffer = [byte[]]::new(256)
            $bytesRead = $gzip.Read($buffer, 0, $buffer.Length)
            $header = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $bytesRead)
            if ($header -notmatch "MySQL dump") {
                throw "备份内容校验失败：未找到 MySQL dump 文件头"
            }
        }
        finally {
            $gzip.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }

    if (Test-Path -LiteralPath $BackupPath) {
        [System.IO.File]::Replace($temporaryPath, $BackupPath, $previousPath)
        Remove-Item -LiteralPath $previousPath -Force
    }
    else {
        Move-Item -LiteralPath $temporaryPath -Destination $BackupPath
    }

    $result = Get-Item -LiteralPath $BackupPath
    Write-Output "MySQL 备份完成：$($result.FullName)（$($result.Length) 字节）"
}
finally {
    Remove-Item -LiteralPath $temporaryPath, $errorPath, $previousPath -Force -ErrorAction SilentlyContinue
}
