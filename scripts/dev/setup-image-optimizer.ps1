# ComicAtlas - 安装项目本地 libjpeg-turbo
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtimeDirectory = Join-Path $repoRoot "worker-service\tools\image-optimizer\.runtime\libjpeg-turbo"
$binDirectory = Join-Path $runtimeDirectory "bin"
$installerPath = Join-Path $env:TEMP "libjpeg-turbo-3.2.0-vc-x64.exe"
$downloadUrl = "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0-vc-x64.exe"
$expectedSha256 = "662761d8ba8dae04aec74023ebaeceb856c2b56b9b59cfd180759d26300dda42"

New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
if (-not (Test-Path (Join-Path $binDirectory "cjpeg.exe"))) {
    Write-Host "正在下载官方 libjpeg-turbo Windows x64 安装包..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $downloadUrl -OutFile $installerPath
    $actualSha256 = (Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "libjpeg-turbo 安装包 SHA-256 校验失败，实际值：$actualSha256"
    }
    Write-Host "正在安装到项目本地运行目录..." -ForegroundColor Cyan
    Start-Process -FilePath $installerPath -ArgumentList "/S", "/D=$runtimeDirectory" -Verb RunAs -Wait
}

$djpegPath = Join-Path $binDirectory "djpeg.exe"
$cjpegPath = Join-Path $binDirectory "cjpeg.exe"
if ((-not (Test-Path $djpegPath)) -or (-not (Test-Path $cjpegPath))) {
    throw "libjpeg-turbo 安装完成但未找到 cjpeg.exe/djpeg.exe：$binDirectory"
}

[Environment]::SetEnvironmentVariable("IMAGE_DJPEG_PATH", $djpegPath, "Process")
[Environment]::SetEnvironmentVariable("IMAGE_CJPEG_PATH", $cjpegPath, "Process")
Write-Host "安装完成：$binDirectory" -ForegroundColor Green
Write-Host "启动开发环境时 scripts/dev/start-dev.ps1 会自动使用该目录，不会修改系统 Path。" -ForegroundColor Green
