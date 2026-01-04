# BiliBili 动态推送 Bot v1.0

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

编译完成后，可执行文件位于：
- `build/libs/dynamic-bot-1.0.jar`

### 2. 配置文件

首次运行时，程序会自动创建配置文件目录结构：

```
config/
├── bot.yml          # Bot 基础配置（NapCat 连接信息）
└── data.yml         # 订阅数据和推送配置

data/
├── font/            # 字体文件目录
└── cookies.json     # B站 Cookie（可选）
```

### 3. 运行 Bot

```bash
java -jar build/libs/dynamic-bot-1.0.jar
```

或者使用 Docker：

```bash
docker-compose up -d
```

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

### 快速部署

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
- **健康检查**: 每30秒检查一次进程状态
- **日志限制**: 10MB × 3 文件（自动轮转）
- **重启策略**: unless-stopped

### 自动化脚本

Windows 用户可使用 `docker-deploy.ps1` 脚本：
```powershell
.\docker-deploy.ps1 build   # 构建镜像
.\docker-deploy.ps1 start   # 启动容器
.\docker-deploy.ps1 stop    # 停止容器
.\docker-deploy.ps1 logs    # 查看日志
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

## 许可证

本项目基于 AGPL-3.0 许可证开源。

## 联系方式

如有问题，请在 GitHub 上提交 Issue。
