# ComicAtlas - 开发环境全部启动
Write-Host "=== ComicAtlas 开发环境 ===" -ForegroundColor Cyan

# 1. 基础设施 (Docker)
Write-Host "[1/4] 启动基础设施 (MySQL/Redis/RabbitMQ/Nacos/Nginx)..." -ForegroundColor Yellow
docker compose up -d mysql redis rabbitmq nacos nginx
Write-Host "  等待就绪..." -ForegroundColor Gray
Start-Sleep 5

# 2-4. Java 服务（mvn spring-boot:run）
"gateway", "api-service", "worker-service" | ForEach-Object {
    $name = $_
    Write-Host "启动 $name ..." -ForegroundColor Yellow
    Start-Process pwsh -WorkingDirectory "$PSScriptRoot\$name" -ArgumentList "-NoExit", "-Command", "mvn spring-boot:run"
}

Write-Host "`n=== 全部启动完成 ===" -ForegroundColor Green
Write-Host "Gateway : http://localhost:8000"
Write-Host "Frontend: http://localhost (nginx) 或 npm run dev (5173)"
