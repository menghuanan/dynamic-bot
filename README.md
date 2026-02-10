# BiliBili 动态推送 Bot v1.5.4

[![Docker Hub](https://img.shields.io/docker/v/menghuanan/dynamic-bot?label=Docker%20Hub&logo=docker)](https://hub.docker.com/r/menghuanan/dynamic-bot)
[![Docker Pulls](https://img.shields.io/docker/pulls/menghuanan/dynamic-bot)](https://hub.docker.com/r/menghuanan/dynamic-bot)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

由 [bilibili-dynamic-mirai-plugin](https://github.com/Colter23/bilibili-dynamic-mirai-plugin) 改造而来。  
代码部分则由 [claude](https://github.com/claude) 主刀构建雏形 GPT-5.2-Codex 协助修复BUG。  
这是基于 NapCat 的 B站动态、直播推送机器人，支持动态订阅、直播通知、链接解析等功能。

## 文档目录

- [预览效果](#预览效果)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [主要功能](#主要功能)
- [配置说明](#配置说明)
- [Docker 部署](#docker-部署)
- [开发说明](#开发说明)
- [与原项目的区别](#与原项目的区别)
- [更新日志](#更新日志)
- [故障排查](#故障排查)
- [许可证](#许可证)
- [联系方式](#联系方式)

## 预览效果

<img src="docs/dynamic.png" width="400" alt="预览图片1">
<img src="docs/live.png" width="400" alt="预览图片2">
<img src="docs/Video.png" width="400" alt="预览图片3">
<img src="docs/bangumi.png" width="400" alt="预览图片4">

## 项目结构

```
dynamic-bot/
├── src/main/kotlin/top/bilibili/      # 源代码目录
│   └── api/               # B站 API 接口
│   ├── client/            # HTTP 客户端
│   ├── config/            # 配置管理
│   ├── core/              # 核心模块
│   ├── data/              # 数据模型
│   ├── draw/              # 图片渲染
│   ├── napcat/            # NapCat 客户端
│   ├── service/           # 业务服务
│   ├── tasker/            # 定时任务
│   ├── utils/             # 工具类
│   ├── BiliConfig.kt      # 配置文件
│   ├── BiliData.kt        # 数据文件
│   └── Main.kt            # 程序入口
├── src/main/resources/               # 资源文件
│   └── font/              # 字体文件
│   ├── icon/              # 图标文件
│   ├── image/             # 图片文件
│   └── logback.xml        # 日志文件
├── gradle/                # Gradle wrapper
├── build.gradle.kts       # Gradle 构建脚本
├── settings.gradle.kts    # Gradle 设置
├── gradle.properties      # Gradle 属性
├── gradlew                # Gradle wrapper 脚本（Linux/Mac）
├── gradlew.bat            # Gradle wrapper 脚本（Windows）
├── .env.example           # 环境变量示例
├── .gitignore             # Git 忽略文件
├── Dockerfile             # Docker 镜像构建文件
├── docker-compose.yml     # Docker Compose 配置
├── docker-deploy.ps1      # Docker 部署脚本（Windows）
├── docker-push.ps1        # Docker Hub 推送脚本（Windows）
├── README.md              # 项目说明
└── LICENSE                # 许可证

```

## 快速开始

### 1. 编译项目

```bash
# Windows
.\gradlew.bat build -x test

# Linux/Mac
./gradlew build -x test
```

编译完成后,可执行文件位于：
- `build/libs/dynamic-bot-1.4.jar`

### 2. 配置文件

首次运行时，程序会自动创建配置文件目录结构：

```
config/
├── bot.yml              # Bot 基础配置（NapCat 连接信息）
├── BiliData.yml         # 订阅数据和推送配置
└── BiliConfig.yml       # 配置文件

data/
├── font/                # 字体文件目录
├── cache/               # 缓存文件目录
└── image_cache/         # 图片缓存目录

temp/                    # 临时文件目录（二维码、缓存等）

logs/
├── bilibili-bot.log     # 主日志文件
├── error.log            # 错误日志文件
└── daemon/              # 守护进程监控日志目录
    └── Daemon_YYYY-MM-DD.log  # 每日监控日志
```

### 3. 运行 Bot

#### 方式一：直接运行 JAR

```bash
java -jar build/libs/dynamic-bot-1.4.jar
```

#### 方式二：使用 Docker Hub 镜像（推荐）

```bash
# 拉取镜像
docker pull menghuanan/dynamic-bot:latest

# 启动容器
docker run -d --name dynamic-bot \
  --network bridge \
  -v ./config:/app/config \
  -v ./data:/app/data \
  -v ./temp:/app/temp \
  -v ./logs:/app/logs \
  menghuanan/dynamic-bot:latest
```

详细的 Docker 部署说明请查看 [Docker 部署](#docker-部署) 章节。

## 主要功能

### 1. 链接解析
- 自动解析群消息中的 B站链接
- 支持视频、动态、直播、番剧、专栏等多种类型
- 支持 QQ 小程序分享的 B站链接
- 生成精美的图文卡片
- 返回标准的 BV 号链接

### 2. 动态订阅
- 订阅 B站用户的动态
- 自动检测新动态并推送到群聊/私聊
- 支持自定义推送模板
- 支持直播开播/关播通知

### 3. 管理命令（均需要管理员权限）

#### 基础命令
- `/check` - 手动触发检查（测试用）
- `/login` - B站扫码登录
- `/bili help` - 显示帮助 

#### 高级命令（/bili）
[查看 `/bili` 帮助大图](docs/help.png)

  <img src="docs/help.png" width="420" alt="高级命令预览">

    /bili 命令帮助:

    订阅管理:
    /bili add <UID|ss|md|ep> <群号> - 添加订阅到指定群
    /bili remove <UID|ss|md|ep> <群号> - 从指定群移除订阅
    /bili list - 查看当前群的订阅
    /bili list <UID|ss|md|ep> - 查看订阅推送到哪些群

    分组管理:
    /bili group create <分组名> - 创建分组
    /bili group delete <分组名> - 删除分组
    /bili group add <分组名> <群号> - 将群加入分组
    /bili group remove <分组名> <群号> - 从分组移除群
    /bili group list [分组名] - 查看分组信息
    /bili group subscribe <分组名> <UID|ss|md|ep> - 订阅到分组
    /bili group unsubscribe <分组名> <UID|ss|md|ep> - 从分组移除订阅
    /bili groups - 查看所有分组

    过滤器管理（支持黑名单与白名单）:
    /bili filter add <UID> <type|regex> <模式> <内容> - 添加过滤器
      type模式: /bili filter add <UID> type <black|white> <动态|转发动态|视频|音乐|专栏|直播>
      regex模式: /bili filter add <UID> regex <black|white> <正则表达式>
    /bili filter list <UID> - 查看过滤器
    /bili filter del <UID> <索引> - 删除过滤器(如 t0, r1)

    其他:
    /bili help - 显示此帮助

## 配置说明

### bot.yml 示例

```yaml
napcat:
  host: "127.0.0.1"          # NapCat WebSocket 主机地址
  port: 3001                 # NapCat WebSocket 端口
  token: ""                  # NapCat WebSocket 访问令牌 （如有）
  heartbeat_interval: 30000  # 心跳间隔（毫秒）
  reconnect_interval: 5000   # 重连间隔（毫秒）
  message_format: "array"    # 消息格式：array
  max_reconnect_attempts: -1 # 最大重连尝试次数（-1表示无限次）
  connect_timeout: 10000     # 连接超时（毫秒）
targets: []                  # 尚未启用
admins: []                   # 尚未启用
```

### BiliData.yml 示例

```yaml
# 动态订阅数据
dynamic:
  # UID: 订阅信息
  123456:
    name: "用户名"
    contacts:
      - "group:987654321"  # 群聊
      - "private:123456789"  # 私聊
    banList: {}
```
### BiliConfig.yml 示例

```yaml
admin: 0                        # 管理员 QQ 号
enableConfig:                   # 启用配置
  debugMode: false              # 启用调试模式
  drawEnable: true              # 启用绘制功能
  notifyEnable: true            # 启用通知功能
  liveCloseNotifyEnable: true   # 启用直播关播通知
  lowSpeedEnable: true          # 启用低速模式
  translateEnable: false        # 启用翻译功能
  proxyEnable: false            # 启用代理功能
  cacheClearEnable: true        # 启用缓存清除功能
accountConfig:                  # 账号配置
  cookie: ""                    # B站账号 Cookie
  autoFollow: true              # 自动关注用户
  followGroup: "Bot关注"        # 自动关注分组
checkConfig:                    # 检查配置
  interval: 15                  # 检查间隔（秒）
  liveInterval: 15              # 直播检查间隔（秒）
  lowSpeedTime: "22-8"          # 低速模式时间范围（24小时格式）
  lowSpeedRange: "60-240"       # 低速模式范围（秒）
  normalRange: "30-120"         # 正常模式范围（秒）
  checkReportInterval: 10       # 检查报告间隔（分钟）
  timeout: 10                   # 检查超时（秒）
pushConfig:
  messageInterval: 100          # 消息间隔（毫秒）
  pushInterval: 500             # 推送间隔（毫秒）
  toShortLink: false            # 是否转换为短链接
imageConfig:
  quality: "1000w"              # 图片质量（1000w/750w）  
  theme: "v3"
  font: ""
  defaultColor: "#d3edfa"
  cardOrnament: "FanCard"
  colorGenerator:
    hueStep: 30
    lockSB: true
    saturation: 0.25
    brightness: 1.0
  badgeEnable:
    left: true
    right: false
templateConfig:
  defaultDynamicPush: "OneMsg"
  defaultLivePush: "OneMsg"
  defaultLiveClose: "SimpleMsg"
  dynamicPush:
    "DrawOnly": "{draw}"
    "TextOnly": "{name}@{type}\n{link}\n{content}\n{images}"
    "OneMsg": "{draw}\n{name}@{type}\n{link}"
    "TwoMsg": "{draw}\r{name}@{uid}@{type}\n{time}\n{link}"
  livePush:
    "DrawOnly": "{draw}"
    "TextOnly": "{name}@直播\n{link}\n标题: {title}"
    "OneMsg": "{draw}\n{name}@直播\n{link}"
    "TwoMsg": "{draw}\r{name}@{uid}@直播\n{title}\n{time}\n{link}"
  liveClose:
    "SimpleMsg": "{name} 直播结束啦!\n直播时长: {duration}"
    "ComplexMsg": "{name} 直播结束啦!\n标题: {title}\n直播时长: {duration}"
  footer:
    dynamicFooter: ""
    liveFooter: ""
    footerAlign: "LEFT"
cacheConfig:
  downloadOriginal: true
  expires:
    "DRAW": 7
    "IMAGES": 7
    "EMOJI": 7
    "USER": 7
    "OTHER": 7
proxyConfig:
  proxy: []
translateConfig:               # 翻译配置
  cutLine: "\n\n〓〓〓 翻译 〓〓〓\n"     # 翻译结果分隔线
  baidu:                       # 百度翻译配置
    APP_ID: ""                 # 百度翻译 APP_ID
    SECURITY_KEY: ""           # 百度翻译 SECURITY_KEY
linkResolveConfig:             # 链接解析配置
  triggerMode: "At"            # 触发模式：At/Always/Never
  returnLink: false            # 是否返回链接

```

## Docker 部署

### 方式一：使用 Docker Hub 镜像（推荐）

无需编译，直接从 Docker Hub 拉取预构建镜像快速部署。

**Docker Hub 仓库：** https://hub.docker.com/r/menghuanan/dynamic-bot

#### 1. 拉取镜像

```bash
docker pull menghuanan/dynamic-bot:latest
```

#### 2. 创建配置目录

```bash
mkdir -p config data temp logs
```

#### 3. 使用 docker run 启动

```bash
docker run -d \
  --name dynamic-bot \
  --restart unless-stopped \
  --network bridge \
  -v ./config:/app/config \
  -v ./data:/app/data \
  -v ./temp:/app/temp \
  -v ./logs:/app/logs \
  menghuanan/dynamic-bot:latest
```

#### 4. 使用 docker-compose（推荐）

创建 `docker-compose.yml` 文件：

```yaml
version: '3.8'

services:
  dynamic-bot:
    image: menghuanan/dynamic-bot:latest
    container_name: dynamic-bot
    restart: unless-stopped
    environment:
      - TZ=Asia/Shanghai
    volumes:
      - ./config:/app/config
      - ./data:/app/data
      - ./temp:/app/temp
      - ./logs:/app/logs
    network_mode: "bridge"  # 使用 bridge 网络模式
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

启动容器：

```bash
docker-compose up -d
```

#### 5. 配置 NapCat 连接

首次运行后，编辑 `config/bot.yml`：

```yaml
napcat:
  host: "NapCat WebSocket 主机地址"  
  port: 3001    #默认3001
  token: ""     #如果有则填入，没有不填
```

重启容器：

```bash
docker-compose restart
# 或
docker restart dynamic-bot
```

#### 6. 查看日志

```bash
docker-compose logs -f
# 或
docker logs -f dynamic-bot
```

#### 可用标签

- `latest` - 最新版本（v1.5）
- `v1.5` - 稳定版本 v1.5
- `v1.4` - 稳定版本 v1.4
- `v1.3.1` - 稳定版本 v1.3.1
- `v1.3` - 稳定版本 v1.3
- `v1.2` - 稳定版本 v1.2
- `v1.1` - 稳定版本 v1.1
- `v1.0` - 稳定版本 v1.0

### 方式二：从源码构建部署

如果需要自定义修改，可以从源码构建镜像。

1. **配置 NapCat 连接**
   - 修改 `config/bot.yml` 中的 host 为 `host.docker.internal`（如果 NapCat 在宿主机）
   - 或保持 `127.0.0.1`（如果 NapCat 也在容器内）

2. **构建镜像**
   ```bash
   docker compose build
   ```

3. **启动容器**
   ```bash
   docker compose up -d
   ```

4. **查看日志**
   ```bash
   docker compose logs -f
   ```

### 容器配置

- **基础镜像**: eclipse-temurin:17-jre-jammy
- **JVM 参数**: `-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport`
- **网络模式**: bridge（默认）
- **健康检查**: 每30秒检查一次进程状态
- **日志限制**: 10MB × 3 文件（自动轮转）
- **重启策略**: unless-stopped

**网络说明：**
- 默认使用 `bridge` 网络模式，适合大多数场景
- 如果 NapCat 在宿主机运行，需要在 `config/bot.yml` 中配置 `host: "host.docker.internal"`
- 如果 NapCat 也在 Docker 中运行，建议使用自定义网络连接两个容器（参考 docker-compose.yml 注释）

### 自动化脚本

Windows 用户可使用自动化脚本简化操作：

**docker-deploy.ps1** - 部署管理脚本
```powershell
.\docker-deploy.ps1 build    # 构建镜像
.\docker-deploy.ps1 start    # 启动容器
.\docker-deploy.ps1 stop     # 停止容器
.\docker-deploy.ps1 restart  # 重启容器
.\docker-deploy.ps1 logs     # 查看日志
.\docker-deploy.ps1 status   # 查看状态
.\docker-deploy.ps1 clean    # 清理容器和镜像
.\docker-deploy.ps1 rebuild  # 完全重新构建
```

**docker-push.ps1** - Docker Hub 推送脚本
```powershell
.\docker-push.ps1 latest     # 推送 latest 标签
.\docker-push.ps1 v1.4       # 推送指定版本标签
```

## 开发说明

### 技术栈
- Kotlin 2.0.0
- Ktor 3.0.3（HTTP 客户端）
- Skiko 0.7.27（图片渲染）
- kotlinx.serialization 1.6.3（JSON 处理）
- kotlinx.coroutines 1.8.0（协程）
- OneBot v11 协议（NapCat）

### 项目特点
- 独立运行，不依赖 Mirai 框架
- 使用 NapCat 作为 QQ 机器人框架
- 基于 WebSocket 通信
- 使用 Skiko 进行高质量图片渲染
- 支持 Docker 部署

## 与原项目的区别

1. **移除了以下内容**：
   - Mirai 框架相关代码和测试
   - 编译输出（build/、.gradle/）
   - IDE 配置文件（.idea/、.kotlin/）
   - 临时文件和运行时数据

2. **保留了以下内容**：
   - 完整的源代码
   - Gradle 构建配置
   - Docker 部署文件
   - 文档和示例配置

## 更新日志

### v1.5.4 (2026-02-11)

**全面代码审计完成** 📋
- ✅ **完成所有模块的自维护代码审计**
  - API 模块 (6 个文件): 扩展函数模式，共享 BiliClient，无资源泄漏
  - Service 模块 (5 个文件): Mutex 线程安全 + 容量限制保护
  - 数据模型 (4 个文件): 纯 @Serializable data class，无需资源管理
  - Tasker 模块 (4 个文件): LRU 缓存 + 失败告警 + 性能优化
- ✅ **关键发现与验证**
  - DynamicService: MAX_SUBSCRIPTIONS=50000, MAX_CONTACTS_PER_UID=1000 容量保护
  - LoginService: try-finally + deleteOnExit 双重临时文件清理
  - ListenerTasker: LRU LinkedHashMap (MAX_CACHE_SIZE=10000) 自动驱逐
  - CacheClearTasker: 连续失败 3 次自动告警管理员
- ✅ **审计报告更新**
  - 更新 `docs/self-maintenance-audit.md` 审计报告
  - 所有模块状态从"部分审计"更新为"已审计"
  - 新增 Service 模块容量保护、LRU 缓存等关键代码示例

**Skia 绘图资源管理增强** 🎨
- ✅ **修复 DynamicModuleDraw.kt 中的 Image 泄漏**
  - 修复 DISPUTE 图标 Image 未关闭问题
  - 修复 TOPIC 图标 Image 未关闭问题
  - 修复富文本节点图标 Image 未关闭问题
- ✅ **修复 LiveDraw.kt 中的 coverImg 泄漏**
  - 在 drawLive() 函数中添加 coverImg.close()

**结论**: 全部模块审计完成，未发现新的资源泄漏问题。

### v1.5.3 (2026-02-11)

**Skia Image 原生内存泄漏彻底修复** 🔧
- ✅ **修复 Image 对象未关闭导致的内存泄漏**
  - v1.5.2 仅修复了 Surface 的关闭，但遗漏了 Image 对象的关闭
  - Image 对象同样持有 Skia 原生内存，必须显式调用 `close()` 释放
  - 每次链接解析后内存增加 20-30MB 的问题现已彻底解决
- ✅ **新增资源管理工具函数**
  - 新增 `cacheImageAndClose()` 函数，缓存图片后自动关闭 Image
  - 新增 `cacheImageAndCloseAll()` 函数，支持批量关闭中间 Image
- ✅ **修复的文件和函数**
  - `General.kt` - 新增 `cacheImageAndClose()` 和 `cacheImageAndCloseAll()`
  - `DynamicDraw.kt` - 修复 `makeDrawDynamic()`、`drawDynamic()`、`assembleCard()`
  - `ResolveLinkService.kt` - 修复 `drawGeneral()` 和 `LinkType.drawGeneral()`
  - `LiveDraw.kt` - 修复 `makeDrawLive()`、`drawLive()`
  - `DynamicMajorDraw.kt` - 修复 `Opus.drawGeneral()`
  - `DynamicMessageTasker.kt` - 修复 `dynamicImages()`
- ✅ **assembleCard() 函数增强**
  - 新增 `closeInputImages` 参数，支持自动关闭输入的 Image 列表
  - 关闭 badge Image，防止小图标泄漏

**技术说明** 📝
- Skia 的 `Image` 和 `Surface` 都继承自 `RefCnt`，持有原生内存
- JVM GC 只能回收 Java 对象引用，无法回收 Skia 原生内存
- 必须在使用完毕后显式调用 `close()` 方法释放原生资源
- 使用 try-finally 模式确保资源在任何情况下都能正确释放

### v1.5.2 (2026-02-11)

**Skia 原生内存泄漏修复** 🔧
- ✅ **修复 Surface 原生内存泄漏**
  - 修复所有绘图模块中 Skia Surface 对象未正确关闭导致的原生内存泄漏
  - 每次链接解析后内存增加 20-30MB 的问题已解决
- ✅ **新增安全资源管理工具函数**
  - 新增 `createImage()` 内联函数，确保 Surface 在绘图完成后自动关闭
  - 新增 `createImageWithArea()` 内联函数，支持指定区域截图并自动释放资源
- ✅ **修复的文件**
  - `General.kt` - 修复 imageMiss 和 SVGDOM.makeImage()
  - `DynamicDraw.kt` - 修复 assembleCard、drawBlockedDefault、makeCardBg
  - `DynamicMajorDraw.kt` - 修复 12 处 Surface 使用（视频、文章、音乐等所有类型）
  - `DynamicModuleDraw.kt` - 修复 6 处 Surface 使用
  - `LiveDraw.kt` - 修复 drawLive、drawAvatar
  - `QrCodeDraw.kt` - 修复 loginQrCode

**技术说明** 📝
- Skia 是 C++ 图形库，其对象（如 Surface）在 JVM 堆外分配原生内存
- 即使 JVM 对象被 GC 回收，原生内存也不会自动释放
- 必须显式调用 `close()` 方法释放原生资源
- 使用 try-finally 模式确保资源在任何情况下都能正确释放

### v1.5.1 (2026-02-10)

**内存泄漏修复** 🔧
- ✅ **P0 高风险修复**
  - 修复 BiliTasker 任务取消后未从列表移除导致的内存泄漏
  - 修复 SendTasker.messageQueue Channel 未关闭的问题
  - 修复 eventCollectorJob 协程未等待完成的问题
- ✅ **P1 中风险修复**
  - 优化 NapCatClient.sendChannel 容量从 1000 降至 200，降低内存占用
  - 修复 ListenerTasker 缓存清理协程 isActive 检查问题
- ✅ **P2 低风险修复**
  - 删除未使用的 missChannel，清理无用代码
- ✅ **P3 稳定性修复**
  - 新增 Channel 背压监控，集成到 ProcessGuardian 守护进程
  - 新增缓存清理失败告警机制，连续失败 3 次发送管理员通知
  - 配置 OkHttp 连接池参数（最大空闲连接 3，保持时间 3 分钟）
  - 增强 Shutdown Hook 超时保护（15 秒超时强制退出）
  - 新增 sendChannel 容量监控，队列满时记录告警日志

**新增守护进程** 🛡️
- ✅ 新增 ProcessGuardian 综合守护进程
  - 任务健康监控：每30秒检测已停止的任务
  - 内存使用监控：监控 JVM 内存使用率，超过阈值自动清理
  - 连接状态监控：监控 WebSocket 连接状态
  - 僵尸任务清理：自动清理已停止的任务引用，防止内存泄漏
  - Channel 背压监控：检测消息队列积压情况
  - 监控日志记录：将监控信息写入 `logs/daemon/Daemon_YYYY-MM-DD.log`
    - 任务健康状态（异常时记录任务名）
    - 内存使用（Top 5 内存占用组件）
    - 连接状态（异常时记录）
    - 僵尸任务清理记录
    - Channel 背压告警

**技术改进** ⚡
- ✅ 所有资源正确管理，无资源泄漏
- ✅ 使用 `PrintWriter.use {}` 确保文件写入后自动关闭
- ✅ 守护进程随程序启动持久化运行

### v1.5 (2026-02-05)

**安全加固** 🔒
- ✅ 修复 Channel 无界缓存问题 (CWE-770)
  - 为所有 Channel 添加容量限制，防止内存耗尽
  - 优化缓冲区大小配置，提高资源使用效率
- ✅ 添加 lateinit 属性初始化检查
  - 在访问 lateinit 属性前添加 isInitialized 检查
  - 防止 UninitializedPropertyAccessException 异常
- ✅ 添加内网地址检测防止 SSRF 攻击 (CWE-918)
  - 实现完整的私有 IP 地址检测机制
  - 阻止对内网地址的 HTTP 请求
- ✅ 修复路径遍历漏洞 (CWE-22)
  - 添加路径规范化和安全检查
  - 防止通过 ../ 等方式访问系统敏感文件
- ✅ 添加 WebSocket TLS 支持配置
  - 支持 wss:// 协议的安全连接
  - 提升 NapCat 连接的安全性

**测试与验证** ✅
- ✅ 完成中危安全漏洞修复验证测试
  - 验证所有安全修复的有效性
  - 确保修复不影响现有功能

### v1.4.2 (2026-01-28)

**安全修复** 🔒
- ✅ 修复配置保存时敏感信息日志泄露 (CWE-532)
  - 移除 `BiliConfigManager.saveData()` 中输出 YAML 内容的日志
  - 改为仅记录数据大小的 debug 级别日志
- ✅ 修复 `BiliCookie.toString()` 敏感信息暴露
  - `toString()` 方法现在只显示前几位字符，其余用 *** 替代
  - 新增 `toHeaderString()` 方法用于实际 HTTP 请求
  - 防止 Cookie 在日志中完整泄露
- ✅ Docker 容器安全加固
  - 容器改为使用非 root 用户 (botuser, UID 1000) 运行
  - 符合 Docker 安全最佳实践，降低容器逃逸风险

**文档更新** 📝
- ✅ 新增 `docs/SECURITY.md` 安全配置和最佳实践文档
- ✅ README.md 添加安全提示章节

### v1.4 (2026-01-21)

**功能增强**
- ✅ 新增从分组移除订阅的命令
- ✅ 新增日志清理机制

**渲染与解析**
- ✅ 优化影视类解析渲染模板
- ✅ 更新直播链接解析模板
- ✅ 调整命令相关显示效果

**修复与维护**
- ✅ 修复番剧订阅问题
- ✅ 清理弃用配置并修复轮询配置项
- ✅ 修复编译错误
- ✅ 更新版本号显示与脚本版本号

### v1.3 (2026-01-09)

**用户体验优化**
- ✅ 时间显示优化：实现相对时间显示功能
  - 5分钟内显示"刚刚"
  - 5-10分钟显示"5分钟前"
  - 10-30分钟显示"10分钟前"
  - 30-60分钟显示"30分钟前"
  - 1-24小时显示"X小时前"
  - 1-3天显示"X天前"
  - 超过3天显示具体时间（yyyy年MM月dd日 HH:mm）
  - 区分开始时间（使用相对时间）和结束时间（使用绝对时间）
- ✅ 去除所有时间显示中的秒数，使时间显示更简洁

**功能修复**
- ✅ 修复 `/check` 命令：从发送5条动态改为只发送1条最新动态
- ✅ 修复字体加载逻辑：
  - 移除对不存在字体的加载尝试（LXGWWenKai）
  - 优先使用内嵌字体（Source Han Sans SC）
  - 优化默认字体列表，添加 Microsoft YaHei 等常见系统字体
  - 将字体加载失败日志从 WARN 降级为 DEBUG，减少非关键警告信息

**代码优化**
- 📝 清理误导性的日志输出，提高日志可读性
- 📝 完善字体加载流程文档和注释

### v1.2 (2026-01-08)

**核心功能增强**
- 🚀 动态轮询优化：支持时间段调频，可配置低频时段（如晚22点-早8点）使用随机间隔，降低API请求频率
  - 配置 `lowSpeedTime` 设置低频时段（默认 "22-8" 表示晚22点到早8点）
  - 配置 `lowSpeedRange` 设置低频时段的随机间隔范围（默认 "60-240" 秒，为正常间隔的2倍）
  - 配置 `normalRange` 设置正常时段的随机间隔范围（默认 "30-120" 秒）
  - 伪随机间隔避免固定间隔请求被B站检测为爬虫
- 🚀 关键词过滤器：支持按联系人配置黑白名单，精确控制推送内容
  - 类型过滤：可屏蔽或只允许特定类型动态（转发、视频、专栏、音乐、直播等）
  - 正则过滤：支持正则表达式匹配动态内容（如屏蔽抽奖、广告等）
  - 支持黑名单和白名单两种模式

**重要修复**
- ✅ 修复黑名单机制覆盖：解决特定联系人消息绕过黑名单和过滤器的问题
- ✅ 改进推送失败诊断：
  - 添加 API 响应解析，记录失败的retcode和错误信息
  - 增强错误日志，明确标注失败的联系人
  - 提示检查 NapCat 日志获取详细错误原因
- ✅ 修复目录初始化问题：启动时自动创建所有必要目录（config, data, temp, logs）

**改进优化**
- ✅ 启动日志显示版本号：便于识别当前运行版本
- ✅ 清理废弃代码：移除大量注释的 Mirai 相关代码（约 123 行）
- ✅ 改进日志输出：优化错误信息格式，提高问题定位效率

### v1.1 (2026-01-06)

**重要修复**
- ✅ 修复字体渲染问题：更换项目字体为思源黑体，解决部分字符显示异常（如"复"字显示偏瘦）
- ✅ 修复防循环机制漏洞：完善链接解析黑名单机制，避免多个 bot 同时解析时发生循环
- ✅ 修复哔哩哔哩 HD 版链接解析：支持 HD 版小程序发送的链接
- ✅ 修复 JSON 和链接同时发送时绕过防循环机制的问题

**番剧/电影解析优化**
- ✅ 修复电影解析失败：正确处理 `title` 字段为字符串的情况（如"正片"）
- ✅ 修复特殊番剧解析失败：支持 `title` 字段包含特殊格式（如"Extra(4.5)"）
- ✅ 修复番剧简介显示问题：确保正确获取并显示详细信息
- ✅ 优化番剧模板：重新设计信息布局，包括开播时间、更新状态、播放数据和简介
- ✅ 修复电影顶部标签：将"番剧"标签正确替换为"电影"
- ✅ 优化简介显示：确保简介内容填满信息栏
- ✅ 修复番剧/电影卡片垂直居中问题：卡片内容现在正确地在图片中垂直居中显示

**渲染优化**
- ✅ 修复粉丝牌数字显示：添加专用字体文件 `FansCard.ttf` 支持粉丝牌数字渲染
- ✅ 修复视频时长对齐问题：调整视频时长、观看次数和弹幕数的排版对齐

**功能增强**
- 🚀 支持多链接解析：一条消息中包含多个 B站链接时，bot 会逐个解析并发送结果

**日志改进**
- ✅ 添加完整的错误日志：为所有失败任务添加适当的警告或错误日志输出
- ✅ 优化日志显示方式：在文档中添加查看 DEBUG 级日志的说明

### v1.0.1 (2026-01-04)

**Docker 支持**
- ✅ 发布 Docker Hub 官方镜像：https://hub.docker.com/r/menghuanan/dynamic-bot
- ✅ 提供 `latest` 和 `v1.0` 两个标签
- ✅ 无需编译即可快速部署
- ✅ 添加完整的 Docker 部署文档
- ✅ 修复 Docker 环境中的图形库依赖问题（Skiko/QR码生成）
  - 添加完整的 X11 图形库支持（libxi6, libxtst6 等）
  - 配置 Xvfb 虚拟显示环境
  - 确保 `/login` 命令的二维码生成功能正常工作

### v1.0 (2026-01-04)

**核心修复**
- ✅ 数据持久化问题：实现 BiliDataWrapper 包装类，解决 object var 字段序列化问题
- ✅ 推送功能失败：修复联系人 ID 格式兼容问题（支持 `group:123456` 和 `g123456`）
- ✅ 资源文件加载：使用 ClassLoader 加载资源，修复 JAR 中 SVG 图标无法加载问题
- ✅ 动态检查失败：修正 lastDynamic 初始化逻辑，避免遗漏已发布动态
- ✅ 直播推送失败：修正 lastLive 时间窗口，确保不遗漏已开播的直播

**功能增强**
- 🚀 白名单与分组管理：支持精确控制推送范围，批量订阅管理
- 🚀 双源直播检查：同时检查关注列表和订阅列表中的直播状态
- 🚀 缓存自动清理：启动时和定时清理过期缓存，可配置保留时间
- 🚀 日志优化：简化控制台输出，降级 NapCat 重复日志

**配置改进**
- 📝 数据文件迁移：从 `data/BiliData.yml` 自动迁移到 `config/BiliData.yml`
- 📝 检查间隔优化：动态检查和直播检查独立配置（默认15秒）
- 📝 推送模板扩展：支持自定义动态、直播、下播通知模板

### 早期版本

1. **链接解析增强**
   - 添加了 QQ 小程序链接解析支持
   - 短链接自动转换为标准 BV 号
   - 优化了无限循环防护机制

2. **番剧渲染优化**
   - 添加了番剧介绍显示
   - 新增元信息显示（标签、首播时间、完结状态、集数等）
   - 优化了布局，减少空白区域

3. **字体加载改进**
   - 支持从 resources 目录加载字体
   - 字体文件可以打包到 JAR 中
   - 无需手动下载字体文件

4. **订阅功能完善**
   - 添加了订阅管理命令
   - 支持手动触发检查（/check）
   - 优化了推送逻辑

## 故障排查

### 启用 DEBUG 日志

如果遇到问题需要提交 Bug 报告，可以启用 DEBUG 级别日志来获取更详细的信息：

1. **启动时加入--debug**

   在启动时加入--debug ：

   ```powershell
   java -jar dynamic-bot-1.4.jar --debug
   ```

1. **Docker 部署启用 Debug**

   编辑挂载目录中的 `config/BiliConfig.yml`：

   ```yaml
   enableConfig:
     debugMode: true
   ```

   重启容器：

   ```bash
   docker restart dynamic-bot
   # 或
   docker-compose restart
   ```
2. **查看日志**

   - 控制台会显示 DEBUG 级别的详细日志
   - 日志文件位于 `logs/bilibili-bot.log`
   - 错误日志位于 `logs/error.log`

**注意**: DEBUG 日志会输出大量信息，仅在排查问题时启用，日常使用建议保持 INFO 级别。

## 许可证

本项目基于 AGPL-3.0 许可证开源。

## 联系方式

如有问题，请在 GitHub 上提交 Issue。
