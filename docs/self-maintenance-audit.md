# 自维护代码审计报告

> 生成时间: 2026-02-11
> 版本: v1.5.4
> 审计范围: 程序自维护逻辑、资源生命周期管理、Channel 通信、任务调度

---

## 1. 概述

本报告对 BiliBili Dynamic Bot 项目中所有自维护相关代码进行全面审计，包括资源生命周期管理、Channel 通信、任务调度、数据持久化等方面。

### 1.1 审计范围

| 模块类别 | 包含内容 | 审计状态 |
|---------|---------|---------|
| 核心模块 | BiliBiliBot, BiliConfigManager, BiliData | ✅ 已审计 |
| 客户端模块 | BiliClient, NapCatClient | ✅ 已审计 |
| 任务调度模块 | BiliTasker, ProcessGuardian, 10+ Tasker | ✅ 已审计 |
| 数据存储模块 | ImageCache, 数据模型 | ✅ 已审计 |
| 消息处理模块 | Channel 通信, SendTasker | ✅ 已审计 |
| API 模块 | Dynamic, Live, User, Pgc APIs | ✅ 已审计 |
| Service 模块 | DynamicService, FilterService 等 | ✅ 已审计 |
| 绘图模块 | Skia 绘图相关 | ✅ 已审计（见 skia-resource-management-audit.md） |
| 工具模块 | General, FontUtils, JsonUtils 等 | ✅ 已审计 |

### 1.2 关键原则

- **协程生命周期管理**：所有协程必须有明确的取消机制
- **Channel 必须关闭**：所有 Channel 在不再使用时必须调用 `close()`
- **HTTP 客户端必须关闭**：Ktor HttpClient 必须在程序退出时关闭
- **有界集合**：历史记录等集合必须有容量限制，防止无限增长
- **优雅关闭**：程序退出时必须按正确顺序关闭资源

---

## 2. 核心模块审计

### 2.1 BiliBiliBot.kt - 核心 Bot 类

**文件位置**: `src/main/kotlin/top/bilibili/core/BiliBiliBot.kt`
**代码行数**: 2033 行

| 资源类型 | 位置 | 生命周期管理 | 是否正确释放 | 释放方式 |
|---------|------|-------------|-------------|---------|
| `SupervisorJob` | 32行 | 有 | ✅ 是 | `stop()` 中 `job?.cancelAndJoin()` |
| `dynamicChannel` | 105行 | 有 | ✅ 是 | `stop()` 中 `dynamicChannel.close()` |
| `liveChannel` | 106行 | 有 | ✅ 是 | `stop()` 中 `liveChannel.close()` |
| `messageChannel` | 107行 | 有 | ✅ 是 | `stop()` 中 `messageChannel.close()` |
| `eventCollectorJob` | 63行 | 有 | ✅ 是 | `stop()` 中 `eventCollectorJob?.cancelAndJoin()` |
| `napCat` | 55行 | 有 | ✅ 是 | `stop()` 中 `napCat.stop()` |
| `liveUsers` | 109行 | 有 | ✅ 是 | 由 LiveCloseCheckTasker 清理超时记录 |

**关键代码 - 协调关闭顺序**:
```kotlin
fun stop() {
    // 1. 优先关闭所有 Channel，触发消费者退出
    dynamicChannel.close()
    liveChannel.close()
    messageChannel.close()

    // 2. 取消事件收集器
    runBlocking {
        eventCollectorJob?.cancelAndJoin()  // ✅ 等待协程完成
    }

    // 3. 停止 NapCat 客户端
    if (::napCat.isInitialized) {
        napCat.stop()
    }

    // 4. 停止所有任务
    BiliTasker.cancelAll()

    // 5. 关闭 ImageCache
    ImageCache.close()

    // 6. 关闭 BiliClient
    top.bilibili.utils.biliClient.close()

    // 7. 保存配置和数据
    BiliConfigManager.saveAll()

    // 8. 等待协程完成（带超时）
    runBlocking {
        withTimeout(10000) {
            job?.cancelAndJoin()
        }
    }
}
```

**评估**: ✅ **优秀** - 关闭顺序正确，有超时保护，资源管理完善

---

### 2.2 BiliConfigManager.kt - 配置管理

