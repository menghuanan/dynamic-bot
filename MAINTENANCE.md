# BiliBili 动态推送 Bot - 维护文档

本文档为项目维护者提供详细的维护指南，包括项目结构、修改建议、配置说明和版本发布流程。

## 目录

- [项目结构](#项目结构)
- [版本号管理](#版本号管理)
- [配置文件详解](#配置文件详解)
- [模板系统详解](#模板系统详解)
- [可修改的部分](#可修改的部分)
- [不建议修改的部分](#不建议修改的部分)
- [发布流程](#发布流程)
- [Docker 镜像构建与发布](#docker-镜像构建与发布)

---

## 项目结构

```
dynamic-bot/
├── src/main/kotlin/top/bilibili/
│   ├── api/                      # B站 API 接口封装
│   │   ├── Api.kt               # API 基础接口定义
│   │   ├── Dynamic.kt           # 动态相关 API
│   │   ├── Live.kt              # 直播相关 API
│   │   ├── Pgc.kt               # 番剧/影视相关 API
│   │   ├── User.kt              # 用户相关 API
│   │   └── Video.kt             # 视频相关 API
│   │
│   ├── client/                   # HTTP 客户端
│   │   └── BiliClient.kt        # B站 HTTP 客户端封装
│   │
│   ├── core/                     # 核心模块
│   │   ├── BiliBiliBot.kt       # Bot 核心逻辑
│   │   └── ContactId.kt         # 联系人 ID 管理
│   │
│   ├── data/                     # 数据模型
│   │   ├── Article.kt           # 专栏数据模型
│   │   ├── BascLink.kt          # 基础链接模型
│   │   ├── BiliMessage.kt       # 消息模型
│   │   ├── Dynamic.kt           # 动态数据模型
│   │   ├── Live.kt              # 直播数据模型
│   │   ├── Login.kt             # 登录数据模型
│   │   ├── Pgc.kt               # 番剧数据模型
│   │   └── Video.kt             # 视频数据模型
│   │
│   ├── draw/                     # 图片渲染模块
│   │   ├── DynamicDraw.kt       # 动态卡片绘制
│   │   ├── DynamicMajorDraw.kt  # 主要内容绘制
│   │   ├── DynamicModuleDraw.kt # 模块化绘制
│   │   ├── LiveDraw.kt          # 直播卡片绘制
│   │   └── Quality.kt           # 图片质量配置
│   │
│   ├── napcat/                   # NapCat 客户端
│   │   ├── NapCatClient.kt      # WebSocket 客户端
│   │   └── OneBotModels.kt      # OneBot 协议模型
│   │
│   ├── service/                  # 业务服务
│   │   ├── DynamicService.kt    # 动态订阅服务
│   │   ├── General.kt           # 通用服务
│   │   ├── PgcService.kt        # 番剧服务
│   │   └── ResolveLinkService.kt # 链接解析服务
│   │
│   ├── tasker/                   # 定时任务
│   │   ├── BiliCheckTasker.kt   # 检查任务基类
│   │   ├── BiliTasker.kt        # 任务基类
│   │   ├── DynamicCheckTasker.kt        # 动态检查任务
│   │   ├── DynamicMessageTasker.kt      # 动态消息处理
│   │   ├── LiveCheckTasker.kt           # 直播检查任务
│   │   ├── LiveCloseCheckTasker.kt      # 直播结束检查
│   │   ├── LiveMessageTasker.kt         # 直播消息处理
│   │   ├── ListenerTasker.kt            # 消息监听任务
│   │   └── SendTasker.kt                # 消息发送任务
│   │
│   ├── utils/                    # 工具类
│   │   ├── FontUtils.kt         # 字体加载工具
│   │   ├── General.kt           # 通用工具函数
│   │   └── JsonUtils.kt         # JSON 处理工具
│   │
│   ├── BiliConfig.kt             # 配置数据类
│   ├── BiliConfigManager.kt     # 配置管理器
│   ├── BiliData.kt               # 运行时数据
│   ├── Init.kt                   # 初始化逻辑
│   └── Main.kt                   # 程序入口
│
├── src/main/resources/
│   ├── font/                     # 内嵌字体文件
│   │   ├── FansCard.ttf         # 粉丝卡字体
│   │   └── SourceHanSansSC-Regular.otf  # 思源黑体
│   ├── icon/                     # 图标资源
│   ├── image/                    # 图片资源
│   └── logback.xml               # 日志配置
│
├── build.gradle.kts              # Gradle 构建脚本
├── Dockerfile                    # Docker 镜像构建文件
├── docker-compose.yml            # Docker Compose 配置
└── README.md                     # 项目说明文档
```

---

## 版本号管理

### 版本号规范

项目采用语义化版本号：`主版本号.次版本号.修订号`

- **主版本号**：不兼容的 API 修改
- **次版本号**：向下兼容的功能性新增
- **修订号**：向下兼容的问题修正

### 版本号位置

修改版本号时需要同步更新以下文件：

#### 1. `build.gradle.kts`

```kotlin
// 第12行：项目版本号
version = "1.3"

// 第87行：Shadow JAR 版本号
tasks.shadowJar {
    archiveVersion.set("1.3")
}

// 第144-152行：启动脚本中的版本号
file("scripts/start.bat").writeText("""
    java -jar lib\dynamic-bot-${version}.jar
""".trimIndent())

file("scripts/start.sh").writeText("""
    java -jar lib/dynamic-bot-${version}.jar
""".trimIndent())
```

#### 2. `Dockerfile`

```dockerfile
# 第31行：复制 JAR 文件时的版本号
COPY build/libs/dynamic-bot-1.3.jar /app/bot.jar
```

#### 3. `README.md`

```markdown
# 第1行：标题版本号
# BiliBili 动态推送 Bot v1.3

# 第306-310行：Docker 标签说明
- `latest` - 最新版本（v1.3）
- `v1.3` - 稳定版本 v1.3

# 第368行：推送脚本示例
.\docker-push.ps1 v1.3

# 第404行：更新日志
### v1.3 (2026-01-09)
```

#### 4. `src/main/kotlin/top/bilibili/core/BiliBiliBot.kt`

```kotlin
// 第105行：启动日志显示
logger.info("  BiliBili 动态推送 Bot v1.3")
```

#### 5. `src/main/kotlin/top/bilibili/Main.kt`

```kotlin
// 第28行：帮助信息显示
BiliBili 动态推送 Bot v1.3
```

### 版本号更新脚本

建议使用以下 PowerShell 脚本批量更新版本号：

```powershell
# update-version.ps1
param(
    [Parameter(Mandatory=$true)]
    [string]$NewVersion
)

$files = @(
    "build.gradle.kts",
    "Dockerfile",
    "README.md",
    "src/main/kotlin/top/bilibili/core/BiliBiliBot.kt",
    "src/main/kotlin/top/bilibili/Main.kt"
)

foreach ($file in $files) {
    if (Test-Path $file) {
        (Get-Content $file) -replace 'v?\d+\.\d+', $NewVersion | Set-Content $file
        Write-Host "Updated: $file"
    }
}
```

---

## 配置文件详解

### 1. BiliConfig.yml

配置文件位于 `config/BiliConfig.yml`，包含以下主要配置项：

#### 账号配置 (accountConfig)

```yaml
accountConfig:
  cookie: ""              # BiliBili Cookie (必填)
  autoFollow: true        # 自动关注订阅的UP主
  followGroup: "动态推送"  # 关注分组名称
```

**修改限制：**
- ✅ `cookie` 可随时修改，需要重启生效
- ✅ `autoFollow` 可修改，建议保持 true
- ✅ `followGroup` 可自定义分组名称

#### NapCat 配置 (napCatConfig)

```yaml
napCatConfig:
  host: "127.0.0.1"       # NapCat WebSocket 地址
  port: 3001              # NapCat WebSocket 端口
  timeout: 10000          # 连接超时时间（毫秒）
```

**修改限制：**
- ✅ `host` 根据实际部署调整（Docker 中使用 `host.docker.internal`）
- ✅ `port` 需与 NapCat 配置一致
- ⚠️ `timeout` 建议保持默认值，除非网络较差

#### 功能开关 (enableConfig)

```yaml
enableConfig:
  drawEnable: true        # 是否生成动态图片
  notifyEnable: false     # 管理员通知功能
  debugMode: false        # Debug 日志模式（可用 --debug 覆盖）
  autoFollow: true        # 自动关注订阅的UP主
```

**修改限制：**
- ✅ `drawEnable` 关闭可减少资源占用，但失去图片推送
- ✅ `notifyEnable` 生产环境建议关闭
- ✅ `debugMode` 调试时开启，正常运行建议关闭
- ✅ `autoFollow` 建议开启，自动关注订阅的UP主

#### 检查配置 (checkConfig)

```yaml
checkConfig:
  interval: 15            # 动态检查间隔（秒）
  liveInterval: 15        # 直播检查间隔（秒）
  normalRange: "30-120"   # 正常时段随机间隔范围（秒）
  lowSpeedTime: "22-8"    # 低频时段（时-时）
  lowSpeedRange: "60-240" # 低频时段随机间隔范围（秒）
```

**修改限制：**
- ⚠️ `interval` 不建议低于 15 秒，避免频繁请求被限制
- ⚠️ `liveInterval` 不建议低于 15 秒
- ✅ `normalRange` 可调整，格式为 "最小-最大"
- ✅ `lowSpeedTime` 可自定义低频时段
- ✅ `lowSpeedRange` 建议为 normalRange 的 2 倍

#### 推送配置 (pushConfig)

```yaml
pushConfig:
  pushInterval: 500       # 消息发送间隔（毫秒）
```

**修改限制：**
- ⚠️ `pushInterval` 不建议低于 500ms，避免消息发送过快被限制

#### 缓存配置 (cacheConfig)

```yaml
cacheConfig:
  enable: true            # 是否启用缓存清理
  clean: true             # 启动时清理缓存
  downloadOriginal: false # 是否下载原始图片
  interval: 86400         # 定时清理间隔（秒）
  keepTime: 432000        # 缓存保留时间（秒）
```

**修改限制：**
- ✅ `enable` 建议开启，避免缓存堆积
- ✅ `clean` 建议开启
- ✅ `downloadOriginal` 开启会占用更多空间但画质更好
- ✅ `interval` 默认 24 小时清理一次
- ✅ `keepTime` 默认保留 5 天

#### 链接解析配置 (linkResolveConfig)

```yaml
linkResolveConfig:
  enable: true            # 是否启用链接解析
  banList:                # 解析黑名单（防循环）
    - bot-qq-number       # 替换为实际 Bot QQ 号
```

**修改限制：**
- ✅ `enable` 可关闭链接解析功能
- ✅ `banList` 必须添加本 Bot 的 QQ 号，防止循环解析

---

## 模板系统详解

模板系统用于自定义消息推送格式，支持占位符替换。

### 模板占位符

#### 通用占位符

| 占位符 | 说明 | 示例 |
|--------|------|------|
| `{name}` | UP主名称 | 张三 |
| `{uid}` | UP主UID | 123456 |
| `{type}` | 内容类型 | 动态、视频、直播 |
| `{time}` | 发布时间 | 刚刚、5分钟前、2026年01月09日 19:00 |
| `{link}` | 内容链接 | https://t.bilibili.com/... |
| `{draw}` | 渲染图片 | （图片）|
| `{content}` | 文本内容 | 动态正文或视频标题 |
| `{images}` | 图片列表 | （图片1）（图片2）... |

#### 动态专用占位符

| 占位符 | 说明 | 示例 |
|--------|------|------|
| `{id}` | 动态ID | 98765432101234567 |

#### 直播专用占位符

| 占位符 | 说明 | 示例 |
|--------|------|------|
| `{title}` | 直播标题 | 我的直播间 |
| `{area}` | 直播分区 | 娱乐-唱见 |
| `{id}` | 直播间ID | 123456 |

#### 下播专用占位符

| 占位符 | 说明 | 示例 |
|--------|------|------|
| `{endTime}` | 结束时间 | 2026年01月09日 20:00 |
| `{duration}` | 直播时长 | 2小时30分钟 |

### 模板配置示例

#### 1. 动态推送模板 (dynamicTemplate)

```yaml
dynamicTemplate:
  TwoMsg: "{draw}\r{name}@{uid}@{type}\n{time}\n{link}"
```

**说明：**
- `\r` 表示换行（在QQ消息中会渲染为换行）
- `\n` 表示换行符
- 图片（`{draw}`）会单独作为一条消息发送
- 文本信息作为第二条消息发送

**效果：**
```
[图片]

张三@123456@动态
5分钟前
https://t.bilibili.com/98765432101234567
```

#### 2. 直播推送模板 (liveTemplate)

```yaml
liveTemplate:
  TwoMsg: "{draw}\r{name}@{uid}@直播\n{title}\n{time}\n{link}"
```

**说明：**
- 直播标题使用 `{title}` 显示
- 其他占位符与动态模板相同

**效果：**
```
[图片]

张三@123456@直播
我的直播间
刚刚
https://live.bilibili.com/123456
```

#### 3. 下播通知模板 (liveCloseTemplate)

```yaml
liveCloseTemplate:
  SingleMsg: "下播通知\n{name} @{uid}\n{title}\n开播时间：{time}\n下播时间：{endTime}\n直播时长：{duration}\n{link}"
```

**说明：**
- `{time}` 是开播时间（相对时间，如"2小时前"）
- `{endTime}` 是下播时间（绝对时间，如"2026年01月09日 20:00"）
- `{duration}` 是直播时长（如"2小时30分钟"）

**效果：**
```
下播通知
张三 @123456
我的直播间
开播时间：2小时前
下播时间：2026年01月09日 20:00
直播时长：2小时30分钟
https://live.bilibili.com/123456
```

#### 4. 动态卡片 Footer 模板 (footer)

```yaml
footer:
  dynamicFooter: "{name}@{uid}  {type}  {time}"
  liveFooter: "{name}@{uid}  {type}  {time}"
```

**说明：**
- 这些模板用于渲染动态卡片图片底部的信息
- 仅在 `drawEnable: true` 时生效
- 模板为空字符串时不显示 Footer

**效果：**
```
（卡片图片底部显示）
张三@123456  动态  5分钟前
```

### 模板修改建议

#### ✅ 推荐修改

1. **调整信息排版**
   ```yaml
   TwoMsg: "{draw}\r【{type}】{name}\n发布于：{time}\n{link}"
   ```

2. **简化信息**
   ```yaml
   TwoMsg: "{draw}\r{name}发布了{type}\n{link}"
   ```

3. **添加自定义前缀**
   ```yaml
   TwoMsg: "{draw}\r【B站动态】{name}@{type}\n{time}\n{link}"
   ```

#### ⚠️ 注意事项

1. **占位符区分大小写**
   - ❌ `{NAME}` 无效
   - ✅ `{name}` 有效

2. **特殊字符转义**
   - `\r` 表示换行（推荐）
   - `\n` 表示换行符
   - 不要使用 `\t`（制表符），QQ 不支持

3. **图片占位符**
   - `{draw}` 只能用于消息模板，不能用于 Footer
   - `{images}` 是图片URL列表，会自动转换为QQ图片消息

4. **模板长度限制**
   - 单条消息不建议超过 5000 字符
   - 过长的消息可能被QQ截断

#### ❌ 不建议修改

1. **移除必要占位符**
   ```yaml
   # 不推荐：缺少链接
   TwoMsg: "{draw}\r{name}发布了{type}"
   ```

2. **使用不存在的占位符**
   ```yaml
   # 错误：{author} 不存在
   TwoMsg: "{draw}\r作者：{author}\n{link}"
   ```

3. **错误的换行符**
   ```yaml
   # 不推荐：\t 在QQ中不显示
   TwoMsg: "{name}\t{type}\t{link}"
   ```

### 模板调试

修改模板后，可以通过以下方式测试：

1. **重启 Bot**
   ```bash
   # 重启以加载新配置
   docker restart dynamic-bot
   ```

2. **触发测试推送**
   - 订阅一个UP主
   - 等待该UP主发布动态
   - 检查推送格式是否符合预期

3. **使用 /check 命令**
   ```
   /check
   ```
   手动触发一次动态检查，快速测试模板效果

---

## 可修改的部分

### 1. 配置相关

✅ **推荐修改**

- 所有 `config/BiliConfig.yml` 中的配置项
- 消息推送模板
- 检查间隔时间
- 缓存策略
- NapCat 连接参数

✅ **可以修改（需注意影响）**

- 图片渲染质量 (`imageConfig`)
- 字体配置 (`fontConfig`)
- 日志级别 (`logback.xml`)

### 2. 代码相关

✅ **可以修改**

- `src/main/kotlin/top/bilibili/BiliConfig.kt` - 添加新的配置项
- `src/main/kotlin/top/bilibili/service/` - 修改业务逻辑
- `src/main/kotlin/top/bilibili/draw/` - 调整图片渲染样式
- `src/main/kotlin/top/bilibili/tasker/` - 修改任务执行逻辑

✅ **建议扩展而非修改**

- 添加新的API接口到 `api/` 目录
- 添加新的数据模型到 `data/` 目录
- 添加新的工具函数到 `utils/` 目录

### 3. 资源文件

✅ **可以替换**

- `src/main/resources/font/` - 字体文件
- `src/main/resources/icon/` - 图标文件
- `src/main/resources/image/` - 图片资源

### 4. Docker 相关

✅ **可以修改**

- `Dockerfile` - JVM 参数、依赖安装
- `docker-compose.yml` - 容器配置、网络设置
- `docker-deploy.ps1` - 部署脚本

---

## 不建议修改的部分

### 1. 核心逻辑

❌ **不建议修改（除非非常了解代码）**

- `src/main/kotlin/top/bilibili/core/BiliBiliBot.kt` - Bot 核心初始化和消息分发
- `src/main/kotlin/top/bilibili/client/BiliClient.kt` - HTTP 客户端封装
- `src/main/kotlin/top/bilibili/napcat/NapCatClient.kt` - WebSocket 客户端

**原因：** 这些是核心组件，修改不当会导致整个系统无法工作

### 2. 数据模型

❌ **不建议修改（除非API变更）**

- `src/main/kotlin/top/bilibili/data/` - 所有数据模型类

**原因：** 数据模型需要与B站API返回的JSON结构严格对应，修改可能导致解析失败

### 3. API 封装

❌ **谨慎修改**

- `src/main/kotlin/top/bilibili/api/` - B站 API 接口

**原因：** API 调用涉及认证、签名等复杂逻辑，修改不当可能被B站限制访问

### 4. 构建配置

❌ **不建议随意修改**

- `build.gradle.kts` - 依赖版本、构建任务
- `settings.gradle.kts` - Gradle 设置

**原因：** 依赖版本之间存在兼容性要求，随意升级可能导致编译失败

### 5. 序列化配置

❌ **不要修改**

- `BiliConfigManager.kt` 中的序列化逻辑
- `BiliData.kt` 中的数据包装类

**原因：** 序列化逻辑确保配置和数据正确保存/加载，修改可能导致数据丢失

---

## 发布流程

### 1. 准备阶段

1. **确认所有修改已提交**
   ```bash
   git status
   ```

2. **运行测试（如果有）**
   ```bash
   ./gradlew test
   ```

3. **本地编译测试**
   ```bash
   ./gradlew clean build
   ```

### 2. 版本号更新

按照 [版本号管理](#版本号管理) 章节更新所有相关文件：

```bash
# 示例：更新到 v1.4
# 1. 修改 build.gradle.kts
version = "1.4"
archiveVersion.set("1.4")

# 2. 修改 Dockerfile
COPY build/libs/dynamic-bot-1.4.jar /app/bot.jar

# 3. 修改 README.md (多处)
# 4. 修改 BiliBiliBot.kt
# 5. 修改 Main.kt
```

### 3. 更新日志

在 `README.md` 的更新日志部分添加新版本记录：

```markdown
### v1.4 (2026-01-XX)

**新功能**
- 🚀 功能描述

**功能优化**
- ✅ 优化描述

**Bug 修复**
- ✅ 修复描述
```

### 4. Git 提交

```bash
git add -A
git commit -m "Release v1.4 - 版本更新说明

详细更新内容：
- 功能1
- 功能2
- Bug修复

Co-Authored-By: Claude <noreply@anthropic.com>"
```

### 5. 创建标签

```bash
git tag -a v1.4 -m "Release version 1.4"
```

### 6. 推送到远程

```bash
git push origin main
git push origin v1.4
```

---

## Docker 镜像构建与发布

### 前提条件

1. **安装 Docker Desktop**
2. **登录 Docker Hub**
   ```bash
   docker login
   ```

### 构建流程

#### 1. 构建 JAR 文件

```bash
# 清理并构建
./gradlew clean shadowJar --no-daemon

# 验证 JAR 文件
ls -lh build/libs/
# 应该看到：dynamic-bot-1.3.jar
```

#### 2. 构建 Docker 镜像

```bash
# 构建并打标签
docker build -t menghuanan/dynamic-bot:v1.3 -t menghuanan/dynamic-bot:latest .

# 查看构建的镜像
docker images | grep dynamic-bot
```

**可能的问题：**

- **网络连接超时**
  ```bash
  ERROR: failed to do request: Head "https://registry-1.docker.io/...": net/http: TLS handshake timeout
  ```
  **解决方案：**
  1. 配置 Docker 镜像加速器
  2. 使用 VPN 或代理
  3. 重试构建

#### 3. 本地测试

```bash
# 运行容器
docker run -d \
  --name dynamic-bot-test \
  -v $(pwd)/config:/app/config \
  -v $(pwd)/data:/app/data \
  menghuanan/dynamic-bot:v1.3

# 查看日志
docker logs -f dynamic-bot-test

# 停止并删除测试容器
docker stop dynamic-bot-test
docker rm dynamic-bot-test
```

#### 4. 推送到 Docker Hub

```bash
# 推送 v1.3 标签
docker push menghuanan/dynamic-bot:v1.3

# 推送 latest 标签
docker push menghuanan/dynamic-bot:latest
```

**使用脚本推送（Windows）：**

```powershell
# 推送最新版本
.\docker-push.ps1 latest

# 推送指定版本
.\docker-push.ps1 v1.3

# 同时推送两个标签
.\docker-push.ps1 latest v1.3
```

#### 5. 验证发布

访问 Docker Hub 页面验证镜像已上传：
```
https://hub.docker.com/r/menghuanan/dynamic-bot/tags
```

### Docker Hub 自动构建（可选）

可以配置 Docker Hub 自动构建功能，在 Git 推送时自动构建镜像：

1. 在 Docker Hub 项目中启用 "Automated Builds"
2. 连接 GitHub 仓库
3. 配置构建规则：
   - Source: `main` 分支 → Tag: `latest`
   - Source: `/^v\d+\.\d+$/` 标签 → Tag: `{sourceref}`

### 构建优化

#### 多平台构建

如果需要支持多平台（如 ARM）：

```bash
# 创建 buildx 构建器
docker buildx create --use

# 多平台构建并推送
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t menghuanan/dynamic-bot:v1.3 \
  -t menghuanan/dynamic-bot:latest \
  --push \
  .
```

#### 减小镜像大小

在 `Dockerfile` 中优化：

```dockerfile
# 使用 alpine 基础镜像（更小）
FROM eclipse-temurin:17-jre-alpine

# 多阶段构建（分离构建和运行环境）
FROM gradle:8.5-jdk17 AS builder
WORKDIR /build
COPY . .
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
COPY --from=builder /build/build/libs/*.jar /app/bot.jar
```

---

## 常见维护任务

### 1. 添加新的配置项

**示例：添加新的通知方式**

1. 在 `BiliConfig.kt` 中添加配置类：
   ```kotlin
   data class NotificationConfig(
       val enabled: Boolean = true,
       val method: String = "websocket",
       val webhook: String = ""
   )
   ```

2. 在 `BiliConfig` 中添加字段：
   ```kotlin
   data class BiliConfig(
       // ... 其他配置
       val notificationConfig: NotificationConfig = NotificationConfig()
   )
   ```

3. 在 `config/BiliConfig.yml` 中添加默认配置：
   ```yaml
   notificationConfig:
     enabled: true
     method: "websocket"
     webhook: ""
   ```

4. 在业务代码中使用：
   ```kotlin
   if (BiliConfigManager.config.notificationConfig.enabled) {
       // 发送通知
   }
   ```

### 2. 修改图片渲染样式

**示例：调整动态卡片的边距**

修改 `src/main/kotlin/top/bilibili/draw/Quality.kt`：

```kotlin
val cardMargin: Int = 20  // 修改边距大小
```

### 3. 添加新的 B站 API

**示例：添加评论API**

1. 在 `src/main/kotlin/top/bilibili/api/Comment.kt` 创建新文件：
   ```kotlin
   package top.bilibili.api

   suspend fun BiliClient.getComments(oid: String): CommentResponse? {
       // API 实现
   }
   ```

2. 在 `src/main/kotlin/top/bilibili/data/Comment.kt` 添加数据模型：
   ```kotlin
   @Serializable
   data class CommentResponse(
       val code: Int,
       val data: CommentData
   )
   ```

### 4. 调整任务执行频率

修改 `config/BiliConfig.yml`：

```yaml
checkConfig:
  interval: 30  # 从 15 秒改为 30 秒
```

重启 Bot 后生效。

### 5. 更新依赖版本

编辑 `build.gradle.kts`：

```kotlin
dependencies {
    // 更新 Ktor 版本
    implementation("io.ktor:ktor-client-okhttp:3.0.4")  // 3.0.3 → 3.0.4
}
```

**注意：** 更新依赖后务必测试是否兼容！

---

## 故障排查

### 编译失败

**症状：** `./gradlew build` 失败

**检查步骤：**
1. 检查 JDK 版本是否为 17
   ```bash
   java -version
   ```

2. 清理缓存重新构建
   ```bash
   ./gradlew clean build --refresh-dependencies
   ```

3. 检查依赖冲突
   ```bash
   ./gradlew dependencies
   ```

### Docker 构建失败

**症状：** `docker build` 失败

**检查步骤：**
1. JAR 文件是否存在
   ```bash
   ls build/libs/dynamic-bot-*.jar
   ```

2. Dockerfile 中的 JAR 文件名是否匹配

3. 网络连接是否正常

### 推送消息失败

**症状：** Bot 运行正常但消息不推送

**检查步骤：**
1. 检查 NapCat 连接
   ```bash
   docker logs dynamic-bot | grep "NapCat"
   ```

2. 检查配置文件中的联系人 ID 格式

3. 检查 NapCat 日志
   ```bash
   docker logs napcat
   ```

---

## 参考资源

- **B站 API 文档**：https://github.com/SocialSisterYi/bilibili-API-collect
- **OneBot v11 协议**：https://github.com/botuniverse/onebot-11
- **NapCat 文档**：https://napcat.netlify.app/
- **Kotlin 官方文档**：https://kotlinlang.org/docs/
- **Ktor 客户端文档**：https://ktor.io/docs/client.html

---

## 维护者联系方式

如有问题或建议，请通过以下方式联系：

- **GitHub Issues**：https://github.com/你的用户名/dynamic-bot/issues
- **Pull Requests**：欢迎贡献代码！

---

**最后更新：** 2026-01-09
**文档版本：** v1.3
