<#
.SYNOPSIS
    Backfill legacy LQ files into ComicAtlas managed storage after HQ batch import.

.DESCRIPTION
    This script reads imported comics from MySQL, derives each legacy LQ directory
    from import_task.source_path by replacing the top h_* directory with l_*,
    copies matching legacy LQ images into the managed LQ root, and updates page
    lq_root/lq_path/lq_status/lq_size plus comic.lq_size.

    Default mode is DryRun. Run without -DryRun only after checking the CSV report.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass `
      -File .\tools\migration\backfill-legacy-lq.ps1 `
      -LegacyRoot "F:\games\comics" `
      -MangaRoot "F:\manga" `
      -DryRun
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$LegacyRoot = "F:\games\comics",

    [Parameter(Mandatory = $false)]
    [string]$MangaRoot = "",

    [Parameter(Mandatory = $false)]
    [string]$MysqlHost = "127.0.0.1",

    [Parameter(Mandatory = $false)]
    [int]$MysqlPort = 3306,

    [Parameter(Mandatory = $false)]
    [string]$MysqlDatabase = "comic_atlas",

    [Parameter(Mandatory = $false)]
    [string]$MysqlUser = "root",

    [Parameter(Mandatory = $false)]
    [string]$MysqlPassword = "root",

    [Parameter(Mandatory = $false)]
    [string]$DockerMysqlContainer = "comicatlas-mysql",

    [Parameter(Mandatory = $false)]
    [string]$ReportPath = "",

    [Parameter(Mandatory = $false)]
    [string]$SqlPath = "",

    [Parameter(Mandatory = $false)]
    [ValidateSet("Exact", "Sequence")]
    [string]$MatchMode = "Exact",

    [Parameter(Mandatory = $false)]
    [switch]$DryRun,

    [Parameter(Mandatory = $false)]
    [switch]$CopyFiles,

    [Parameter(Mandatory = $false)]
    [switch]$ExecuteSql,

    [Parameter(Mandatory = $false)]
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$imageExtensions = @(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif")

function Get-DefaultMangaRoot {
    $envPath = Join-Path -Path (Get-Location) -ChildPath ".env"
    if (Test-Path -LiteralPath $envPath -PathType Leaf) {
        $line = Get-Content -LiteralPath $envPath | Where-Object { $_ -match "^MANGA_ROOT=" } | Select-Object -First 1
        if ($line) {
            return $line.Substring("MANGA_ROOT=".Length).Trim()
        }
    }
    return "F:/manga"
}

function Convert-ToWindowsPathText {
    param([Parameter(Mandatory = $true)][string]$PathText)
    return $PathText.Replace("/", "\")
}

function Convert-ToDbPathText {
    param([Parameter(Mandatory = $true)][string]$PathText)
    return $PathText.Replace("\", "/")
}

function Escape-SqlString {
    param([Parameter(Mandatory = $true)][string]$Value)
    return $Value.Replace("\", "\\").Replace("'", "''")
}

function Get-FileBaseName {
    param([Parameter(Mandatory = $true)][string]$PathText)
    return [System.IO.Path]::GetFileNameWithoutExtension($PathText)
}

function Get-LqRelativePath {
    param([Parameter(Mandatory = $true)][string]$HqPath)
    $normalized = Convert-ToDbPathText -PathText $HqPath
    $dir = [System.IO.Path]::GetDirectoryName($normalized)
    if ($null -eq $dir) {
        $dir = ""
    }
    $dir = $dir.Replace("\", "/")
    $baseName = Get-FileBaseName -PathText $normalized
    if ([string]::IsNullOrWhiteSpace($dir)) {
        return $baseName + ".webp"
    }
    return $dir + "/" + $baseName + ".webp"
}

function Resolve-LegacyLqRoot {
    param(
        [Parameter(Mandatory = $true)][string]$LegacyRootPath,
        [Parameter(Mandatory = $true)][string]$HqSourcePath
    )

    $legacyFull = (Resolve-Path -LiteralPath $LegacyRootPath).Path.TrimEnd("\", "/")
    $sourceFull = (Convert-ToWindowsPathText -PathText $HqSourcePath).TrimEnd("\", "/")

    if (-not $sourceFull.StartsWith($legacyFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ""
    }

    $relative = $sourceFull.Substring($legacyFull.Length).TrimStart("\", "/")
    $parts = @($relative -split "[\\/]+" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($parts.Count -lt 1) {
        return ""
    }

    $top = $parts[0]
    if (-not $top.StartsWith("h_", [System.StringComparison]::OrdinalIgnoreCase)) {
        return ""
    }

    $parts[0] = "l_" + $top.Substring(2)
    $lqPath = $legacyFull
    foreach ($part in $parts) {
        $lqPath = Join-Path -Path $lqPath -ChildPath ([string]$part)
    }
    return $lqPath
}

function Get-LegacyLqImageMap {
    param([Parameter(Mandatory = $true)][string]$LqRootPath)

    $map = @{}
    if (-not (Test-Path -LiteralPath $LqRootPath -PathType Container)) {
        return $map
    }

    Get-ChildItem -LiteralPath $LqRootPath -File -Recurse -Force |
        Where-Object { $imageExtensions -contains $_.Extension.ToLowerInvariant() } |
        Sort-Object FullName |
        ForEach-Object {
            $baseName = $_.BaseName.ToLowerInvariant()
            if (-not $map.ContainsKey($baseName)) {
                $map[$baseName] = New-Object System.Collections.Generic.List[object]
            }
            $map[$baseName].Add($_)
        }

    return $map
}

function Get-LegacyLqImageList {
    param([Parameter(Mandatory = $true)][string]$LqRootPath)

    if (-not (Test-Path -LiteralPath $LqRootPath -PathType Container)) {
        return @()
    }

    return @(
        Get-ChildItem -LiteralPath $LqRootPath -File -Recurse -Force |
            Where-Object { $imageExtensions -contains $_.Extension.ToLowerInvariant() } |
            Sort-Object FullName
    )
}

function Invoke-MysqlText {
    param(
        [Parameter(Mandatory = $true)][string]$SqlText,
        [Parameter(Mandatory = $false)][switch]$NoColumnNames
    )

    $tempSql = [System.IO.Path]::GetTempFileName()
    Set-Content -LiteralPath $tempSql -Value $SqlText -Encoding UTF8
    try {
        $mysql = Get-Command mysql -ErrorAction SilentlyContinue
        if ($mysql) {
            $args = @(
                "--host=$MysqlHost",
                "--port=$MysqlPort",
                "--user=$MysqlUser",
                "--password=$MysqlPassword",
                "--database=$MysqlDatabase",
                "--default-character-set=utf8mb4",
                "--batch",
                "--raw"
            )
            if ($NoColumnNames) {
                $args += "--skip-column-names"
            }
            $args += "--execute=$SqlText"
            return & $mysql.Source @args
        }

        $docker = Get-Command docker -ErrorAction SilentlyContinue
        if (-not $docker) {
            throw "Neither mysql CLI nor docker CLI was found."
        }

        $dockerArgs = @(
            "exec",
            "-i",
            $DockerMysqlContainer,
            "mysql",
            "--user=$MysqlUser",
            "--password=$MysqlPassword",
            "--database=$MysqlDatabase",
            "--default-character-set=utf8mb4",
            "--batch",
            "--raw"
        )
        if ($NoColumnNames) {
            $dockerArgs += "--skip-column-names"
        }

        Get-Content -LiteralPath $tempSql | & $docker.Source @dockerArgs
    } finally {
        Remove-Item -LiteralPath $tempSql -Force -ErrorAction SilentlyContinue
    }
}

function Read-ImportedPageRows {
    $legacyDbPath = Convert-ToDbPathText -PathText ((Resolve-Path -LiteralPath $LegacyRoot).Path.TrimEnd("\", "/"))
    $legacySql = Escape-SqlString -Value ($legacyDbPath + "/h_%")
    $sql = @"
SELECT
  c.id AS comic_id,
  c.title AS comic_title,
  it.source_path AS source_path,
  ch.id AS chapter_id,
  ch.global_order AS global_order,
  p.id AS page_id,
  p.page_number AS page_number,
  p.hq_path AS hq_path,
  p.media_type AS media_type
FROM import_task it
JOIN comic c ON c.id = it.comic_id
JOIN chapter ch ON ch.comic_id = c.id
JOIN page p ON p.chapter_id = ch.id
WHERE it.status = 'SUCCESS'
  AND it.source_type IN ('REGISTER', 'DIRECTORY')
  AND REPLACE(it.source_path, '\\', '/') LIKE '$legacySql'
  AND p.media_type = 'IMAGE'
ORDER BY c.id, ch.global_order, p.page_number;
"@

    $lines = @(Invoke-MysqlText -SqlText $sql -NoColumnNames)
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $cols = @($line -split "`t", 9)
        if ($cols.Count -lt 9) {
            continue
        }
        $rows.Add([pscustomobject]@{
            ComicId = [long]$cols[0]
            ComicTitle = $cols[1]
            SourcePath = $cols[2]
            ChapterId = [long]$cols[3]
            GlobalOrder = [int]$cols[4]
            PageId = [long]$cols[5]
            PageNumber = [int]$cols[6]
            HqPath = $cols[7]
            MediaType = $cols[8]
        })
    }
    return $rows.ToArray()
}

if ([string]::IsNullOrWhiteSpace($MangaRoot)) {
    $MangaRoot = Get-DefaultMangaRoot
}

if (-not (Test-Path -LiteralPath $LegacyRoot -PathType Container)) {
    throw "LegacyRoot does not exist: $LegacyRoot"
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $ReportPath = Join-Path -Path (Get-Location) -ChildPath "tools\legacy-lq-backfill-report-$timestamp.csv"
}

if ([string]::IsNullOrWhiteSpace($SqlPath)) {
    $timestampForSql = Get-Date -Format "yyyyMMdd-HHmmss"
    $SqlPath = Join-Path -Path (Get-Location) -ChildPath "tools\legacy-lq-backfill-$timestampForSql.sql"
}

if ($DryRun) {
    $CopyFiles = $false
    $ExecuteSql = $false
}

if ((-not $DryRun) -and (-not $CopyFiles -or -not $ExecuteSql) -and (-not $Force)) {
    throw "Non-DryRun requires -CopyFiles and -ExecuteSql, or use -Force to bypass this guard."
}

$managedLqRoot = Join-Path -Path (Convert-ToWindowsPathText -PathText $MangaRoot) -ChildPath "lq"
$rows = @(Read-ImportedPageRows)

$mapsBySource = @{}
$listsBySource = @{}
$sequenceIndexBySource = @{}
$sequenceCountBySource = @{}
$reportRows = New-Object System.Collections.Generic.List[object]
$updates = New-Object System.Collections.Generic.List[string]
$copiedCount = 0
$matchedCount = 0
$missingCount = 0
$duplicateCount = 0

foreach ($row in $rows) {
    $lqRoot = Resolve-LegacyLqRoot -LegacyRootPath $LegacyRoot -HqSourcePath $row.SourcePath
    $sourceKey = $row.SourcePath
    if (-not $mapsBySource.ContainsKey($sourceKey)) {
        $mapsBySource[$sourceKey] = Get-LegacyLqImageMap -LqRootPath $lqRoot
    }
    if ($MatchMode -eq "Sequence" -and -not $listsBySource.ContainsKey($sourceKey)) {
        $listsBySource[$sourceKey] = Get-LegacyLqImageList -LqRootPath $lqRoot
        $sequenceIndexBySource[$sourceKey] = 0
        $sequenceCountBySource[$sourceKey] = @($rows | Where-Object { $_.SourcePath -eq $sourceKey }).Count
    }

    $lqMap = $mapsBySource[$sourceKey]
    $baseName = (Get-FileBaseName -PathText $row.HqPath).ToLowerInvariant()
    $matches = @()
    if ($MatchMode -eq "Exact" -and $lqMap.ContainsKey($baseName)) {
        $matches = @($lqMap[$baseName].ToArray())
    }

    $status = "MISSING"
    $legacyFile = ""
    $legacySize = 0L
    $targetRelative = Get-LqRelativePath -HqPath $row.HqPath
    $targetFile = Join-Path -Path $managedLqRoot -ChildPath (Convert-ToWindowsPathText -PathText $targetRelative)

    if ($MatchMode -eq "Sequence") {
        $lqFiles = @($listsBySource[$sourceKey])
        $sequenceIndex = [int]$sequenceIndexBySource[$sourceKey]
        $sequenceIndexBySource[$sourceKey] = $sequenceIndex + 1

        if ($sequenceIndex -lt $lqFiles.Count) {
            $status = "SEQUENCE_MATCHED"
            if ($lqFiles.Count -ne [int]$sequenceCountBySource[$sourceKey]) {
                $status = "SEQUENCE_COUNT_MISMATCH"
            }
            $legacyFile = $lqFiles[$sequenceIndex].FullName
            $legacySize = [long]$lqFiles[$sequenceIndex].Length
            $matchedCount++

            if ($CopyFiles) {
                $targetDir = Split-Path -Path $targetFile -Parent
                if (-not (Test-Path -LiteralPath $targetDir -PathType Container)) {
                    New-Item -ItemType Directory -Path $targetDir | Out-Null
                }
                Copy-Item -LiteralPath $legacyFile -Destination $targetFile -Force
                $copiedCount++
            }

            $escapedLqPath = Escape-SqlString -Value $targetRelative
            $updates.Add("UPDATE page SET lq_root='LQ', lq_path='$escapedLqPath', lq_status='READY', lq_size=$legacySize WHERE id=$($row.PageId);")
        } else {
            $missingCount++
        }
    } elseif ($matches.Count -eq 1) {
        $status = "MATCHED"
        $legacyFile = $matches[0].FullName
        $legacySize = [long]$matches[0].Length
        $matchedCount++

        if ($CopyFiles) {
            $targetDir = Split-Path -Path $targetFile -Parent
            if (-not (Test-Path -LiteralPath $targetDir -PathType Container)) {
                New-Item -ItemType Directory -Path $targetDir | Out-Null
            }
            Copy-Item -LiteralPath $legacyFile -Destination $targetFile -Force
            $copiedCount++
        }

        $escapedLqPath = Escape-SqlString -Value $targetRelative
        $updates.Add("UPDATE page SET lq_root='LQ', lq_path='$escapedLqPath', lq_status='READY', lq_size=$legacySize WHERE id=$($row.PageId);")
    } elseif ($matches.Count -gt 1) {
        $status = "DUPLICATE_BASE_NAME"
        $duplicateCount++
    } else {
        $missingCount++
    }

    $reportRows.Add([pscustomobject]@{
        ComicId = $row.ComicId
        ComicTitle = $row.ComicTitle
        SourcePath = $row.SourcePath
        LegacyLqRoot = $lqRoot
        ChapterId = $row.ChapterId
        GlobalOrder = $row.GlobalOrder
        PageId = $row.PageId
        PageNumber = $row.PageNumber
        HqPath = $row.HqPath
        TargetLqPath = $targetRelative
        LegacyLqFile = $legacyFile
        LegacyLqSize = $legacySize
        Status = $status
    })
}

$comicIds = @($reportRows | Where-Object { $_.Status -eq "MATCHED" } | Select-Object -ExpandProperty ComicId -Unique)
foreach ($comicId in $comicIds) {
    $updates.Add("UPDATE comic SET lq_size=(SELECT COALESCE(SUM(lq_size),0) FROM page p JOIN chapter ch ON ch.id=p.chapter_id WHERE ch.comic_id=$comicId) WHERE id=$comicId;")
}

$sqlLines = New-Object System.Collections.Generic.List[string]
$sqlLines.Add("START TRANSACTION;")
foreach ($update in $updates) {
    $sqlLines.Add($update)
}
$sqlLines.Add("COMMIT;")

$reportDir = Split-Path -Path $ReportPath -Parent
if (-not [string]::IsNullOrWhiteSpace($reportDir) -and -not (Test-Path -LiteralPath $reportDir)) {
    New-Item -ItemType Directory -Path $reportDir | Out-Null
}
$reportRows | Export-Csv -LiteralPath $ReportPath -NoTypeInformation -Encoding UTF8

$sqlDir = Split-Path -Path $SqlPath -Parent
if (-not [string]::IsNullOrWhiteSpace($sqlDir) -and -not (Test-Path -LiteralPath $sqlDir)) {
    New-Item -ItemType Directory -Path $sqlDir | Out-Null
}
Set-Content -LiteralPath $SqlPath -Value $sqlLines -Encoding UTF8

if ($ExecuteSql -and $updates.Count -gt 0) {
    $sqlText = $sqlLines -join "`n"
    Invoke-MysqlText -SqlText $sqlText | Out-Null
}

Write-Host "Rows read: $($rows.Count)"
Write-Host "Matched: $matchedCount"
Write-Host "Missing: $missingCount"
Write-Host "DuplicateBaseName: $duplicateCount"
Write-Host "Copied: $copiedCount"
Write-Host "SQL updates: $($updates.Count)"
Write-Host "Report written: $ReportPath"
Write-Host "SQL written: $SqlPath"
