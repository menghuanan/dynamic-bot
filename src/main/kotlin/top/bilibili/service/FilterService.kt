package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.DynamicFilter
import top.bilibili.DynamicFilterType
import top.bilibili.FilterMode
import top.bilibili.FilterType

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

    suspend fun addFilter(type: FilterType, mode: FilterMode?, regex: String?, uid: Long, subject: String) =
        mutex.withLock {
            if (!isFollow(uid, subject)) return@withLock "还未订阅此人哦"

            if (!filter.containsKey(subject)) filter[subject] = mutableMapOf()
            if (!filter[subject]!!.containsKey(uid)) filter[subject]!![uid] = DynamicFilter()

            val dynamicFilter = filter[subject]!![uid]!!
            when (type) {
                FilterType.TYPE -> {
                    if (mode != null) dynamicFilter.typeSelect.mode = mode
                    if (regex != null && regex != "") {
                        val t = when (regex) {
                            "动态" -> DynamicFilterType.DYNAMIC
                            "转发动态" -> DynamicFilterType.FORWARD
                            "视频" -> DynamicFilterType.VIDEO
                            "音乐" -> DynamicFilterType.MUSIC
                            "专栏" -> DynamicFilterType.ARTICLE
                            "直播" -> DynamicFilterType.LIVE
                            else -> return@withLock "没有这个类型 $regex"
                        }
                        dynamicFilter.typeSelect.list.add(t)
                    }
                }
                FilterType.REGULAR -> {
                    if (mode != null) dynamicFilter.regularSelect.mode = mode
                    if (regex != null && regex != "") dynamicFilter.regularSelect.list.add(regex)
                }
            }
            "设置成功"
        }

    suspend fun listFilter(uid: Long, subject: String) = mutex.withLock {
        if (!isFollow(uid, subject)) return@withLock "还未订阅此人哦"

        if (!(filter.containsKey(subject) && filter[subject]!!.containsKey(uid))) return@withLock "目标没有过滤器"

        val dynamicFilter = filter[subject]!![uid]!!
        if (dynamicFilter.typeSelect.list.isEmpty() && dynamicFilter.regularSelect.list.isEmpty()) {
            cleanupEmptyFilter(subject, uid)
            return@withLock "当前目标没有过滤器"
        }

        val text = buildString {
            //appendLine("当前目标过滤器: ")
            //appendLine()
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

    suspend fun delFilter(index: String, uid: Long, subject: String) = mutex.withLock {
        if (!isFollow(uid, subject)) return@withLock "还未订阅此人哦"
        if (!(filter.containsKey(subject) && filter[subject]!!.containsKey(uid))) return@withLock "当前目标没有过滤器"

        var i = 0
        runCatching {
            i = index.substring(1).toInt()
        }.onFailure {
            return@withLock "索引错误"
        }
        if (i < 0) return@withLock "索引超出范围"
        var flag = false
        val selectedList = if (index[0] == 't') {
            flag = true
            filter[subject]!![uid]!!.typeSelect.list
        } else if (index[0] == 'r') {
            filter[subject]!![uid]!!.regularSelect.list
        } else return@withLock "索引类型错误"
        if (selectedList.size <= i) return@withLock "索引超出范围"
        val t = selectedList[i]
        selectedList.removeAt(i)
        cleanupEmptyFilter(subject, uid)

        if (flag) "已删除 ${(t as DynamicFilterType).value} 类型过滤"
        else "已删除 ${(t as String)} 正则过滤"
    }
}
