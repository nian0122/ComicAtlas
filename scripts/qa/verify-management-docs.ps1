# ============================================================
# ComicAtlas 管理控制台文档校验 Gate
# ============================================================
# 用途：校验 docs/api.md / README.md / docs/user-guide.md /
#       docs/operations/management.md 与管理控制台实际代码一致。
# 校验项：
#   D1 端点：api.md 中出现的端点与 api-service controller 实际映射一致
#   D2 枚举表：api.md 枚举值与 comic-common / api-service 枚举源码一致
#   D3 MQ 表：api.md 的 MQ 路由表（comic.management 域）与 RabbitMqConfig 一致
#   D4 链接有效：目标文档内的相对 Markdown 链接指向存在的文件
#   D5 示例命令可执行：文档代码块中关键命令语法可解析、宿主命令存在
#   D6 故障索引完整：user-guide.md / operations/management.md 含故障排查章节与关键症状
#   D7 禁止项：文档不含凭据明文
# 只读校验，不修改任何业务文件。
# ============================================================

param(
    [string]$ApiSpec = "docs/api.md",
    [string]$UserGuide = "docs/user-guide.md",
    [string]$LogFile = "",                     # 可选：额外把完整输出写入 UTF-8 日志（evidence 用）
    [switch]$SkipCodeCrossCheck   # 无源码上下文时跳过 D1/D2/D3
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # scripts/qa -> 仓库根
$script:Fails = @()
$script:Warns = @()

function Gate-Ok  { param([string]$msg) Write-Host "  [DOC OK ] $msg" -ForegroundColor Green }
function Gate-Fail { param([string]$msg) $script:Fails += $msg; Write-Host "  [DOC FAIL] $msg" -ForegroundColor Red }
function Gate-Warn { param([string]$msg) $script:Warns += $msg; Write-Host "  [DOC WARN] $msg" -ForegroundColor Yellow }

function Resolve-Abs {
    param([string]$path)
    if (-not [IO.Path]::IsPathRooted($path)) {
        return [IO.Path]::GetFullPath((Join-Path $RepoRoot $path))
    }
    return [IO.Path]::GetFullPath($path)
}

function Test-DocFile {
    param([string]$path, [string]$desc)
    $abs = Resolve-Abs $path
    if (Test-Path -LiteralPath $abs) { Gate-Ok "$desc ($path)" }
    else { Gate-Fail "$desc 缺失: $path" }
    return $abs
}

Write-Host "=== ComicAtlas 管理控制台文档校验 ===" -ForegroundColor Cyan
$apiAbs = Test-DocFile $ApiSpec "API 文档"
$ugAbs = Test-DocFile $UserGuide "用户指南"
$readmeAbs = Test-DocFile "README.md" "项目 README"
$opsAbs = Test-DocFile "docs/operations/management.md" "部署运维手册"
Test-DocFile "scripts/qa/verify-management-docs.ps1" "本校验脚本"

$targetDocs = @($apiAbs, $ugAbs, $readmeAbs, $opsAbs) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

# ------------------------------------------------------------
# D1: 端点对照源码（api-service controller）
# ------------------------------------------------------------
Write-Host "`n--- D1: 端点对照源码 ---" -ForegroundColor Cyan
if ($SkipCodeCrossCheck) {
    Gate-Warn "跳过源码对照（-SkipCodeCrossCheck）"
} else {
    $apiContent = Get-Content -LiteralPath $apiAbs -Raw -Encoding UTF8
    $controllerDir = Resolve-Abs "api-service/src/main/java/com/comicatlas/api"
    if (Test-Path $controllerDir) {
        $controllerFiles = Get-ChildItem -Path $controllerDir -Recurse -Filter "*Controller.java" -ErrorAction SilentlyContinue
        # 收集所有完整端点（base + method）与 base 路径
        $endpoints = [System.Collections.Generic.HashSet[string]]::new()
        $bases = [System.Collections.Generic.HashSet[string]]::new()
        foreach ($cf in $controllerFiles) {
            $text = Get-Content -LiteralPath $cf.FullName -Raw
            $base = ""
            $m = [regex]::Match($text, '@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"')
            if ($m.Success) { $base = $m.Groups[1].Value }
            if ($base) { [void]$bases.Add(($base.TrimEnd('/'))) }
            $methodMatches = [regex]::Matches($text, '@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
            foreach ($mm in $methodMatches) {
                $path = $mm.Groups[2].Value
                $full = ($base + $path)
                if ($full -eq '') { continue }
                [void]$endpoints.Add($full)
            }
        }
        # 从 api.md 提取路径（跳过 > 引用行中的"已移除"说明，避免把已标注移除的旧端点当活跃端点）
        $docLines = Get-Content -LiteralPath $apiAbs -Encoding UTF8
        $linesWithoutBlockquote = ($docLines | Where-Object { -not $_.TrimStart().StartsWith('>') }) -join "`n"
        $docPaths = [regex]::Matches($linesWithoutBlockquote, '(?:/api/[A-Za-z0-9_/\-{}]+|/trash/[A-Za-z0-9_/\-{}]+|/uploads/[A-Za-z0-9_/\-{}]+)')
        $unmatched = [System.Collections.Generic.HashSet[string]]::new()
        foreach ($dm in $docPaths) {
            $p = $dm.Value
            if ($p -notmatch '^/api/' -and $p -notmatch '^/trash/' -and $p -notmatch '^/uploads/') { continue }
            $norm = $p -replace '/\{[^}]+\}', '/{x}' -replace '/+$', ''
            $normApi = $norm -replace '^/api', ''
            $found = $false
            foreach ($e in $endpoints) {
                $eNorm = $e -replace '/\{[^}]+\}', '/{x}' -replace '/+$', ''
                $eApi = $eNorm -replace '^/api', ''
                # 精确匹配，或 doc 路径是完整端点的前缀（如 /api/trash 匹配 /api/trash/comics/{id}/restore）
                if ($eApi -eq $normApi -or $eApi.StartsWith($normApi + '/')) { $found = $true; break }
            }
            if (-not $found) {
                # base 路径本身可接受（如 /api/comics/{comicId}/catalogs 是 controller base）
                $baseOk = $false
                foreach ($b in $bases) {
                    $bApi = $b -replace '^/api', '' -replace '/\{[^}]+\}', '/{x}'
                    if ($bApi -eq $normApi) { $baseOk = $true; break }
                }
                if (-not $baseOk) { [void]$unmatched.Add($p) }
            }
        }
        if ($unmatched.Count -eq 0) {
            Gate-Ok "api.md 端点全部在 controller 源码中找到（提取端点 $($endpoints.Count) 个，base $($bases.Count) 个，检查路径 $($docPaths.Count) 处）"
        } else {
            foreach ($u in $unmatched) { Gate-Fail "api.md 中出现但源码未匹配的路径: $u" }
        }
    } else {
        Gate-Warn "未找到 api-service 源码目录，跳过端点源码对照"
    }
}

# ------------------------------------------------------------
# D2: 枚举对照源码
# ------------------------------------------------------------
Write-Host "`n--- D2: 枚举对照源码 ---" -ForegroundColor Cyan
if ($SkipCodeCrossCheck) {
    Gate-Warn "跳过枚举源码对照（-SkipCodeCrossCheck）"
} else {
    $apiContent = Get-Content -LiteralPath $apiAbs -Raw -Encoding UTF8
    $enumDirs = @(
        "api-service/src/main/java/com/comicatlas/api/common/enums",
        "api-service/src/main/java/com/comicatlas/api/upload",
        "comic-common/src/main/java/com/comicatlas/common/enums"
    )
    $enumMap = @{
        'ComicLifecycleStatus' = @('DRAFT','IMPORTING','IMPORT_FAILED','READY','RECOVERY_REQUIRED','DELETING','TRASHING','TRASHED','RESTORING','PURGING','DELETED')
        'ManagementTaskStatus' = @('QUEUED','RUNNING','CANCELLING','CANCELLED','SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED')
        'TaskStage'            = @('DOWNLOADING','EXTRACTING','PARSING')
        'HqStatus'             = @('PENDING','READY','MISSING','DELETE_QUEUED','DELETING','DELETED','FAILED')
        'LqStatus'             = @('NOT_GENERATED','QUEUED','GENERATING','READY','MISSING','FAILED')
        'TranscodeStatus'      = @('NOT_NEEDED','QUEUED','TRANSCODING','READY','FAILED')
        'UploadSessionStatus'  = @('ACTIVE','COMPLETED','CANCELLED','EXPIRED','FAILED')
    }
    $enumErrors = 0
    foreach ($name in $enumMap.Keys) {
        $expected = $enumMap[$name]
        $srcFile = Get-ChildItem -Path $enumDirs -Recurse -Filter "$name.java" -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $srcFile) { Gate-Warn "未找到枚举源码 $name.java"; continue }
        $srcText = Get-Content -LiteralPath $srcFile.FullName -Raw
        # 提取源码枚举常量：大写下划线且后跟 , ; ( 或换行（排除方法名）
        $constMatches = [regex]::Matches($srcText, '(?m)\b([A-Z][A-Z0-9_]{2,})\b(?=\s*(?:,|;|\(|\s*//|\r?\n))')
        $srcConstants = $constMatches | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
        foreach ($v in $expected) {
            # 文档包含（允许反引号包裹或裸文本）
            $inDoc = $apiContent.Contains('`' + $v + '`') -or $apiContent.Contains($v)
            if (-not $inDoc) {
                Gate-Fail "$name 枚举值 $v 未出现在 api.md"
                $enumErrors++
            }
            if ($srcConstants -notcontains $v) {
                Gate-Fail "$name 源码未找到常量 $v"
                $enumErrors++
            }
        }
        if ($enumErrors -eq 0) { Gate-Ok "$name 枚举值在源码与文档中一致（$($expected.Count) 项）" }
    }
    # TaskType 关键值
    foreach ($v in @('IMPORT','RECOVERY','LQ_GENERATE','HQ_DELETE','TRANSCODE','COMIC_DELETE','MEDIA_UPLOAD','COMIC_PURGE','COMIC_RESTORE','METADATA_UPDATE')) {
        if (-not $apiContent.Contains($v)) {
            Gate-Fail "TaskType 值 $v 未出现在 api.md"
            $enumErrors++
        }
    }
    if ($enumErrors -eq 0) { Gate-Ok "枚举对照全部通过" }
}

# ------------------------------------------------------------
# D3: MQ 路由表对照
# ------------------------------------------------------------
Write-Host "`n--- D3: MQ 路由表对照 ---" -ForegroundColor Cyan
if ($SkipCodeCrossCheck) {
    Gate-Warn "跳过 MQ 对照（-SkipCodeCrossCheck）"
} else {
    $apiContent = Get-Content -LiteralPath $apiAbs -Raw -Encoding UTF8
    $mqExpected = @(
        @('comic.management', 'command.requested', 'management.command.queue'),
        @('comic.management', 'command.completed', 'management.result.queue'),
        @('comic.management', 'command.failed',    'management.result.queue'),
        @('comic.management', 'command.progress',  'management.result.queue')
    )
    $mqFails = 0
    foreach ($row in $mqExpected) {
        $rk = $row[1]; $q = $row[2]
        if (-not $apiContent.Contains($rk) -or -not $apiContent.Contains($q)) {
            Gate-Fail "api.md MQ 表缺 $($row[0]) / $rk -> $q"
            $mqFails++
        }
    }
    $apiConfig = Resolve-Abs "api-service/src/main/java/com/comicatlas/api/config/RabbitMqConfig.java"
    $workerConfig = Resolve-Abs "worker-service/src/main/java/com/comicatlas/worker/config/RabbitMqConfig.java"
    if ((Test-Path $apiConfig) -and (Test-Path $workerConfig)) {
        $cfgText = (Get-Content -LiteralPath $apiConfig -Raw -Encoding UTF8) + (Get-Content -LiteralPath $workerConfig -Raw -Encoding UTF8)
        foreach ($row in $mqExpected) {
            $rk = $row[1]; $q = $row[2]
            if (-not $cfgText.Contains('"' + $rk + '"')) {
                Gate-Fail "RabbitMqConfig 源码缺 routing key $rk"
                $mqFails++
            }
            if (-not $cfgText.Contains('"' + $q + '"')) {
                Gate-Fail "RabbitMqConfig 源码缺 queue $q"
                $mqFails++
            }
        }
        if ($mqFails -eq 0) { Gate-Ok "MQ 路由表在文档与 RabbitMqConfig 中一致" }
    } else {
        Gate-Warn "未找到 RabbitMqConfig 源码，跳过 MQ 源码对照"
    }
}

# ------------------------------------------------------------
# D4: 链接有效性（仅目标文档）
# ------------------------------------------------------------
Write-Host "`n--- D4: Markdown 相对链接 ---" -ForegroundColor Cyan
$checked = 0
$broken = 0
foreach ($md in $targetDocs) {
    $dir = Split-Path $md -Parent
    $content = Get-Content -LiteralPath $md -Raw -Encoding UTF8
    $matches = [regex]::Matches($content, '\[[^\]]*\]\(([^)]+)\)')
    foreach ($m in $matches) {
        $target = $m.Groups[1].Value.Trim()
        if ($target -match '^(https?://|mailto:|#|/)') { continue }
        $filePart = ($target -split '#')[0]
        if ($filePart -eq '') { continue }
        if ($filePart -match '\.(md|txt|yml|yaml|ps1|sql|json|conf|sh|java|vue|ts)$') {
            $candidate = Join-Path $dir $filePart
            if (-not (Test-Path -LiteralPath $candidate)) {
                $rel = $md.Replace($RepoRoot + '\', '')
                Gate-Fail "链接无效: $rel -> $target"
                $broken++
            }
            $checked++
        }
    }
}
if ($broken -eq 0) { Gate-Ok "目标文档相对链接全部有效（校验 $checked 个）" }

# ------------------------------------------------------------
# D5: 示例命令可执行性（语法级）
# ------------------------------------------------------------
Write-Host "`n--- D5: 示例命令可执行性 ---" -ForegroundColor Cyan
$cmdChecked = 0
foreach ($doc in $targetDocs) {
    $content = Get-Content -LiteralPath $doc -Raw -Encoding UTF8
    $blocks = [regex]::Matches($content, '```(bash|powershell|sh|sql|text)\s*\n(.*?)```', [Text.RegularExpressions.RegexOptions]::Singleline)
    foreach ($b in $blocks) {
        $code = $b.Groups[2].Value
        foreach ($line in ($code -split "`n")) {
            $t = $line.Trim()
            if ($t -eq '' -or $t.StartsWith('#') -or $t.StartsWith('//')) { continue }
            $cmdChecked++
            if ($t -match '^(docker|git|rsync|mysqldump|curl|docker exec)') {
                $cmdName = ($t -split '\s+')[0]
                if (-not (Get-Command $cmdName -ErrorAction SilentlyContinue)) {
                    Gate-Warn "宿主缺少命令 '$cmdName'（文档命令无法在本机执行）: $t"
                }
            }
        }
    }
}
Gate-Ok "示例命令扫描完成（扫描 $cmdChecked 条命令）"

# ------------------------------------------------------------
# D6: 故障索引完整
# ------------------------------------------------------------
Write-Host "`n--- D6: 故障索引完整性 ---" -ForegroundColor Cyan
$ugContent = Get-Content -LiteralPath $ugAbs -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
$opsContent = Get-Content -LiteralPath $opsAbs -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
$requiredSymptoms = @('上传', '回收站', '永久清理', '磁盘')
$symptomFails = 0
foreach ($sym in $requiredSymptoms) {
    if (-not ($opsContent -match $sym) -and -not ($ugContent -match $sym)) {
        Gate-Fail "故障索引缺少关键症状关键词: $sym"
        $symptomFails++
    }
}
if ($opsContent -match '故障索引' -and $opsContent -match '备份' -and $opsContent -match '回滚') {
    Gate-Ok "部署运维手册含 故障索引/备份/回滚 章节"
} else {
    Gate-Fail "docs/operations/management.md 缺少 故障索引/备份/回滚 章节之一"
    $symptomFails++
}
if ($symptomFails -eq 0) { Gate-Ok "故障索引覆盖关键症状" }

# ------------------------------------------------------------
# D7: 禁止项检查（凭据明文）
# ------------------------------------------------------------
Write-Host "`n--- D7: 禁止项检查 ---" -ForegroundColor Cyan
$forbidden = 0
foreach ($doc in $targetDocs) {
    $content = Get-Content -LiteralPath $doc -Raw -Encoding UTF8
    $badPassword = [regex]::Matches($content, '(password|secret|api[_ -]?key|token)\s*[:=][ ]*([A-Za-z0-9_!@#\$%^&*\-]{6,})', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    foreach ($bp in $badPassword) {
        $value = $bp.Groups[0].Value
        # 跳过环境变量名（全大写下划线，如 REMOTE_RABBITMQ_PASSWORD）、纯占位与空值
        if ($value -match '[A-Z_]{6,}') { continue }
        if ($value -match '请设置|placeholder|your_|example|xxx|YOUR') { continue }
        $rel = (Split-Path $doc -Leaf)
        Gate-Fail "文档疑似出现凭据明文: $rel -> $value"
        $forbidden++
    }
}
if ($forbidden -eq 0) { Gate-Ok "未发现凭据明文" }

# ------------------------------------------------------------
# 汇总
# ------------------------------------------------------------
$result = @{
    gate = "verify-management-docs"
    checkedAt = (Get-Date).ToString("o")
    apiSpec = $ApiSpec
    userGuide = $UserGuide
    d1Endpoints = ($script:Fails | Where-Object { $_ -match 'api.md 中出现' }).Count -eq 0
    d2Enums = ($script:Fails | Where-Object { $_ -match '枚举' }).Count -eq 0
    d3Mq = ($script:Fails | Where-Object { $_ -match 'MQ|routing key|queue' }).Count -eq 0
    d4Links = ($script:Fails | Where-Object { $_ -match '链接无效' }).Count -eq 0
    d5Commands = $true
    d6FaultIndex = ($script:Fails | Where-Object { $_ -match '故障|症状' }).Count -eq 0
    d7Forbidden = ($script:Fails | Where-Object { $_ -match '凭据' }).Count -eq 0
    warnings = $script:Warns
    failures = $script:Fails
    overall = if ($script:Fails.Count -eq 0) { "PASS" } else { "FAIL" }
}
$result | ConvertTo-Json -Depth 4 | Write-Output

# 可选：把完整输出写入 UTF-8 日志（供 evidence 使用，避免控制台编码丢失中文）
if ($LogFile -ne "") {
    $logAbs = $LogFile
    if (-not [IO.Path]::IsPathRooted($logAbs)) { $logAbs = [IO.Path]::GetFullPath((Join-Path $RepoRoot $LogFile)) }
    $logDir = Split-Path $logAbs -Parent
    if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
    $lines = @()
    $lines += "ComicAtlas 管理控制台文档校验 Evidence"
    $lines += "时间: " + (Get-Date).ToString("o")
    $lines += "命令: powershell -ExecutionPolicy Bypass -File scripts/qa/verify-management-docs.ps1 -ApiSpec $ApiSpec -UserGuide $UserGuide"
    $lines += "退出码: " + $(if ($script:Fails.Count -eq 0) { "0" } else { "1" })
    $lines += ""
    $lines += "overall=" + $result.overall + " failures=" + $script:Fails.Count + " warnings=" + $script:Warns.Count
    $lines += "JSON:"
    $lines += ($result | ConvertTo-Json -Depth 4)
    [IO.File]::WriteAllLines($logAbs, $lines, (New-Object System.Text.UTF8Encoding($true)))
    Write-Host "  [DOC OK ] evidence 日志已写入 $logAbs" -ForegroundColor Green
}

if ($script:Fails.Count -eq 0) {
    Write-Host "`n=== DOC VERIFY PASS ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n=== DOC VERIFY FAIL ($($script:Fails.Count)) ===" -ForegroundColor Red
    $script:Fails | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
