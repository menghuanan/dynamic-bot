# BiliBili 动态推送 Bot v1.3

[![Docker Hub](https://img.shields.io/docker/v/menghuanan/dynamic-bot?label=Docker%20Hub&logo=docker)](https://hub.docker.com/r/menghuanan/dynamic-bot)
[![Docker Pulls](https://img.shields.io/docker/pulls/menghuanan/dynamic-bot)](https://hub.docker.com/r/menghuanan/dynamic-bot)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

由 [bilibili-dynamic-mirai-plugin](https://github.com/Colter23/bilibili-dynamic-mirai-plugin) 改造而来。  
代码部分则由 [claude](https://github.com/claude) 倾力打造。  
这是基于 NapCat 的 B站动态推送机器人，支持动态订阅、直播通知、链接解析等功能。

## 项目结构

```
dynamic-bot/
├── src/                    # 源代码目录
│   ├── main/
│   │   ├── kotlin/        # Kotlin 源代码
│   │   │   └── top/
│   │   │       └── bilibili/
│   │   │           ├── api/           # B站 API 接口
│   │   │           ├── client/        # HTTP 客户端
│   │   │           ├── config/        # 配置管理
│   │   │           ├── core/          # 核心模块
│   │   │           ├── data/          # 数据模型
│   │   │           ├── draw/          # 图片渲染
│   │   │           ├── napcat/        # NapCat 客户端
│   │   │           ├── service/       # 业务服务
│   │   │           ├── tasker/        # 定时任务
│   │   │           ├── utils/         # 工具类
│   │   │           ├── BiliConfig.kt  # 配置文件
│   │   │           ├── BiliData.kt    # 数据文件
│   │   │           └── Main.kt        # 程序入口
│   │   └── resources/     # 资源文件
│   │       └── font/      # 字体文件（可选）
│   └── test/              # （已删除旧的 Mirai 测试）
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
- `build/libs/dynamic-bot-1.2.jar`

### 2. 配置文件

首次运行时，程序会自动创建配置文件目录结构：

```
config/
├── bot.yml          # Bot 基础配置（NapCat 连接信息）
└── data.yml         # 订阅数据和推送配置

data/
├── font/            # 字体文件目录
└── cookies.json     # B站 Cookie（可选）

temp/                # 临时文件目录（二维码、缓存等）

logs/                # 日志文件目录
```

### 3. 运行 Bot

#### 方式一：直接运行 JAR

```bash
java -jar build/libs/dynamic-bot-1.2.jar
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

### 3. 管理命令

#### 基础命令
- `/subscribe <UID>` - 订阅用户
- `/unsubscribe <UID>` - 取消订阅
- `/list` - 查看订阅列表
- `/check` - 手动触发检查（测试用）
- `/login` - B站扫码登录

#### 高级命令（/bili）
- `/bili help` - 显示完整帮助
- `/bili add <UID> <群号>` - 将 UP主添加到指定群
- `/bili list` - 查看当前群订阅（无参）
- `/bili list <UID>` - 查看 UID 推送到哪些群
- `/bili group create <分组名>` - 创建群组分组
- `/bili group subscribe <分组名> <UID>` - 订阅到分组
- `/bili groups` - 查看所有分组信息

## 配置说明

### bot.yml 示例

```yaml
# NapCat WebSocket 配置
napcat:
  host: "127.0.0.1"
  port: 3001
  accessToken: ""  # 如果设置了访问令牌

# 管理员 QQ 号
admin: 123456789
```

### data.yml 示例

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

# 检查配置
checkConfig:
  interval: 15  # 动态检查间隔（秒）
  liveInterval: 15  # 直播检查间隔（秒）
  timeout: 10  # API 请求超时（秒）

# 推送配置
pushConfig:
  toShortLink: false  # 是否使用短链接
  pushInterval: 1000  # 推送间隔（毫秒）
  messageInterval: 500  # 消息间隔（毫秒）

# 缓存配置
cacheConfig:
  expires:
    "DRAW": 7        # 绘图缓存保留7天
    "IMAGES": 7      # 图片缓存保留7天
    "EMOJI": 7       # 表情缓存保留7天

# 链接解析配置
linkResolveConfig:
  triggerMode: "Always"  # 触发模式：Always/At/Never
  returnLink: true  # 是否返回链接

# 推送模板配置
dynamicPush: "{draw}\n{name}@{type}\n{link}"
livePush: "{draw}\n{name}@直播\n{link}"
liveClose: "{name} 直播结束啦!\n直播时长: {duration}"

# 功能开关
drawEnable: true  # 启用图片生成
liveCloseNotifyEnable: true  # 启用下播通知
cacheClearEnable: true  # 启用定时缓存清理
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
  host: "host.docker.internal"  # Docker 访问宿主机的特殊域名
  port: 3001
  token: ""

admin: 你的QQ号
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

- `latest` - 最新版本（v1.3）
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
- **JVM 参数**: `-Xms256m -Xmx512m -XX:+UseG1GC`
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
.\docker-push.ps1 v1.3       # 推送指定版本标签
```

## 开发说明

### 技术栈
- Kotlin 1.9.22
- Ktor 2.3.7（HTTP 客户端）
- Skiko 0.7.93（图片渲染）
- kotlinx.serialization（JSON 处理）
- kotlinx.coroutines（协程）
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

1. **修改日志配置文件**

   编辑 `src/main/resources/logback.xml` 文件，将以下两行的 `INFO` 改为 `DEBUG`：

   ```xml
   <!-- 应用日志级别 -->
   <logger name="top.bilibili" level="DEBUG"/>

   <root level="DEBUG">
   ```

2. **重新编译并运行**

   ```bash
   ./gradlew build
   java -jar build/libs/dynamic-bot-all.jar
   ```

3. **Docker 环境启用 DEBUG**

   如果使用 Docker，需要重新构建镜像：

   ```bash
   # 修改 logback.xml 后重新构建
   docker-compose build
   docker-compose up -d
   ```

4. **查看日志**

   - 控制台会显示 DEBUG 级别的详细日志
   - 日志文件位于 `logs/bilibili-bot.log`
   - 错误日志位于 `logs/error.log`

**注意**: DEBUG 日志会输出大量信息，仅在排查问题时启用，日常使用建议保持 INFO 级别。

## 许可证

本项目基于 AGPL-3.0 许可证开源。

## 联系方式

如有问题，请在 GitHub 上提交 Issue。