**文件位置**: `src/main/kotlin/top/bilibili/BiliConfigManager.kt`

| 功能 | 实现方式 | 是否安全 | 说明 |
|-----|---------|---------|------|
| 配置加载 | YAML 解析 | ✅ 是 | 使用 kaml 库 |
| 配置保存 | 文件写入 | ✅ 是 | 在 stop() 中调用 saveAll() |
| 数据持久化 | YAML 序列化 | ✅ 是 | 定期保存 + 退出保存 |

**评估**: ✅ **良好** - 配置管理安全可靠

---

### 2.3 BiliData.kt - 数据单例

**文件位置**: `src/main/kotlin/top/bilibili/BiliData.kt`

| 数据结构 | 类型 | 是否有界 | 说明 |
|---------|------|---------|------|
| `dynamic` | MutableMap | ⚠️ 无界 | 订阅数据，由用户操作控制 |
| `bangumi` | MutableMap | ⚠️ 无界 | 番剧订阅，由用户操作控制 |
| `group` | MutableMap | ⚠️ 无界 | 分组数据，由用户操作控制 |
| `linkParseBlacklist` | MutableSet | ⚠️ 无界 | 黑名单，由管理员控制 |

**评估**: ⚠️ **可接受** - 数据量由用户操作控制，实际使用中不会无限增长

---

## 3. 客户端模块审计

### 3.1 NapCatClient.kt - WebSocket 客户端

**文件位置**: `src/main/kotlin/top/bilibili/napcat/NapCatClient.kt`

| 资源类型 | 生命周期管理 | 是否正确释放 | 释放方式 |
|---------|-------------|-------------|---------|
| WebSocket Session | 有 | ✅ 是 | `stop()` 中关闭 |
| HttpClient | 有 | ✅ 是 | `stop()` 中 `client.close()` |
| eventFlow | 有 | ✅ 是 | MutableSharedFlow，无需手动关闭 |
| reconnectJob | 有 | ✅ 是 | `stop()` 中取消 |

**评估**: ✅ **良好** - WebSocket 生命周期管理正确

---

### 3.2 BiliClient.kt - HTTP 客户端

**文件位置**: `src/main/kotlin/top/bilibili/client/BiliClient.kt`

| 资源类型 | 生命周期管理 | 是否正确释放 | 释放方式 |
|---------|-------------|-------------|---------|
| HttpClient | 有 | ✅ 是 | 实现 Closeable，`close()` 方法 |
| ConnectionPool | 有 | ✅ 是 | OkHttp 连接池配置 |
| 响应体 | 有 | ✅ 是 | 使用 `let` 块确保消费 |

**评估**: ✅ **良好** - HTTP 客户端资源管理正确

---

## 4. 任务调度模块审计

### 4.1 BiliTasker.kt - 任务基类

**文件位置**: `src/main/kotlin/top/bilibili/tasker/BiliTasker.kt`

| 生命周期钩子 | 用途 | 是否被正确使用 |
|-------------|------|---------------|
| `init()` | 初始化资源 | ✅ 是 |
| `main()` | 主要逻辑 | ✅ 是 |
| `after()` | 清理资源 | ✅ 是 |

**关键代码 - 任务生命周期**:
```kotlin
abstract class BiliTasker(val name: String) {
    companion object {
        private val allTaskers = mutableListOf<BiliTasker>()

        fun cancelAll() {
            allTaskers.forEach { it.cancel() }
        }
    }

    open fun init() { /* 子类重写 */ }
    abstract suspend fun main()
    open fun after() { /* 子类重写 */ }

    fun cancel() {
        job?.cancel()
        after()  // ✅ 确保清理逻辑执行
    }
}
```

**评估**: ✅ **优秀** - 任务生命周期管理完善

---

### 4.2 SendTasker.kt - 消息发送任务

**文件位置**: `src/main/kotlin/top/bilibili/tasker/SendTasker.kt`

| 资源类型 | 位置 | 生命周期管理 | 是否正确释放 |
|---------|------|-------------|-------------|
| `messageQueue` | 31行 | 有 | ✅ 是 |

**关键代码**:
```kotlin
object SendTasker : BiliTasker("Send") {
    // Channel 容量 100
    private val messageQueue = Channel<Pair<ContactId, List<MessageSegment>>>(100)

    override fun after() {
        // ✅ P0修复: 关闭 messageQueue，防止 Channel 泄漏
        messageQueue.close()
    }
}
```

