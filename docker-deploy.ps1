# BiliBili Dynamic Bot - Docker 部署脚本
# 此脚本用于简化 Docker 容器的管理操作

param(
    [Parameter(Position=0)]
    [ValidateSet("build", "start", "stop", "restart", "logs", "status", "clean", "rebuild")]
    [string]$Action = "status"
)

$ErrorActionPreference = "Stop"
$ProjectName = "dynamic-bot"

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[SUCCESS] $Message" -ForegroundColor Green
}

function Write-Error-Message {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Write-Warning-Message {
    param([string]$Message)
    Write-Host "[WARNING] $Message" -ForegroundColor Yellow
}

function Test-DockerInstalled {
    try {
        docker --version | Out-Null
        docker compose version | Out-Null
        return $true
    }
    catch {
        Write-Error-Message "Docker 或 Docker Compose 未安装或未运行"
        Write-Info "请访问 https://www.docker.com/products/docker-desktop 安装 Docker Desktop"
        return $false
    }
}

function Test-JarExists {
    $jarPath = "build/libs/dynamic-bot-1.0.jar"
    if (-not (Test-Path $jarPath)) {
        Write-Warning-Message "JAR 文件不存在: $jarPath"
        Write-Info "正在尝试构建项目..."
        return $false
    }
    return $true
}

function Build-Project {
    Write-Info "开始构建项目..."
    if (Test-Path "gradlew.bat") {
        & .\gradlew.bat build -x test
    }
    else {
        Write-Error-Message "gradlew.bat 不存在"
        exit 1
    }

    if ($LASTEXITCODE -eq 0) {
        Write-Success "项目构建成功"
    }
    else {
        Write-Error-Message "项目构建失败"
        exit 1
    }
}

function Build-Image {
    Write-Info "开始构建 Docker 镜像..."

    if (-not (Test-JarExists)) {
        Build-Project
    }

    docker compose build

    if ($LASTEXITCODE -eq 0) {
        Write-Success "Docker 镜像构建成功"
    }
    else {
        Write-Error-Message "Docker 镜像构建失败"
        exit 1
    }
}

function Start-Container {
    Write-Info "启动容器..."

    # 检查配置文件
    if (-not (Test-Path "config/bot.yml")) {
        Write-Warning-Message "配置文件 config/bot.yml 不存在"
        Write-Info "首次运行时会自动创建配置文件，请稍后修改配置"
    }

    docker compose up -d

    if ($LASTEXITCODE -eq 0) {
        Write-Success "容器启动成功"
        Write-Info "使用 'docker-deploy.ps1 logs' 查看日志"
        Write-Info "使用 'docker-deploy.ps1 status' 查看状态"
    }
    else {
        Write-Error-Message "容器启动失败"
        exit 1
    }
}

function Stop-Container {
    Write-Info "停止容器..."
    docker compose down

    if ($LASTEXITCODE -eq 0) {
        Write-Success "容器已停止"
    }
    else {
        Write-Error-Message "停止容器失败"
        exit 1
    }
}

function Restart-Container {
    Write-Info "重启容器..."
    docker compose restart

    if ($LASTEXITCODE -eq 0) {
        Write-Success "容器已重启"
    }
    else {
        Write-Error-Message "重启容器失败"
        exit 1
    }
}

function Show-Logs {
    Write-Info "显示容器日志 (Ctrl+C 退出)..."
    docker compose logs -f
}

function Show-Status {
    Write-Info "容器状态:"
    docker compose ps

    Write-Host ""
    Write-Info "镜像信息:"
    docker images | Select-String -Pattern "dynamic-bot|REPOSITORY"

    Write-Host ""
    Write-Info "磁盘使用:"
    $configSize = if (Test-Path "config") { (Get-ChildItem "config" -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB } else { 0 }
    $dataSize = if (Test-Path "data") { (Get-ChildItem "data" -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB } else { 0 }
    $logsSize = if (Test-Path "logs") { (Get-ChildItem "logs" -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB } else { 0 }

    Write-Host "  配置目录: $([math]::Round($configSize, 2)) MB"
    Write-Host "  数据目录: $([math]::Round($dataSize, 2)) MB"
    Write-Host "  日志目录: $([math]::Round($logsSize, 2)) MB"
}

function Clean-Resources {
    Write-Warning-Message "此操作将删除容器、镜像和未使用的数据卷"
    $confirm = Read-Host "是否继续? (y/N)"

    if ($confirm -ne "y" -and $confirm -ne "Y") {
        Write-Info "操作已取消"
        return
    }

    Write-Info "停止并删除容器..."
    docker compose down

    Write-Info "删除镜像..."
    docker rmi dynamic-bot:latest -f 2>$null

    Write-Info "清理未使用的数据卷..."
    docker volume prune -f

    Write-Success "清理完成"
}

function Rebuild-All {
    Write-Info "完全重新构建..."

    Write-Info "1/4 停止容器..."
    docker compose down

    Write-Info "2/4 删除旧镜像..."
    docker rmi dynamic-bot:latest -f 2>$null

    Write-Info "3/4 重新构建项目..."
    Build-Project

    Write-Info "4/4 构建新镜像..."
    docker compose build --no-cache

    if ($LASTEXITCODE -eq 0) {
        Write-Success "重新构建完成"
        Write-Info "使用 'docker-deploy.ps1 start' 启动容器"
    }
    else {
        Write-Error-Message "重新构建失败"
        exit 1
    }
}

# 主逻辑
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  BiliBili Dynamic Bot - Docker 部署" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

if (-not (Test-DockerInstalled)) {
    exit 1
}

switch ($Action) {
    "build" {
        Build-Image
    }
    "start" {
        Start-Container
    }
    "stop" {
        Stop-Container
    }
    "restart" {
        Restart-Container
    }
    "logs" {
        Show-Logs
    }
    "status" {
        Show-Status
    }
    "clean" {
        Clean-Resources
    }
    "rebuild" {
        Rebuild-All
    }
    default {
        Write-Info "用法: .\docker-deploy.ps1 [命令]"
        Write-Host ""
        Write-Host "可用命令:"
        Write-Host "  build    - 构建 Docker 镜像"
        Write-Host "  start    - 启动容器"
        Write-Host "  stop     - 停止容器"
        Write-Host "  restart  - 重启容器"
        Write-Host "  logs     - 查看日志"
        Write-Host "  status   - 查看状态 (默认)"
        Write-Host "  clean    - 清理容器和镜像"
        Write-Host "  rebuild  - 完全重新构建"
    }
}
