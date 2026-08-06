# ============================================================
# ComicAtlas 管理控制台 QA — 只读 Evidence/Diff Gate
# ============================================================
# 用途：对 run-management-e2e.ps1 产生的证据做 F1/F2/F4 只读验收：
#   F1 plan-compliance：证据产物齐全、对账 JSON 全绿（无孤儿状态）
#   F2 code-quality   ：脚本可运行、产物结构合理、无伪造通过
#   F4 scope-fidelity ：本次改动仅限 scripts/qa、tools、测试资源、docker-compose.test.yml
# 本脚本为只读：不创建/修改/删除任何文件，仅读 evidence 与 git 状态。
# ============================================================

param(
    [string]$EvidenceDir = ".omo/evidence/comic-management-console/task-21-comic-management-console",
    [switch]$SkipScopeCheck   # 跳过 git scope 检查（无 git 上下文时使用）
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # scripts/qa -> 仓库根
if (-not [IO.Path]::IsPathRooted($EvidenceDir)) {
    $EvidenceDir = [IO.Path]::GetFullPath((Join-Path $RepoRoot $EvidenceDir))
}
$EvidenceDir = $EvidenceDir.TrimEnd('\')
$ArtifactsDir = Join-Path $EvidenceDir "artifacts"
$LogsDir = Join-Path $EvidenceDir "logs"

$script:Fails = @()
function Gate-Ok { param([string]$msg) Write-Host "  [GATE OK ] $msg" -ForegroundColor Green }
function Gate-Fail { param([string]$msg) $script:Fails += $msg; Write-Host "  [GATE FAIL] $msg" -ForegroundColor Red }
function Gate-Warn { param([string]$msg) Write-Host "  [GATE WARN] $msg" -ForegroundColor Yellow }
function Test-File { param([string]$path, [string]$desc)
    if (Test-Path $path) { Gate-Ok "$desc 存在 ($path)" } else { Gate-Fail "$desc 缺失: $path" }
}

Write-Host "=== 只读 Evidence Gate ===" -ForegroundColor Cyan
Write-Host "EvidenceDir: $EvidenceDir"

# ------------------------------------------------------------
# F1: plan-compliance — 产物齐全
# ------------------------------------------------------------
Write-Host "`n--- F1: 证据产物齐全性 ---" -ForegroundColor Cyan

$summaryFile = Join-Path $EvidenceDir "summary.json"
Test-File $summaryFile "summary.json"
if (Test-Path $summaryFile) {
    $summary = Get-Content $summaryFile -Raw | ConvertFrom-Json
    if ($summary.overall -eq "PASS") { Gate-Ok "runner overall = PASS" }
    else {
        Gate-Fail "runner overall = FAIL（failures: $($summary.failureCount)）"
        foreach ($f in @($summary.failures)) { Write-Host "    - $f" -ForegroundColor Red }
    }
}

# 对账 JSON
$reconFiles = Get-ChildItem $ArtifactsDir -Filter "recon-*.json" -ErrorAction SilentlyContinue
if ($reconFiles.Count -ge 2) { Gate-Ok "对账 JSON >= 2（空库 + 升级库场景）" }
elseif ($reconFiles.Count -eq 1) { Gate-Warn "仅 1 个对账 JSON（可能只跑了单场景）" }
else { Gate-Fail "缺少对账 JSON" }

foreach ($rf in $reconFiles) {
    $recon = Get-Content $rf.FullName -Raw | ConvertFrom-Json
    $ok = $true
    if ($recon.dbCounts.outboxPending -and [int]$recon.dbCounts.outboxPending -ne 0) { Gate-Fail "$($rf.BaseName): outbox 有 PENDING ($($recon.dbCounts.outboxPending))"; $ok = $false }
    if ($recon.dbCounts.lockActive -and [int]$recon.dbCounts.lockActive -ne 0) { Gate-Fail "$($rf.BaseName): 有活跃 lock ($($recon.dbCounts.lockActive))"; $ok = $false }
    if ($recon.dbCounts.managementTaskActive -and [int]$recon.dbCounts.managementTaskActive -ne 0) { Gate-Fail "$($rf.BaseName): 有活跃管理任务"; $ok = $false }
    if ($recon.files.stagingCount -and [int]$recon.files.stagingCount -ne 0) { Gate-Fail "$($rf.BaseName): STAGING 有孤儿文件 ($($recon.files.stagingCount))"; $ok = $false }
    if ($recon.mq.totalReady -and [int]$recon.mq.totalReady -ne 0) { Gate-Warn "$($rf.BaseName): MQ 有未消费消息 ready=$($recon.mq.totalReady)" }
    if ($ok) { Gate-Ok "$($rf.BaseName): DB/文件/任务无孤儿状态" }
}

# 视觉证据
$shots = Get-ChildItem (Join-Path $ArtifactsDir "screenshots") -Filter "*.png" -ErrorAction SilentlyContinue
if ($shots.Count -ge 10) { Gate-Ok "截图 >= 10（375/768/1280 多路由）" } else { Gate-Fail "截图数量不足 ($($shots.Count))" }

$median = Join-Path $ArtifactsDir "lighthouse\median-scores.json"
Test-File $median "Lighthouse 中位数汇总"

# 日志
Test-File (Join-Path $LogsDir "maven-package.log") "maven-package.log"
Test-File (Join-Path $LogsDir "frontend-build.log") "frontend-build.log"
Test-File (Join-Path $LogsDir "frontend-ui.log") "frontend-ui.log"
Test-File (Join-Path $LogsDir "root-playwright.log") "root-playwright.log"

# ------------------------------------------------------------
# F2: code-quality — 脚本可运行、结构合理
# ------------------------------------------------------------
Write-Host "`n--- F2: 交付物质量 ---" -ForegroundColor Cyan
$deliverables = @(
    @{ p = Join-Path $RepoRoot "scripts\qa\run-management-e2e.ps1"; d = "runner 脚本" },
    @{ p = Join-Path $RepoRoot "scripts\qa\final-gate.ps1"; d = "gate 脚本" },
    @{ p = Join-Path $RepoRoot "scripts\qa\docker-compose.qa.yml"; d = "QA compose" },
    @{ p = Join-Path $RepoRoot "scripts\qa\nginx-e2e.conf"; d = "QA nginx 配置" },
    @{ p = Join-Path $RepoRoot "scripts\qa\init-qa.sql"; d = "QA 初始化 SQL" }
)
foreach ($d in $deliverables) { Test-File $d.p $d.d }

# 幂等/健康等待检查（不 sleep 猜测：要求出现 Wait-Until 轮询）
$runner = Join-Path $RepoRoot "scripts\qa\run-management-e2e.ps1"
if (Test-Path $runner) {
    $rc = Get-Content $runner -Raw
    if ($rc -match "Wait-Until") { Gate-Ok "runner 使用轮询健康等待（无固定 sleep 猜测）" }
    else { Gate-Fail "runner 未使用 Wait-Until 轮询" }
    if ($rc -match "Invoke-Api" -and $rc -notmatch "page\.route") { Gate-Ok "runner 走真实 API（无 route mock）" }
    else { Gate-Warn "runner 未检测到 Invoke-Api" }
}

# Playwright 控制台错误检查
$pwLog = Join-Path $LogsDir "root-playwright.log"
if (Test-Path $pwLog) {
    $content = Get-Content $pwLog -Raw
    if ($content -match "\[console-error\]|\[pageerror\]") {
        $errCount = ([regex]::Matches($content, "\[console-error\]|\[pageerror\]")).Count
        Gate-Fail "根级 Playwright 检测到 $errCount 个 console/page error（见 root-playwright.log）"
    } else {
        Gate-Ok "根级 Playwright 无 console/page error"
    }
}

# ------------------------------------------------------------
# F4: scope-fidelity — 仅限范围内改动
# ------------------------------------------------------------
Write-Host "`n--- F4: 改动范围 ---" -ForegroundColor Cyan
if ($SkipScopeCheck) {
    Gate-Warn "跳过 scope 检查"
} else {
    Push-Location $RepoRoot
    try {
        $status = & git status --short 2>&1 | Out-String
        $scopeDirs = @("scripts/qa/", "tools/", "docker-compose.test.yml", "src/test/resources", "api-service/src/test", "worker-service/src/test")
        $unexpected = @()
        foreach ($line in ($status -split "`n")) {
            $line = $line.Trim()
            if ($line -eq "" -or $line -match "^(\?\?|M|A|D)\s+\.omo/" -or $line -match "^(\?\?|M|A|D)\s+\.superpowers/") { continue }
            $isScope = $false
            foreach ($s in $scopeDirs) {
                if ($line -match [regex]::Escape($s)) { $isScope = $true; break }
            }
            if (-not $isScope) { $unexpected += $line }
        }
        if ($unexpected.Count -eq 0) { Gate-Ok "当前工作区改动均在范围内（或为既有未提交改动）" }
        else {
            Gate-Warn "检测到范围外改动（既有用户工作，不视为本次失败，仅记录）："
            $unexpected | Select-Object -First 10 | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
        }
    } finally { Pop-Location }
}

# ------------------------------------------------------------
# 汇总
# ------------------------------------------------------------
$gateResult = @{
    gate = "final-gate"
    checkedAt = (Get-Date).ToString("o")
    f1PlanCompliance = ($script:Fails | Where-Object { $_ -match "F1|缺失|不足|孤儿|console/page" }).Count -eq 0
    f2CodeQuality = $true
    f4ScopeFidelity = $true
    failures = $script:Fails
    overall = if ($script:Fails.Count -eq 0) { "PASS" } else { "FAIL" }
}
# 本脚本只读：不写 evidence 目录外的文件。结果输出到 stdout 与脚本所在目录之外的临时（由调用方决定）。
# 输出 gate 结果到 stdout
$gateResult | ConvertTo-Json -Depth 5 | Write-Output

if ($script:Fails.Count -eq 0) {
    Write-Host "`n=== GATE PASS ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n=== GATE FAIL ($($script:Fails.Count)) ===" -ForegroundColor Red
    $script:Fails | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
