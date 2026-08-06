<#
.SYNOPSIS
    将旧系统 h_photograph / l_photograph 漫画目录提交到 ComicAtlas 新系统导入队列。

.DESCRIPTION
    支持识别旧系统三种存储状态：
    - HQ_ONLY：HQ 有非 0 字节媒体，LQ 不存在或为空。
    - HQ_AND_LQ：HQ 有非 0 字节媒体，LQ 也存在媒体。
    - LQ_ONLY：HQ 不存在/仅占位空文件，LQ 有媒体。

    当前后端代码尚未实现 MIGRATE_LQ sourceType，因此默认只提交 HQ_ONLY / HQ_AND_LQ。
    LQ_ONLY 默认写入报告并跳过。若后端补齐 MIGRATE_LQ 后，可加 -EnableMigrateLq 提交。
    若接受把旧 LQ 当作新 HQ 导入（会失真：新系统认为 HQ=旧 LQ），可加 -ImportLqOnlyAsHqFallback。

.EXAMPLE
    .\tools\migration\import-legacy-comics.ps1 `
      -LegacyRoot "F:\games\comics" `
      -DryRun

.EXAMPLE
    .\tools\migration\import-legacy-comics.ps1 `
      -HqRoot "F:\games\comics\h_photograph\写真\梨霜儿" `
      -LqRoot "F:\games\comics\l_photograph\写真\梨霜儿" `
      -DryRun

.EXAMPLE
    .\tools\migration\import-legacy-comics.ps1 `
      -HqRoot "F:\games\comics\h_photograph\写真\梨霜儿" `
      -LqRoot "F:\games\comics\l_photograph\写真\梨霜儿" `
      -ApiBaseUrl "http://localhost:8000"
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$LegacyRoot = "",

    [Parameter(Mandatory = $false)]
    [int]$ComicRelativeDepth = 2,

    [Parameter(Mandatory = $false)]
    [string]$HqRoot = "",

    [Parameter(Mandatory = $false)]
    [string]$LqRoot = "",

    [Parameter(Mandatory = $false)]
    [string]$ApiBaseUrl = "http://localhost:8000",

    [Parameter(Mandatory = $false)]
    [int]$BatchSize = 20,

    [Parameter(Mandatory = $false)]
    [string]$ReportPath = "",

    [Parameter(Mandatory = $false)]
    [switch]$DryRun,

    [Parameter(Mandatory = $false)]
    [switch]$SingleComic,

    [Parameter(Mandatory = $false)]
    [switch]$AsCollection,

    [Parameter(Mandatory = $false)]
    [switch]$EnableMigrateLq,

    [Parameter(Mandatory = $false)]
    [switch]$ImportLqOnlyAsHqFallback
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$mediaExtensions = @(
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif",
    ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v"
)

function Convert-ToApiPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return $Path.Replace("\", "/")
}

function Join-RootRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        return $Root
    }

    return Join-Path -Path $Root -ChildPath $RelativePath
}

function Get-MediaFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $false)][switch]$NonZeroOnly
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        return @()
    }

    $files = Get-ChildItem -LiteralPath $Path -File -Recurse -Force |
        Where-Object { $mediaExtensions -contains $_.Extension.ToLowerInvariant() }

    if ($NonZeroOnly) {
        return @($files | Where-Object { $_.Length -gt 0 })
    }

    return @($files)
}

function Get-ComicEntries {
    param(
        [Parameter(Mandatory = $true)][string]$HqRootPath,
        [Parameter(Mandatory = $true)][string]$LqRootPath,
        [Parameter(Mandatory = $true)][bool]$TreatAsSingleComic
    )

    $entriesByName = [ordered]@{}

    $hqExists = Test-Path -LiteralPath $HqRootPath -PathType Container
    $lqExists = Test-Path -LiteralPath $LqRootPath -PathType Container

    if (-not $hqExists -and -not $lqExists) {
        throw "Both HQ and LQ roots do not exist: HQ=$HqRootPath, LQ=$LqRootPath"
    }

    if ($TreatAsSingleComic) {
        $singleComicPath = $LqRootPath
        if ($hqExists) {
            $singleComicPath = $HqRootPath
        }
        $name = Split-Path -Path $singleComicPath -Leaf
        $entriesByName[$name] = [pscustomobject]@{
            Name = $name
            HqPath = if ($hqExists) { (Resolve-Path -LiteralPath $HqRootPath).Path } else { "" }
            LqPath = if ($lqExists) { (Resolve-Path -LiteralPath $LqRootPath).Path } else { "" }
        }
        return @($entriesByName.Values)
    }

    if ($hqExists) {
        Get-ChildItem -LiteralPath $HqRootPath -Directory -Force | ForEach-Object {
            $entriesByName[$_.Name] = [pscustomobject]@{
                Name = $_.Name
                HqPath = $_.FullName
                LqPath = ""
            }
        }
    }

    if ($lqExists) {
        Get-ChildItem -LiteralPath $LqRootPath -Directory -Force | ForEach-Object {
            if ($entriesByName.Contains($_.Name)) {
                $entriesByName[$_.Name].LqPath = $_.FullName
            } else {
                $entriesByName[$_.Name] = [pscustomobject]@{
                    Name = $_.Name
                    HqPath = ""
                    LqPath = $_.FullName
                }
            }
        }
    }

    return @($entriesByName.Values)
}

