# ComicAtlas - 开发环境全部启动
Write-Host "=== ComicAtlas 开发环境 ===" -ForegroundColor Cyan

# 1. 基础设施 (Docker)
Write-Host "[1/4] 启动基础设施 (MySQL/Redis/RabbitMQ/Nacos/Nginx)..." -ForegroundColor Yellow
docker compose up -d mysql redis rabbitmq nacos nginx
Write-Host "  等待就绪..." -ForegroundColor Gray
Start-Sleep 5

# 2. Gateway (8000)
Write-Host "[2/4] 启动 Gateway (8000)..." -ForegroundColor Yellow
Start-Process pwsh -WorkingDirectory "$PSScriptRoot\gateway" -ArgumentList "-NoExit", "-Command", "Write-Host 'Gateway starting...'; ./mvnw spring-boot:run"

# 3. API Service (8010)
Write-Host "[3/4] 启动 API Service (8010)..." -ForegroundColor Yellow
Start-Process pwsh -WorkingDirectory "$PSScriptRoot\api-service" -ArgumentList "-NoExit", "-Command", "Write-Host 'API starting...'; ./mvnw spring-boot:run"

# 4. Worker Service (8020)
Write-Host "[4/4] 启动 Worker Service (8020)..." -ForegroundColor Yellow
Start-Process pwsh -WorkingDirectory "$PSScriptRoot\worker-service" -ArgumentList "-NoExit", "-Command", "Write-Host 'Worker starting...'; ./mvnw spring-boot:run"

Write-Host "`n=== 全部启动完成 ===" -ForegroundColor Green
Write-Host "Gateway : http://localhost:8000"
Write-Host "Frontend: http://localhost (nginx) 或 npm run dev (5173)"
Write-Host "MySQL   : localhost:3306"
Write-Host "Redis   : localhost:6379"
