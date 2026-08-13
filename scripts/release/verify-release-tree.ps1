[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$forbiddenPaths = @(
    '.mvn',
    'mvnw',
    'mvnw.cmd',
    'docker-compose.test.yml',
    'e2e',
    'frontend/e2e',
    'frontend/e2e-legacy',
    'frontend/test-fixtures',
    'scripts/dev',
    'scripts/qa',
    'tools/migration'
)

$trackedPaths = git ls-files
if ($LASTEXITCODE -ne 0) {
    throw '无法读取 Git 跟踪文件，发布树校验终止。'
}

$violations = foreach ($forbiddenPath in $forbiddenPaths) {
    $prefix = "$forbiddenPath/"
    $trackedPaths | Where-Object { $_ -eq $forbiddenPath -or $_ -like "$prefix*" }
}

if ($violations) {
    Write-Error ('正式发布分支包含开发文件：' + [Environment]::NewLine + ($violations | Sort-Object -Unique | Out-String))
    exit 1
}

Write-Output '发布树校验通过：未发现开发文件。'