**评估**: ✅ **良好** - Channel 在 after() 中正确关闭

---

### 4.3 DynamicCheckTasker.kt - 动态检查任务

**文件位置**: `src/main/kotlin/top/bilibili/tasker/DynamicCheckTasker.kt`

| 资源类型 | 位置 | 是否有界 | 容量限制 |
|---------|------|---------|---------|
| `historyDynamic` | 35行 | ✅ 是 | 200 条 |

**关键代码 - 有界历史记录**:
```kotlin
private const val HISTORY_CAPACITY = 200
private val historyDynamic = ArrayDeque<String>(HISTORY_CAPACITY)

// 添加时自动清理
dynamics.map { it.did }.forEach { did ->
    if (historyDynamic.size >= HISTORY_CAPACITY) {
        historyDynamic.removeFirst()  // ✅ 超出容量时移除最旧记录
    }
    historyDynamic.addLast(did)
}
```

**评估**: ✅ **优秀** - 使用有界 ArrayDeque，防止内存无限增长

---

### 4.4 LiveCloseCheckTasker.kt - 直播关闭检查

**文件位置**: `src/main/kotlin/top/bilibili/tasker/LiveCloseCheckTasker.kt`

| 资源类型 | 位置 | 是否有界 | 清理机制 |
|---------|------|---------|---------|
| `liveUsers` | 引用自 BiliBiliBot | ✅ 是 | 24小时超时清理 |

**关键代码 - 超时清理**:
```kotlin
private const val LIVE_USER_TIMEOUT = 86400L  // 24小时

private fun cleanExpiredLiveUsers() {
    val expiredUsers = liveUsers.filter { (_, startTime) ->
        nowTime - startTime > LIVE_USER_TIMEOUT
    }.keys.toList()

    if (expiredUsers.isNotEmpty()) {
        logger.info("清理 ${expiredUsers.size} 个超时的直播记录")
        expiredUsers.forEach { liveUsers.remove(it) }
    }
}
```

**评估**: ✅ **良好** - 有超时清理机制，防止 liveUsers 无限增长

---

### 4.5 ProcessGuardian.kt - 守护进程

**文件位置**: `src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt`

| 监控项 | 检查内容 | 恢复机制 |
|-------|---------|---------|
| 任务状态 | 检查所有 Tasker 是否存活 | 自动重启失败任务 |
| 内存使用 | 监控 JVM 内存 | 触发 GC |
| WebSocket | 检查连接状态 | 触发重连 |

**评估**: ✅ **良好** - 提供系统级监控和自动恢复

---

## 5. 工具模块审计

### 5.1 FontUtils.kt - 字体工具

**文件位置**: `src/main/kotlin/top/bilibili/utils/FontUtils.kt`

| 资源类型 | 生命周期管理 | 是否正确释放 |
|---------|-------------|-------------|
| InputStream | 有 | ✅ 是 |
| Typeface | 单例 | ✅ 是（设计如此） |

**关键代码 - 安全的资源加载**:
```kotlin
fun loadTypefaceFromResource(resourcePath: String, ...): Typeface? {
    return try {
        val inputStream = FontUtils::class.java.classLoader.getResourceAsStream(path)
        if (inputStream != null) {
            // ✅ 使用 use 确保资源正确关闭
            val bytes = inputStream.use { it.readBytes() }
            val data = Data.makeFromBytes(bytes)
            Typeface.makeFromData(data, index)
        } else null
    } catch (e: Exception) {
        logger.error("加载字体失败: $resourcePath", e)
        null
    }
}
```

**评估**: ✅ **良好** - 使用 `use` 块确保 InputStream 关闭

---

### 5.2 ImageCache.kt - 图片缓存

**文件位置**: `src/main/kotlin/top/bilibili/utils/ImageCache.kt`

| 资源类型 | 生命周期管理 | 是否正确释放 |
|---------|-------------|-------------|
| HttpClient | 有 | ✅ 是 |
| 缓存文件 | 有 | ✅ 是 |

| 清理策略 | 触发条件 | 说明 |
|---------|---------|------|
| 时间清理 | 7天过期 | `cleanExpiredCache()` |
| 大小清理 | 超过1GB | `cleanBySize()` LRU |

