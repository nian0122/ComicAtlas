param(
    [string]$ModelDirectory = '.runtime/models/InternVL3_5-2B',
    [int64]$ChunkSize = 104857600
)

$ErrorActionPreference = 'Stop'
$modelUrl = 'https://modelscope.cn/models/OpenGVLab/InternVL3_5-2B/resolve/master/model.safetensors'
$modelSize = 4696775752
$resolvedDirectory = [IO.Path]::GetFullPath($ModelDirectory)
New-Item -ItemType Directory -Force -Path $resolvedDirectory | Out-Null
$modelPath = Join-Path $resolvedDirectory 'model.safetensors'
$supportingFiles = @(
    'added_tokens.json', 'chat_template.jinja', 'config.json', 'configuration_intern_vit.py',
    'configuration_internvl_chat.py', 'conversation.py', 'generation_config.json', 'merges.txt',
    'modeling_intern_vit.py', 'modeling_internvl_chat.py', 'preprocessor_config.json',
    'processor_config.json', 'special_tokens_map.json', 'tokenizer.json', 'tokenizer_config.json',
    'video_preprocessor_config.json', 'vocab.json'
)
foreach ($supportingFile in $supportingFiles) {
    $supportingPath = Join-Path $resolvedDirectory $supportingFile
    if (Test-Path -LiteralPath $supportingPath) { continue }
    Write-Host ("下载模型配置：{0}" -f $supportingFile)
    & curl.exe --fail --location --retry 6 --retry-all-errors --retry-delay 2 --connect-timeout 30 `
        --output $supportingPath ("https://modelscope.cn/models/OpenGVLab/InternVL3_5-2B/resolve/master/{0}" -f $supportingFile)
    if ($LASTEXITCODE -ne 0) { throw "模型配置下载失败：$supportingFile" }
}

$chunkPaths = [Collections.Generic.List[string]]::new()
for ($rangeStart = [int64]0; $rangeStart -lt $modelSize; $rangeStart += $ChunkSize) {
    $rangeEnd = [Math]::Min($modelSize - 1, $rangeStart + $ChunkSize - 1)
    $chunkPath = Join-Path $resolvedDirectory ("model.safetensors.{0:D5}.part" -f $chunkPaths.Count)
    $chunkPaths.Add($chunkPath)
    $expectedSize = $rangeEnd - $rangeStart + 1
    if ((Test-Path -LiteralPath $chunkPath) -and ((Get-Item -LiteralPath $chunkPath).Length -eq $expectedSize)) {
        continue
    }
    Write-Host ("下载字节范围 {0}-{1}" -f $rangeStart, $rangeEnd)
    & curl.exe --fail --location --retry 6 --retry-all-errors --retry-delay 2 --connect-timeout 30 `
        --range ("{0}-{1}" -f $rangeStart, $rangeEnd) --output $chunkPath $modelUrl
    if ($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $chunkPath).Length -ne $expectedSize) {
        throw "模型分段下载失败：$rangeStart-$rangeEnd"
    }
}

Write-Host '合并模型权重...'
$temporaryModelPath = "$modelPath.tmp"
if (Test-Path -LiteralPath $temporaryModelPath) { [IO.File]::Delete($temporaryModelPath) }
$outputStream = [IO.File]::Open($temporaryModelPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    foreach ($chunkPath in $chunkPaths) {
        $inputStream = [IO.File]::OpenRead($chunkPath)
        try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose() }
    }
} finally { $outputStream.Dispose() }
if ((Get-Item -LiteralPath $temporaryModelPath).Length -ne $modelSize) {
    throw '合并后的模型权重大小不正确'
}
Move-Item -LiteralPath $temporaryModelPath -Destination $modelPath -Force
Write-Host ("模型权重已准备：{0}" -f $modelPath)
