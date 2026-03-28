package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.DynamicFilter
import top.bilibili.DynamicFilterType
import top.bilibili.FilterMode
import top.bilibili.FilterType
import top.bilibili.utils.findEquivalentSubjectKey
import top.bilibili.utils.normalizeContactSubject

/**
 * 集中维护订阅过滤器存储，避免命令层直接处理筛选结构细节。
 */
object FilterService {
    private val mutex = Mutex()

    private fun cleanupEmptyFilter(subject: String, uid: Long) {
        val bySubject = filter[subject] ?: return
        val dynamicFilter = bySubject[uid] ?: return
        val isEmpty = dynamicFilter.typeSelect.list.isEmpty() && dynamicFilter.regularSelect.list.isEmpty()
        if (!isEmpty) return

        bySubject.remove(uid)
        if (bySubject.isEmpty()) {
            filter.remove(subject)
        }
    }

    /**
     * 在写入过滤器前统一校验目标订阅是否存在，避免留下无效规则。
     */
    suspend fun addFilter(type: FilterType, mode: FilterMode?, regex: String?, uid: Long, subject: String) =
        mutex.withLock {
            val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock "联系人格式错误: $subject"
            if (!isFollow(uid, normalizedSubject)) return@withLock "还未订阅此人哦"

            if (!filter.containsKey(normalizedSubject)) filter[normalizedSubject] = mutableMapOf()
            if (!filter[normalizedSubject]!!.containsKey(uid)) filter[normalizedSubject]!![uid] = DynamicFilter()

            val dynamicFilter = filter[normalizedSubject]!![uid]!!
            when (type) {
                FilterType.TYPE -> {
                    if (mode != null) dynamicFilter.typeSelect.mode = mode
                    if (!regex.isNullOrEmpty()) {
                        val mapped = when (regex) {
                            "动态" -> DynamicFilterType.DYNAMIC
                            "转发动态" -> DynamicFilterType.FORWARD
                            "视频" -> DynamicFilterType.VIDEO
                            "音乐" -> DynamicFilterType.MUSIC
                            "专栏" -> DynamicFilterType.ARTICLE
                            "直播" -> DynamicFilterType.LIVE
                            else -> return@withLock "没有这个类型 $regex"
                        }
                        dynamicFilter.typeSelect.list.add(mapped)
                    }
                }
                FilterType.REGULAR -> {
                    if (mode != null) dynamicFilter.regularSelect.mode = mode
                    if (!regex.isNullOrEmpty()) dynamicFilter.regularSelect.list.add(regex)
                }
            }
            "设置成功"
        }

    /**
     * 读取并格式化指定目标的过滤器列表，顺带回收已经空掉的历史节点。
     */
    suspend fun listFilter(uid: Long, subject: String) = mutex.withLock {
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        if (!isFollow(uid, normalizedSubject)) return@withLock "还未订阅此人哦"
        val storedSubject = findEquivalentSubjectKey(filter.keys, normalizedSubject) ?: normalizedSubject
        if (!(filter.containsKey(storedSubject) && filter[storedSubject]!!.containsKey(uid))) return@withLock "当前目标没有过滤器"

        val dynamicFilter = filter[storedSubject]!![uid]!!
        if (dynamicFilter.typeSelect.list.isEmpty() && dynamicFilter.regularSelect.list.isEmpty()) {
            cleanupEmptyFilter(storedSubject, uid)
            return@withLock "当前目标没有过滤器"
        }

        val text = buildString {
            val typeSelect = dynamicFilter.typeSelect
            if (typeSelect.list.isNotEmpty()) {
                append("动态类型过滤器: ")
                appendLine(typeSelect.mode.value)
                typeSelect.list.forEachIndexed { index, type -> appendLine("  t$index: ${type.value}") }
                appendLine()
            }
            val regularSelect = dynamicFilter.regularSelect
            if (regularSelect.list.isNotEmpty()) {
                append("正则过滤器: ")
                appendLine(regularSelect.mode.value)
                regularSelect.list.forEachIndexed { index, reg -> appendLine("  r$index: $reg") }
                appendLine()
            }
        }
        if (text.isBlank()) "当前目标没有过滤器" else text
    }

    /**
     * 删除指定索引的过滤规则，并在规则集为空时同步清理空容器。
     */
    suspend fun delFilter(index: String, uid: Long, subject: String) = mutex.withLock {
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        if (!isFollow(uid, normalizedSubject)) return@withLock "还未订阅此人哦"
        val storedSubject = findEquivalentSubjectKey(filter.keys, normalizedSubject) ?: normalizedSubject
        if (!(filter.containsKey(storedSubject) && filter[storedSubject]!!.containsKey(uid))) return@withLock "当前目标没有过滤器"

        val i = index.substring(1).toIntOrNull() ?: return@withLock "索引错误"
        if (i < 0) return@withLock "索引超出范围"

        val selectedList = when (index.firstOrNull()) {
            't' -> filter[storedSubject]!![uid]!!.typeSelect.list
            'r' -> filter[storedSubject]!![uid]!!.regularSelect.list
            else -> return@withLock "索引类型错误"
        }
        if (selectedList.size <= i) return@withLock "索引超出范围"

        val removed = selectedList[i]
        selectedList.removeAt(i)
        cleanupEmptyFilter(storedSubject, uid)

        if (removed is DynamicFilterType) {
            "已删除 ${removed.value} 类型过滤"
        } else {
            "已删除 ${removed as String} 正则过滤"
        }
    }
}