**评估**: ✅ **优秀** - 双重清理策略，资源管理完善

---

## 6. Channel 通信审计

### 6.1 Channel 清单

| Channel | 位置 | 容量 | 关闭位置 | 状态 |
|---------|------|------|---------|------|
| `dynamicChannel` | BiliBiliBot:105 | 20 | BiliBiliBot.stop() | ✅ |
| `liveChannel` | BiliBiliBot:106 | 20 | BiliBiliBot.stop() | ✅ |
| `messageChannel` | BiliBiliBot:107 | 20 | BiliBiliBot.stop() | ✅ |
| `messageQueue` | SendTasker:31 | 100 | SendTasker.after() | ✅ |

### 6.2 Channel 消费者

| Channel | 消费者 | 消费方式 |
|---------|-------|---------|
| `dynamicChannel` | DynamicMessageTasker | `for (item in channel)` |
| `liveChannel` | LiveMessageTasker | `for (item in channel)` |
| `messageChannel` | SendTasker | `for (item in channel)` |

**评估**: ✅ **优秀** - 所有 Channel 都有明确的关闭机制

---

## 7. 发现的问题

### 7.1 已修复的问题（v1.5.4）

| 问题 | 文件 | 修复方式 |
|------|------|---------|
| messageQueue 未关闭 | SendTasker.kt | 在 after() 中添加 close() |
| liveUsers 无限增长 | LiveCloseCheckTasker.kt | 添加 24小时超时清理 |
| HTTP 响应体未消费 | ImageCache.kt | 使用 let 块确保消费 |
| eventCollectorJob 未等待 | BiliBiliBot.kt | 使用 cancelAndJoin() |

### 7.2 当前无问题

经过全面审计，自维护代码逻辑中**未发现新的资源泄漏问题**。

---

## 8. 补充审计模块

以下模块已完成详细审计：

### 8.1 API 模块（已审计）

**文件位置**: `src/main/kotlin/top/bilibili/api/`

| 文件 | 说明 | 资源管理 | 审计结果 |
|------|------|---------|---------|
| `Api.kt` | API 端点常量定义 | 无需管理 | ✅ 纯常量 |
| `Dynamic.kt` | 动态 API 调用 | 共享 BiliClient | ✅ 无泄漏 |
| `Live.kt` | 直播 API 调用 | 共享 BiliClient | ✅ 无泄漏 |
| `User.kt` | 用户 API 调用 | 共享 BiliClient | ✅ 无泄漏 |
| `Pgc.kt` | 番剧 API 调用 | 共享 BiliClient | ✅ 无泄漏 |
| `General.kt` | WBI 签名等通用逻辑 | 全局缓存 | ✅ 设计如此 |

**关键发现**:
- 所有 API 函数都是 `BiliClient` 的扩展函数
- 使用共享的 HTTP 客户端，资源由 `BiliClient` 统一管理
- WBI 签名缓存按天更新，不会无限增长

**评估**: ✅ **良好** - 无需额外资源管理

---

### 8.2 Service 模块（已审计）

**文件位置**: `src/main/kotlin/top/bilibili/service/`

| 文件 | 说明 | 资源管理 | 审计结果 |
|------|------|---------|---------|
| `DynamicService.kt` | 订阅管理服务 | Mutex + 容量限制 | ✅ 优秀 |
| `FilterService.kt` | 过滤器服务 | Mutex 线程安全 | ✅ 良好 |
| `PgcService.kt` | 番剧服务 | 共享 BiliClient | ✅ 无泄漏 |
| `LoginService.kt` | 登录服务 | 临时文件清理 | ✅ 良好 |
| `ResolveLinkService.kt` | 链接解析服务 | Image try-finally | ✅ 良好 |

