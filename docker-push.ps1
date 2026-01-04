# BiliBili Dynamic Bot - Docker Hub 推送脚本
# 此脚本用于将镜像推送到 Docker Hub

param(
    [Parameter(Position=0)]
    [string]$Version = "latest"
)

$ErrorActionPreference = "Stop"

# Docker Hub 配置
$DOCKERHUB_USERNAME = "menghuanan"
$IMAGE_NAME = "dynamic-bot"
$LOCAL_IMAGE = "dynamic-bot-dynamic-bot"

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

function Write-Step {
    param([string]$Message)
    Write-Host "`n>>> $Message" -ForegroundColor Yellow
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Docker Hub 镜像推送" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查本地镜像是否存在
Write-Step "步骤 1/5: 检查本地镜像"
$localImage = docker images $LOCAL_IMAGE --format "{{.Repository}}:{{.Tag}}" | Select-Object -First 1
if (-not $localImage) {
    Write-Error-Message "本地镜像 $LOCAL_IMAGE 不存在"
    Write-Info "请先运行: .\docker-deploy.ps1 build"
    exit 1
}
Write-Success "找到本地镜像: $localImage"

# 检查 Docker Hub 登录状态
Write-Step "步骤 2/5: 检查 Docker Hub 登录状态"
Write-Info "用户名: $DOCKERHUB_USERNAME"
Write-Info "镜像名: $IMAGE_NAME"
Write-Info "版本号: $Version"
Write-Host ""
Write-Info "请确保已登录 Docker Hub"
Write-Info "如果未登录，请运行: docker login"
Write-Host ""

$confirm = Read-Host "是否继续推送? (y/N)"
if ($confirm -ne "y" -and $confirm -ne "Y") {
    Write-Info "操作已取消"
    exit 0
}

# 为镜像打标签
Write-Step "步骤 3/5: 为镜像打标签"
$targetImage = "$DOCKERHUB_USERNAME/${IMAGE_NAME}:$Version"
Write-Info "正在创建标签: $targetImage"

docker tag $LOCAL_IMAGE $targetImage

if ($LASTEXITCODE -eq 0) {
    Write-Success "标签创建成功"
} else {
    Write-Error-Message "标签创建失败"
    exit 1
}

# 如果是版本号，同时打上 latest 标签
if ($Version -ne "latest") {
    $latestImage = "$DOCKERHUB_USERNAME/${IMAGE_NAME}:latest"
    Write-Info "正在创建 latest 标签: $latestImage"
    docker tag $LOCAL_IMAGE $latestImage

    if ($LASTEXITCODE -eq 0) {
        Write-Success "latest 标签创建成功"
    } else {
        Write-Error-Message "latest 标签创建失败"
        exit 1
    }
}

# 推送镜像
Write-Step "步骤 4/5: 推送镜像到 Docker Hub"
Write-Info "正在推送: $targetImage"
Write-Info "这可能需要几分钟，请耐心等待..."
Write-Host ""

docker push $targetImage

if ($LASTEXITCODE -eq 0) {
    Write-Success "镜像推送成功: $targetImage"
} else {
    Write-Error-Message "镜像推送失败"
    exit 1
}

# 如果有 latest 标签，也推送
if ($Version -ne "latest") {
    Write-Info "正在推送 latest 标签..."
    docker push $latestImage

    if ($LASTEXITCODE -eq 0) {
        Write-Success "latest 标签推送成功"
    } else {
        Write-Error-Message "latest 标签推送失败"
        exit 1
    }
}

# 显示镜像信息
Write-Step "步骤 5/5: 完成"
Write-Host ""
Write-Success "所有镜像已成功推送到 Docker Hub!"
Write-Host ""
Write-Host "镜像信息:" -ForegroundColor Cyan
Write-Host "  仓库地址: https://hub.docker.com/r/$DOCKERHUB_USERNAME/$IMAGE_NAME" -ForegroundColor White
Write-Host "  拉取命令: docker pull $DOCKERHUB_USERNAME/${IMAGE_NAME}:$Version" -ForegroundColor White
if ($Version -ne "latest") {
    Write-Host "  拉取最新: docker pull $DOCKERHUB_USERNAME/${IMAGE_NAME}:latest" -ForegroundColor White
}
Write-Host ""

# 清理本地标签（可选）
Write-Host "提示: 如需清理本地标签，运行:" -ForegroundColor Yellow
Write-Host "  docker rmi $targetImage" -ForegroundColor Gray
if ($Version -ne "latest") {
    Write-Host "  docker rmi $latestImage" -ForegroundColor Gray
}
Write-Host ""
