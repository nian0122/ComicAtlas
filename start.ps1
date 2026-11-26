#Requires -Version 5.1
<#
.SYNOPSIS
准备并启动 ComicAtlas（Docker Compose）。
.DESCRIPTION
首次运行会创建 .env 和存储目录下的 imports、media，然后构建并启动。
已有 .env 不会被覆盖；升级时保留 .env 和存储目录，替换源码后再次执行。
.EXAMPLE
.\start.ps1
.EXAMPLE
.\start.ps1 -NoBuild
.EXAMPLE
.\start.ps1 -StorageRoot D:\ComicAtlas-storage
#>
[CmdletBinding()]
param(
    [switch]$NoBuild,
    [switch]$PrepareOnly,
    [string]$StorageRoot
)

$ErrorActionPreference = 'Stop'

$projectDir = $PSScriptRoot
if (-not $projectDir) { $projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
$envPath = Join-Path $projectDir '.env'
$examplePath = Join-Path $projectDir '.env.example'
$composePath = Join-Path $projectDir 'compose.yaml'

function Fail([string]$Message) {
    Write-Host "错误：$Message" -ForegroundColor Red
    exit 1
}

function Get-DotEnvValue([string]$Path, [string]$Key) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    $pattern = '^\s*' + [regex]::Escape($Key) + '\s*=\s*(.*)$'
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match $pattern) {
            $value = $Matches[1].Trim()
            if ($value.Length -ge 2) {
                $first = $value.Substring(0, 1)
                $last = $value.Substring($value.Length - 1, 1)
                if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
            }
            return $value
        }
    }
    return $null
}

function Set-DotEnvValue([string]$Path, [string]$Key, [string]$Value) {
    $lines = @([IO.File]::ReadAllLines($Path))
    $pattern = '^\s*' + [regex]::Escape($Key) + '\s*='
    $found = $false
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match $pattern) {
            $lines[$index] = "$Key=$Value"
            $found = $true
        }
    }
    if (-not $found) { $lines += "$Key=$Value" }
    $content = ($lines -join "`n") + "`n"
    [IO.File]::WriteAllText($Path, $content, (New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false))
}

function Resolve-StorageRoot([string]$Value) {
    if (-not $Value) { return $null }
    if (-not [IO.Path]::IsPathRooted($Value)) { $Value = Join-Path $projectDir $Value }
    $full = [IO.Path]::GetFullPath($Value).Replace('\', '/')
    if ($full -eq '/' -or $full -match '^[A-Za-z]:$') { return "$full/" }
    return $full.TrimEnd('/')
}

if (-not (Test-Path -LiteralPath $composePath -PathType Leaf)) { Fail '缺少 compose.yaml' }
if (-not (Test-Path -LiteralPath $examplePath -PathType Leaf)) { Fail '缺少 .env.example' }

$root = Resolve-StorageRoot $StorageRoot
if (Test-Path -LiteralPath $envPath -PathType Leaf) {
    $existing = Get-DotEnvValue $envPath 'COMICATLAS_STORAGE_ROOT'
    if (-not $existing) {
        if (-not $root) { Fail '已有 .env 缺少 COMICATLAS_STORAGE_ROOT；请补齐或删除 .env 让脚本生成。' }
        Set-DotEnvValue $envPath 'COMICATLAS_STORAGE_ROOT' $root
        $existing = $root
    }
    $existing = Resolve-StorageRoot $existing
    if ($root -and $root -ne $existing) {
        Fail '已有 .env 的 COMICATLAS_STORAGE_ROOT 与 -StorageRoot 不一致；请直接修改 .env 或删除后重试。'
    }
    $root = $existing
} else {
    if (-not $root) { $root = Resolve-StorageRoot 'storage' }
    Copy-Item -LiteralPath $examplePath -Destination $envPath
    Set-DotEnvValue $envPath 'COMICATLAS_STORAGE_ROOT' $root
    Write-Host "已创建 .env，存储目录：$root"
}

if (-not $root) { Fail '无法确定存储目录' }
foreach ($name in @('imports', 'media')) {
    $dir = "$root/$name"
    if (-not (Test-Path -LiteralPath $dir -PathType Container)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        Write-Host "已创建目录：$dir"
    }
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) { Fail '未找到 docker，请先安装 Docker Desktop 或 Docker Engine。' }
& docker compose version *> $null
if ($LASTEXITCODE -ne 0) { Fail '未找到 docker compose 插件；请安装 Docker Compose v2。' }

$compose = @('compose', '--project-directory', $projectDir, '--env-file', $envPath, '-f', $composePath)

if ($PrepareOnly) {
    & docker @compose '--profile' 'ai' 'config' '--quiet'
    if ($LASTEXITCODE -ne 0) { Fail 'Compose 配置校验失败。' }
    Write-Host "准备完成：$envPath；存储目录：$root"
    exit 0
}

& docker info *> $null
if ($LASTEXITCODE -ne 0) { Fail '无法连接 Docker daemon；请先启动 Docker Desktop。' }

$upHelp = (& docker @compose 'up' '--help' 2>&1 | Out-String)
if ($upHelp -notmatch '--wait') { Fail '当前 docker compose 不支持 --wait；请升级到 Compose v2.17 或更高版本。' }

if ($NoBuild) {
    $images = & docker @compose 'config' '--images' 2>$null
    if ($LASTEXITCODE -ne 0) { Fail '无法读取 Compose 镜像列表。' }
    foreach ($image in @($images)) {
        if (-not $image) { continue }
        & docker image inspect $image *> $null
        if ($LASTEXITCODE -ne 0) { Fail "缺少镜像 $image；请去掉 -NoBuild 执行一次完整构建。" }
    }
}

if ($NoBuild) {
    Write-Host '启动 ComicAtlas...'
} else {
    Write-Host '构建并启动 ComicAtlas（首次构建需要网络）...'
}
$upArgs = @('up', '-d', '--wait', '--wait-timeout', '180')
if ($NoBuild) { $upArgs += '--no-build' } else { $upArgs += '--build' }
& docker @compose @upArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host '启动失败。查看日志：' -ForegroundColor Red
    Write-Host "  docker compose --project-directory `"$projectDir`" --env-file `"$envPath`" -f `"$composePath`" logs --tail=100" -ForegroundColor Red
    exit 1
}

$webPort = Get-DotEnvValue $envPath 'COMICATLAS_WEB_PORT'
if (-not $webPort) { $webPort = '18078' }
$adminPort = Get-DotEnvValue $envPath 'COMICATLAS_ADMIN_PORT'
if (-not $adminPort) { $adminPort = '18079' }
Write-Host 'ComicAtlas 已启动。'
Write-Host "阅读：http://127.0.0.1:$webPort/"
Write-Host "管理：http://127.0.0.1:$adminPort/manage"
Write-Host "导入：把文件或目录放入 $root/imports，管理后台导入时填写该目录内的完整路径。"