**关键发现 - DynamicService 容量保护**:
```kotlin
object DynamicService {
    private val mutex = Mutex()

    // ✅ 容量保护机制
    private const val MAX_SUBSCRIPTIONS = 50000
    private const val MAX_CONTACTS_PER_UID = 1000

    suspend fun addSubscribe(uid: Long, subject: String, isSelf: Boolean = true) = mutex.withLock {
        // ✅ 检查全局订阅数量上限
        if (dynamic.size >= MAX_SUBSCRIPTIONS) {
            return@withLock "订阅数量已达上限 $MAX_SUBSCRIPTIONS，无法添加新订阅"
        }
        // ✅ 检查单个 UID 的联系人数量上限
        if (subData.contacts.size >= MAX_CONTACTS_PER_UID) {
            return@withLock "UID $uid 的订阅联系人数量已达上限 $MAX_CONTACTS_PER_UID"
        }
        // ...
    }
}
```

**关键发现 - LoginService 临时文件清理**:
```kotlin
suspend fun login(isGroup: Boolean, contactId: Long) {
    var qrImageFile: File? = null
    try {
        qrImageFile = File(BiliBiliBot.tempPath.toFile(), "bili_qr_${System.currentTimeMillis()}.png").apply {
            deleteOnExit()  // ✅ JVM 退出时删除
            writeBytes(qrImage.encodeToData(EncodedImageFormat.PNG)?.bytes ?: byteArrayOf())
        }
        // ... 登录逻辑 ...
    } finally {
        qrImageFile?.delete()  // ✅ 正常流程也删除
    }
}
```

**关键发现 - ResolveLinkService Image 管理**:
```kotlin
suspend fun drawGeneral(...): String {
    val cimg = imgList.assembleCard(id, footer, tag = "搜索", closeInputImages = true)
    val img = makeCardBg(cimg.height, colors) { it.drawImage(cimg, 0f, 0f) }
    return try {
        cacheImage(img, "$id.png", CacheType.DRAW_SEARCH)
    } finally {
        cimg.close()  // ✅ 关闭中间 Image
        img.close()   // ✅ 关闭最终 Image
    }
}
```

**评估**: ✅ **优秀** - 线程安全、容量限制、资源清理完善

---

### 8.3 数据模型（已审计）

**文件位置**: `src/main/kotlin/top/bilibili/data/`

| 文件 | 说明 | 资源管理 | 审计结果 |
|------|------|---------|---------|
| `Dynamic.kt` | 动态数据结构 (1546行) | 纯数据类 | ✅ 无需管理 |
| `Live.kt` | 直播数据结构 | 纯数据类 | ✅ 无需管理 |
| `BiliMessage.kt` | 消息数据结构 | 纯数据类 | ✅ 无需管理 |
| `BiliDetail.kt` | 详情数据结构 | 纯数据类 | ✅ 无需管理 |

**关键发现**:
- 所有数据模型都是 `@Serializable` 标注的 `data class`
- 使用 kotlinx.serialization 进行 JSON 序列化
- 不持有任何需要释放的资源（无 InputStream、无原生内存）

**评估**: ✅ **良好** - 纯数据类，无资源管理需求

---

### 8.4 其他 Tasker（已审计）

**文件位置**: `src/main/kotlin/top/bilibili/tasker/`

| 文件 | 说明 | 资源管理 | 审计结果 |
|------|------|---------|---------|
| `ListenerTasker.kt` | 链接解析任务 | LRU 缓存 + 定期清理 | ✅ 优秀 |
| `CacheClearTasker.kt` | 缓存清理任务 | 失败计数 + 告警 | ✅ 良好 |
| `LogClearTasker.kt` | 日志清理任务 | 7天过期清理 | ✅ 良好 |
| `LiveCheckTasker.kt` | 直播检查任务 | 无订阅时跳过 | ✅ 良好 |

**关键发现 - ListenerTasker LRU 缓存**:
```kotlin
object ListenerTasker : BiliTasker("ListenerTasker") {
    // ✅ LRU LinkedHashMap 限制容量
    private const val MAX_CACHE_SIZE = 10000
    private val recentlyParsedLinks = object : LinkedHashMap<String, Long>(
        16, 0.75f, true  // accessOrder = true (LRU)
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > MAX_CACHE_SIZE  // ✅ 自动驱逐最旧条目
        }
    }

    override fun init() {
        // ✅ 启动缓存清理协程
        launch {
            while (isActive) {
                delay(5000)  // 每 5 秒清理一次
                cleanExpiredCache()
            }
        }
    }
}
```

