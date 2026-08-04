# ============================================================
# ComicAtlas 管理控制台 QA — 一条命令入口
# ============================================================
# 运行全链路 E2E runner，随后执行只读 final-gate，二者都通过才退出 0。
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/verify-management.ps1
#   powershell -ExecutionPolicy Bypass -File tools/verify-management.ps1 -EvidenceDir <dir>
# ============================================================

param(
    [string]$EvidenceDir = ".omo/evidence/comic-management-console/task-21-comic-management-console"
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Runner = Join-Path $RepoRoot "scripts\qa\run-management-e2e.ps1"
$Gate = Join-Path $RepoRoot "scripts\qa\final-gate.ps1"

Write-Host "=== ComicAtlas 管理控制台 QA 入口 ===" -ForegroundColor Cyan
Write-Host "EvidenceDir: $EvidenceDir"

if (-not (Test-Path $Runner)) { throw "缺少 runner: $Runner" }
if (-not (Test-Path $Gate)) { throw "缺少 gate: $Gate" }

$runnerExit = 0
& $Runner -EvidenceDir $EvidenceDir @PSBoundParameters
$runnerExit = $LASTEXITCODE

if ($runnerExit -ne 0) {
    Write-Host "`nrunner 退出码 $runnerExit —— 全链路存在失败，跳过 gate。" -ForegroundColor Red
    exit $runnerExit
}

Write-Host "`n=== 运行只读 final-gate ===" -ForegroundColor Cyan
& $Gate -EvidenceDir $EvidenceDir
$gateExit = $LASTEXITCODE

if ($gateExit -eq 0) {
    Write-Host "`n=== 验证通过：runner + gate 均 PASS ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n=== gate 失败 (exit=$gateExit) ===" -ForegroundColor Red
    exit $gateExit
}
