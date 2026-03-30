package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.core.BiliBiliBot
import top.bilibili.BiliData
import top.bilibili.client.BiliClient
import top.bilibili.utils.subjectsEquivalent

internal val logger by BiliBiliBot::logger

private object ServiceClientLifecycle {
    @Volatile
    private var sharedClient: BiliClient? = null

    fun get(): BiliClient {
        sharedClient?.let { return it }
        return synchronized(this) {
            // 服务层共享客户端使用固定 owner 标签，便于守护日志区分轮询链路和命令服务链路。
            sharedClient ?: BiliClient("service.shared").also { sharedClient = it }
        }
    }

    fun close() {
        val toClose = synchronized(this) {
            val current = sharedClient
            sharedClient = null
            current
        }
        runCatching { toClose?.close() }
            .onFailure { logger.warn("关闭 service 共享 BiliClient 失败: ${it.message}", it) }
    }
}

val client: BiliClient
    get() = ServiceClientLifecycle.get()

/**
 * 主动关闭共享的 BiliClient，避免停机或重载后继续复用旧连接。
 */
fun closeServiceClient() {
    ServiceClientLifecycle.close()
}

val dynamic by BiliData::dynamic
val filter by BiliData::filter
val group by BiliData::group
val bangumi by BiliData::bangumi
val atAll by BiliData::atAll

/**
 * 统一判断指定 UID 是否已经覆盖目标会话，避免多处重复遍历订阅联系人。
 */
fun isFollow(uid: Long, subject: String) =
    uid == 0L || (dynamic.containsKey(uid) && dynamic[uid]!!.contacts.any { subjectsEquivalent(it, subject) })

/**
 * NapCat 会话状态仓库，用于替代 Mirai whileSelectMessages 的基础能力。
 * key 通常使用 "subject:operator" 形式。
 */
object ConversationStateStore {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, MutableMap<String, String>>()

    /**
     * 为会话流程保存命名状态值，避免多轮交互临时数据散落在调用栈外部。
     */
    suspend fun put(key: String, name: String, value: String) = mutex.withLock {
        states.getOrPut(key) { mutableMapOf() }[name] = value
    }

    /**
     * 读取指定会话状态值，让交互流程在异步回调间保持上下文连续。
     */
    suspend fun get(key: String, name: String): String? = mutex.withLock {
        states[key]?.get(name)
    }

    /**
     * 在流程结束后清空整组状态，避免旧会话残留影响下一次交互。
     */
    suspend fun remove(key: String) = mutex.withLock {
        states.remove(key)
    }

    /**
     * 返回当前会话状态快照，便于调试或一次性消费多个键值。
     */
    suspend fun snapshot(key: String): Map<String, String> = mutex.withLock {
        states[key]?.toMap() ?: emptyMap()
    }
}