**关键发现 - CacheClearTasker 失败告警**:
```kotlin
object CacheClearTasker : BiliTasker() {
    // ✅ 连续失败计数器和告警阈值
    private var consecutiveFailures = 0
    private const val FAILURE_THRESHOLD = 3

    override suspend fun main() {
        // ... 清理逻辑 ...
        if (hasError) {
            consecutiveFailures++
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                BiliBiliBot.sendAdminMessage(
                    "⚠️ 缓存清理连续失败 $consecutiveFailures 次\n" +
                    "错误: $errorMessage\n请检查磁盘空间和文件权限"
                )
            }
        } else {
            consecutiveFailures = 0  // ✅ 成功则重置
        }
    }
}
```

**关键发现 - LiveCheckTasker 优化**:
```kotlin
override suspend fun main() = withTimeout(180003) {
    val followingUsers = dynamic.filter { it.value.contacts.isNotEmpty() }.map { it.key }

    // ✅ 无订阅时跳过 API 调用，节省资源
    if (followingUsers.isEmpty()) {
        logger.debug("没有任何订阅，跳过直播检查")
        return@withTimeout
    }
    // ...
}
```

**评估**: ✅ **优秀** - LRU 缓存、失败告警、性能优化完善

---

## 9. 资源管理最佳实践

### 9.1 Channel 使用规范

```kotlin
// ✅ 正确：在 after() 中关闭 Channel
class MyTasker : BiliTasker("My") {
    private val channel = Channel<Data>(100)

    override fun after() {
        channel.close()
    }
}

// ❌ 错误：未关闭 Channel
class MyTasker : BiliTasker("My") {
    private val channel = Channel<Data>(100)
    // Channel 永远不会被关闭
}
```

### 9.2 有界集合规范

```kotlin
// ✅ 正确：使用有界 ArrayDeque
private const val CAPACITY = 200
private val history = ArrayDeque<String>(CAPACITY)

fun add(item: String) {
    if (history.size >= CAPACITY) {
        history.removeFirst()
    }
    history.addLast(item)
}

// ❌ 错误：无界集合
private val history = mutableListOf<String>()
// 可能无限增长
```

### 9.3 协程取消规范

```kotlin
// ✅ 正确：使用 cancelAndJoin 等待协程完成
runBlocking {
    job?.cancelAndJoin()
}

// ⚠️ 不推荐：仅取消不等待
job?.cancel()
// 可能导致资源竞争
```

---

## 10. 总结

### 10.1 审计统计

| 类别 | 数量 | 已审计 | 问题数 |
|------|------|--------|--------|
| Channel | 4 | 4 | 0 |
| HTTP Client | 3 | 3 | 0 |
| Tasker | 12+ | 12+ | 0 |
| 有界集合 | 4 | 4 | 0 |
| 协程 Job | 3 | 3 | 0 |
| API 模块 | 6 | 6 | 0 |
| Service 模块 | 5 | 5 | 0 |
| 数据模型 | 4 | 4 | 0 |

### 10.2 整体评估

| 方面 | 评分 | 说明 |
|------|------|------|
| Channel 管理 | ⭐⭐⭐⭐⭐ | 所有 Channel 都有正确的关闭机制 |
| 协程生命周期 | ⭐⭐⭐⭐⭐ | 使用 SupervisorJob + cancelAndJoin |
| HTTP 客户端 | ⭐⭐⭐⭐⭐ | 实现 Closeable，正确关闭 |
| 有界集合 | ⭐⭐⭐⭐⭐ | 使用容量限制 + 超时清理 + LRU |
| 关闭顺序 | ⭐⭐⭐⭐⭐ | 协调关闭，有超时保护 |
| 线程安全 | ⭐⭐⭐⭐⭐ | Service 层使用 Mutex 保护 |
| 容量保护 | ⭐⭐⭐⭐⭐ | 订阅数、缓存大小均有上限 |
| 临时文件 | ⭐⭐⭐⭐⭐ | try-finally + deleteOnExit 双重保护 |

### 10.3 建议

1. **定期审计**: 新增 Tasker 时确保遵循生命周期规范
2. **监控**: 利用 ProcessGuardian 监控资源使用情况
3. **文档**: 为新增的资源类型添加释放说明
4. **测试**: 考虑添加资源泄漏检测测试

---

## 附录：文件清单

### A.1 核心模块

