package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.core.BiliBiliBot
import top.bilibili.BiliData
import top.bilibili.client.BiliClient

internal val logger by BiliBiliBot::logger

private object ServiceClientLifecycle {
    @Volatile
    private var sharedClient: BiliClient? = null

    fun get(): BiliClient {
        sharedClient?.let { return it }
        return synchronized(this) {
            sharedClient ?: BiliClient().also { sharedClient = it }
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

fun closeServiceClient() {
    ServiceClientLifecycle.close()
}

val dynamic by BiliData::dynamic
val filter by BiliData::filter
val group by BiliData::group
val bangumi by BiliData::bangumi
val atAll by BiliData::atAll

fun isFollow(uid: Long, subject: String) =
    uid == 0L || (dynamic.containsKey(uid) && dynamic[uid]!!.contacts.contains(subject))

/**
 * NapCat 会话状态仓库，用于替代 Mirai whileSelectMessages 的基础能力。
 * key 通常使用 "subject:operator" 形式。
 */
object ConversationStateStore {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, MutableMap<String, String>>()

    suspend fun put(key: String, name: String, value: String) = mutex.withLock {
        states.getOrPut(key) { mutableMapOf() }[name] = value
    }

    suspend fun get(key: String, name: String): String? = mutex.withLock {
        states[key]?.get(name)
    }

    suspend fun remove(key: String) = mutex.withLock {
        states.remove(key)
    }

    suspend fun snapshot(key: String): Map<String, String> = mutex.withLock {
        states[key]?.toMap() ?: emptyMap()
    }
}
