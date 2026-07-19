# ComicAtlas - 开发环境启动
Write-Host "=== ComicAtlas 开发环境 ===" -ForegroundColor Cyan

docker compose up -d
docker stop comicatlas-worker comicatlas-api 2>$null

Start-Process pwsh -WorkingDirectory "$PSScriptRoot\worker-service" -ArgumentList "-NoExit", "-Command", "mvn spring-boot:run"
Start-Process pwsh -WorkingDirectory "$PSScriptRoot\api-service" -ArgumentList "-NoExit", "-Command", "mvn spring-boot:run"


Write-Host "Worker 已在本地启动，可直接导入任意路径的漫画。"