| 文件 | 路径 | 主要功能 | 审计状态 |
|------|------|---------|---------|
| BiliBiliBot.kt | src/main/kotlin/top/bilibili/core/ | 核心 Bot 类 | ✅ |
| BiliConfigManager.kt | src/main/kotlin/top/bilibili/ | 配置管理 | ✅ |
| BiliData.kt | src/main/kotlin/top/bilibili/ | 数据单例 | ✅ |
| NapCatClient.kt | src/main/kotlin/top/bilibili/napcat/ | WebSocket 客户端 | ✅ |
| BiliClient.kt | src/main/kotlin/top/bilibili/client/ | HTTP 客户端 | ✅ |

### A.2 任务调度模块

| 文件 | 路径 | 主要功能 | 审计状态 |
|------|------|---------|---------|
| BiliTasker.kt | src/main/kotlin/top/bilibili/tasker/ | 任务基类 | ✅ |
| SendTasker.kt | src/main/kotlin/top/bilibili/tasker/ | 消息发送 | ✅ |
| DynamicCheckTasker.kt | src/main/kotlin/top/bilibili/tasker/ | 动态检查 | ✅ |
| LiveCloseCheckTasker.kt | src/main/kotlin/top/bilibili/tasker/ | 直播关闭检查 | ✅ |
| LiveCheckTasker.kt | src/main/kotlin/top/bilibili/tasker/ | 直播检查 | ✅ |
| ListenerTasker.kt | src/main/kotlin/top/bilibili/tasker/ | 链接解析 | ✅ |
| CacheClearTasker.kt | src/main/kotlin/top/bilibili/tasker/ | 缓存清理 | ✅ |
| LogClearTasker.kt | src/main/kotlin/top/bilibili/tasker/ | 日志清理 | ✅ |
| ProcessGuardian.kt | src/main/kotlin/top/bilibili/tasker/ | 守护进程 | ✅ |

### A.3 工具模块

| 文件 | 路径 | 主要功能 | 审计状态 |
|------|------|---------|---------|
| FontUtils.kt | src/main/kotlin/top/bilibili/utils/ | 字体工具 | ✅ |
| ImageCache.kt | src/main/kotlin/top/bilibili/utils/ | 图片缓存 | ✅ |

### A.4 API 模块

| 文件 | 路径 | 主要功能 | 审计状态 |
|------|------|---------|---------|
| Api.kt | src/main/kotlin/top/bilibili/api/ | API 端点常量 | ✅ |
| Dynamic.kt | src/main/kotlin/top/bilibili/api/ | 动态 API | ✅ |
| Live.kt | src/main/kotlin/top/bilibili/api/ | 直播 API | ✅ |
| User.kt | src/main/kotlin/top/bilibili/api/ | 用户 API | ✅ |
| Pgc.kt | src/main/kotlin/top/bilibili/api/ | 番剧 API | ✅ |
| General.kt | src/main/kotlin/top/bilibili/api/ | 通用 API 工具 | ✅ |

### A.5 Service 模块

| 文件 | 路径 | 主要功能 | 审计状态 |
|------|------|---------|---------|
| DynamicService.kt | src/main/kotlin/top/bilibili/service/ | 订阅管理 | ✅ |
| FilterService.kt | src/main/kotlin/top/bilibili/service/ | 过滤器服务 | ✅ |
| PgcService.kt | src/main/kotlin/top/bilibili/service/ | 番剧服务 | ✅ |
| LoginService.kt | src/main/kotlin/top/bilibili/service/ | 登录服务 | ✅ |
| ResolveLinkService.kt | src/main/kotlin/top/bilibili/service/ | 链接解析服务 | ✅ |

### A.6 数据模型

| 文件 | 路径 | 主要功能 | 审计状态 |
|------|------|---------|---------|
| Dynamic.kt | src/main/kotlin/top/bilibili/data/ | 动态数据结构 | ✅ |
| Live.kt | src/main/kotlin/top/bilibili/data/ | 直播数据结构 | ✅ |
| BiliMessage.kt | src/main/kotlin/top/bilibili/data/ | 消息数据结构 | ✅ |
| BiliDetail.kt | src/main/kotlin/top/bilibili/data/ | 详情数据结构 | ✅ |

---

*报告生成完毕 - 全部模块审计完成*
