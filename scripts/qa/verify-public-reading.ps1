[CmdletBinding()]
param([Parameter(Mandatory)][string]$BaseUrl)
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
# 仅发送无写入效果的探测；空正文 PUT 应到达后端并返回业务错误。
$cases = @(
    @('GET', '/', 200),
    @('GET', '/api/comics?size=1', 200),
    @('GET', '/api/categories', 200),
    @('GET', '/api/tags', 200),
    @('GET', '/api/history/page?size=1', 200),
    @('PUT', '/api/history/1', 200),
    @('GET', '/manage', 403),
    @('GET', '/manage/import', 403),
    @('GET', '/api/manage/comics', 403),
    @('POST', '/api/manage/tasks/import', 403),
    @('DELETE', '/api/manage/comics/1', 403),
    @('PUT', '/api/comics/1/reaction', 200),
    @('PUT', '/api/chapters/1/reaction', 200),
    @('PUT', '/api/pages/1/reaction', 200),
    @('POST', '/api/pages/1/reaction', 403),
    @('DELETE', '/api/comics/1/reaction', 403),
    @('PUT', '/api/pages/abc/reaction', 403),
    @('PUT', '/api/pages/1/reaction/extra', 403),
    @('PUT', '/api/comics/1', 403),
    @('DELETE', '/api/history/1', 403),
    @('PUT', '/api/history/page', 403),
    @('GET', '/actuator/health', 403),
    @('GET', '/files/hq/1/metadata.json', 403),
    @('GET', '/files/trash/test.jpg', 403),
    @('GET', '/api/comics/1;test', 403),
    @('GET', '/api/comics%252f..%252fmanage/comics', 403)
)
foreach ($case in $cases) {
    $response = Invoke-WebRequest -Uri "$BaseUrl$($case[1])" -Method $case[0] -SkipHttpErrorCheck -TimeoutSec 30
    if ([int]$response.StatusCode -ne $case[2]) {
        throw "$($case[0]) $($case[1]) 预期 $($case[2])，实际 $($response.StatusCode)"
    }
    if ($case[1] -eq '/' -and $response.Content -notmatch 'name="comicatlas-access" content="public-reading"') {
        throw '公网首页缺少阅读模式标识'
    }
    if ($case[0] -eq 'PUT' -and $case[2] -eq 200 -and ($response.Content | ConvertFrom-Json).code -lt 400) {
        throw '空正文写请求应返回业务错误'
    }
    Write-Output "$($case[0]) $($case[1]) -> $($response.StatusCode)"
}
Write-Output "公网阅读边界验证通过：$($cases.Count) 项"