function Get-RelativeDirectoriesAtDepth {
    param(
        [Parameter(Mandatory = $true)][string]$RootPath,
        [Parameter(Mandatory = $true)][int]$Depth
    )

    if (-not (Test-Path -LiteralPath $RootPath -PathType Container)) {
        return @()
    }

    $rootFullPath = (Resolve-Path -LiteralPath $RootPath).Path.TrimEnd("\", "/")
    $result = New-Object System.Collections.Generic.List[string]

    Get-ChildItem -LiteralPath $rootFullPath -Directory -Recurse -Force | ForEach-Object {
        $relative = $_.FullName.Substring($rootFullPath.Length).TrimStart("\", "/")
        if ([string]::IsNullOrWhiteSpace($relative)) {
            return
        }

        $parts = @($relative -split "[\\/]+" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($parts.Count -eq $Depth) {
            $result.Add($relative)
        }
    }

    return @($result)
}

function Get-PairedLegacyTopRoots {
    param([Parameter(Mandatory = $true)][string]$RootPath)

    if (-not (Test-Path -LiteralPath $RootPath -PathType Container)) {
        throw "LegacyRoot does not exist: $RootPath"
    }

    $rootFullPath = (Resolve-Path -LiteralPath $RootPath).Path
    $pairsByKey = [ordered]@{}

    Get-ChildItem -LiteralPath $rootFullPath -Directory -Force | Where-Object { $_.Name -like "h_*" } | ForEach-Object {
        $hqTop = $_
        $lqTopName = "l_" + $hqTop.Name.Substring(2)
        $lqTopPath = Join-Path -Path $rootFullPath -ChildPath $lqTopName
        $pairsByKey[$hqTop.Name] = [pscustomobject]@{
            Name = $hqTop.Name
            HqRoot = $hqTop.FullName
            LqRoot = if (Test-Path -LiteralPath $lqTopPath -PathType Container) {
                (Resolve-Path -LiteralPath $lqTopPath).Path
            } else {
                ""
            }
        }
    }

    Get-ChildItem -LiteralPath $rootFullPath -Directory -Force | Where-Object { $_.Name -like "l_*" } | ForEach-Object {
        $lqTop = $_
        $hqTopName = "h_" + $lqTop.Name.Substring(2)
        if (-not $pairsByKey.Contains($hqTopName)) {
            $pairsByKey[$lqTop.Name] = [pscustomobject]@{
                Name = $lqTop.Name
                HqRoot = ""
                LqRoot = $lqTop.FullName
            }
        }
    }

    return @($pairsByKey.Values)
}

function Get-LegacyRootComicEntries {
    param(
        [Parameter(Mandatory = $true)][string]$RootPath,
        [Parameter(Mandatory = $true)][int]$Depth
    )

    if ($Depth -lt 1) {
        throw "ComicRelativeDepth must be greater than 0."
    }

    $entriesByKey = [ordered]@{}
    $pairs = @(Get-PairedLegacyTopRoots -RootPath $RootPath)

    foreach ($pair in $pairs) {
        $relativePathsByKey = [ordered]@{}

        if ($pair.HqRoot) {
            Get-RelativeDirectoriesAtDepth -RootPath $pair.HqRoot -Depth $Depth | ForEach-Object {
                $relativePathsByKey[$_] = $true
            }
        }

        if ($pair.LqRoot) {
            Get-RelativeDirectoriesAtDepth -RootPath $pair.LqRoot -Depth $Depth | ForEach-Object {
                $relativePathsByKey[$_] = $true
            }
        }

        foreach ($relativePath in $relativePathsByKey.Keys) {
            $hqPath = ""
            if ($pair.HqRoot) {
                $candidateHqPath = Join-RootRelativePath -Root $pair.HqRoot -RelativePath $relativePath
                if (Test-Path -LiteralPath $candidateHqPath -PathType Container) {
                    $hqPath = (Resolve-Path -LiteralPath $candidateHqPath).Path
                }
            }

            $lqPath = ""
            if ($pair.LqRoot) {
                $candidateLqPath = Join-RootRelativePath -Root $pair.LqRoot -RelativePath $relativePath
                if (Test-Path -LiteralPath $candidateLqPath -PathType Container) {
                    $lqPath = (Resolve-Path -LiteralPath $candidateLqPath).Path
                }
            }

            $key = $pair.Name + "/" + $relativePath.Replace("\", "/")
            $entriesByKey[$key] = [pscustomobject]@{
                Name = $key
                HqPath = $hqPath
                LqPath = $lqPath
            }
        }
    }

    return @($entriesByKey.Values)
}

function Get-LegacyComicPlan {
    param([Parameter(Mandatory = $true)]$Entry)

    $hqMedia = @()
    if ($Entry.HqPath) {
        $hqMedia = @(Get-MediaFiles -Path $Entry.HqPath)
    }

    $hqNonZero = @()
    if ($Entry.HqPath) {
        $hqNonZero = @(Get-MediaFiles -Path $Entry.HqPath -NonZeroOnly)
    }

    $lqMedia = @()
    if ($Entry.LqPath) {
        $lqMedia = @(Get-MediaFiles -Path $Entry.LqPath)
    }

    $state = "EMPTY"
    $sourceType = ""
    $sourcePath = ""
    $action = "SKIP"
    $reason = ""

    if ($hqNonZero.Count -gt 0 -and $lqMedia.Count -gt 0) {
        $state = "HQ_AND_LQ"
        $sourceType = "REGISTER"
        $sourcePath = $Entry.HqPath
        $action = "IMPORT"
        $reason = "Submit HQ. Legacy LQ cannot be attached through the current API."
    } elseif ($hqNonZero.Count -gt 0) {
        $state = "HQ_ONLY"
        $sourceType = "REGISTER"
        $sourcePath = $Entry.HqPath
        $action = "IMPORT"
        $reason = "Submit HQ."
    } elseif ($lqMedia.Count -gt 0) {
        $state = "LQ_ONLY"
        if ($EnableMigrateLq) {
            $sourceType = "MIGRATE_LQ"
            $sourcePath = if ($Entry.HqPath) { $Entry.HqPath } else { $Entry.LqPath }
            $action = "IMPORT"
            $reason = "Submit MIGRATE_LQ. Requires backend support for this sourceType."
        } elseif ($ImportLqOnlyAsHqFallback) {
            $sourceType = "REGISTER"
            $sourcePath = $Entry.LqPath
            $action = "IMPORT"
            $reason = "Fallback: submit legacy LQ as new HQ. HQ/LQ semantics are not preserved."
        } else {
            $reason = "LQ-only. Current backend has no MIGRATE_LQ implementation; skipped by default."
        }
    } else {
        $reason = "No importable media found."
    }

    return [pscustomobject]@{
        Name = $Entry.Name
        State = $state
        Action = $action
        SourceType = $sourceType
        SourcePath = $sourcePath
        HqPath = $Entry.HqPath
        LqPath = $Entry.LqPath
        HqMediaCount = $hqMedia.Count
        HqNonZeroCount = $hqNonZero.Count
        LqMediaCount = $lqMedia.Count
        Reason = $reason
        BatchId = ""
        TaskIds = ""
        Error = ""
    }
}

function Invoke-ImportBatch {
    param(
        [Parameter(Mandatory = $true)][string]$ApiBase,
        [Parameter(Mandatory = $true)][string]$SourceType,
        [Parameter(Mandatory = $true)][array]$Plans
    )

    $uri = $ApiBase.TrimEnd("/") + "/api/tasks/import/batch"
    $body = @{
        sourceType = $SourceType
        sourcePaths = @($Plans | ForEach-Object { Convert-ToApiPath -Path $_.SourcePath })
    } | ConvertTo-Json -Depth 4

    return Invoke-RestMethod -Method Post -Uri $uri -ContentType "application/json; charset=utf-8" -Body $body
}

function Write-PlanSummary {
    param([Parameter(Mandatory = $false)][array]$Plans)

    if ($null -eq $Plans -or $Plans.Count -eq 0) {
        "No entries found."
        return
    }

    $Plans |
        Group-Object State, Action |
        Sort-Object Name |
        ForEach-Object {
            "{0}: {1}" -f $_.Name, $_.Count
        }
}

if ($BatchSize -lt 1) {
    throw "BatchSize must be greater than 0."
}

if ($SingleComic -and $AsCollection) {
    throw "SingleComic and AsCollection cannot be used together."
}

if ($LegacyRoot -and ($HqRoot -or $LqRoot)) {
    throw "Use either LegacyRoot or HqRoot/LqRoot, not both."
}

if (-not $LegacyRoot -and (-not $HqRoot -or -not $LqRoot)) {
    throw "Provide LegacyRoot, or provide both HqRoot and LqRoot."
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $ReportPath = Join-Path -Path (Get-Location) -ChildPath "tools\legacy-import-report-$timestamp.csv"
}

if ($LegacyRoot) {
    $entries = Get-LegacyRootComicEntries -RootPath $LegacyRoot -Depth $ComicRelativeDepth
} else {
    $hqLeaf = Split-Path -Path $HqRoot -Leaf
    $lqLeaf = Split-Path -Path $LqRoot -Leaf
    $treatAsSingle = $false
    if ($SingleComic) {
        $treatAsSingle = $true
    } elseif ($AsCollection) {
        $treatAsSingle = $false
    } elseif ($hqLeaf -eq $lqLeaf) {
        $treatAsSingle = $true
    }

    $entries = Get-ComicEntries -HqRootPath $HqRoot -LqRootPath $LqRoot -TreatAsSingleComic $treatAsSingle
}
$plans = @($entries | ForEach-Object { Get-LegacyComicPlan -Entry $_ })

Write-Host "Scan completed: $($plans.Count) item(s)"
Write-PlanSummary -Plans $plans | ForEach-Object { Write-Host $_ }

$importPlans = @($plans | Where-Object { $_.Action -eq "IMPORT" })
if ($DryRun) {
    Write-Host "DryRun: import API will not be called."
} elseif ($importPlans.Count -gt 0) {
    $groups = $importPlans | Group-Object SourceType
    foreach ($group in $groups) {
        $items = @($group.Group)
        for ($offset = 0; $offset -lt $items.Count; $offset += $BatchSize) {
            $end = [Math]::Min($offset + $BatchSize - 1, $items.Count - 1)
            $chunk = @($items[$offset..$end])
            Write-Host "Submitting batch: sourceType=$($group.Name), count=$($chunk.Count)"
            try {
                $response = Invoke-ImportBatch -ApiBase $ApiBaseUrl -SourceType $group.Name -Plans $chunk
                if ($response.code -ne 200) {
                    throw "API returned non-success response: code=$($response.code), message=$($response.message)"
                }

                $batchId = $response.data.batchId
                $taskIds = @($response.data.succeeded | ForEach-Object { $_.id }) -join ";"
                foreach ($plan in $chunk) {
                    $plan.BatchId = $batchId
                    $plan.TaskIds = $taskIds
                }

                foreach ($failed in @($response.data.failed)) {
                    $failedPath = Convert-ToApiPath -Path $failed.sourcePath
                    $matched = $chunk | Where-Object { (Convert-ToApiPath -Path $_.SourcePath) -eq $failedPath }
                    foreach ($plan in $matched) {
                        $plan.Action = "FAILED"
                        $plan.Error = $failed.errorMessage
                    }
                }
            } catch {
                foreach ($plan in $chunk) {
                    $plan.Action = "FAILED"
                    $plan.Error = $_.Exception.Message
                }
                Write-Warning "Batch submission failed: $($_.Exception.Message)"
            }
        }
    }
} else {
    Write-Host "No importable items."
}

$reportDir = Split-Path -Path $ReportPath -Parent
if (-not [string]::IsNullOrWhiteSpace($reportDir) -and -not (Test-Path -LiteralPath $reportDir)) {
    New-Item -ItemType Directory -Path $reportDir | Out-Null
}

$plans |
    Sort-Object State, Name |
    Export-Csv -LiteralPath $ReportPath -NoTypeInformation -Encoding UTF8

Write-Host "Report written: $ReportPath"
