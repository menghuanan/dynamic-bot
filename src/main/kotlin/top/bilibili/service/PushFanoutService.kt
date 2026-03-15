package top.bilibili.service

import top.bilibili.Bangumi
import top.bilibili.SubData
import top.bilibili.data.DynamicDetail
import top.bilibili.data.DynamicItem
import top.bilibili.data.DynamicType
import top.bilibili.data.LiveDetail
import top.bilibili.data.LiveInfo

object PushFanoutService {
    fun dynamicDetailsForContacts(item: DynamicItem, contacts: Set<String>): List<DynamicDetail> {
        if (contacts.isEmpty()) return listOf(DynamicDetail(item))
        return contacts.sorted().map { contact -> DynamicDetail(item, contact) }
    }

    fun liveDetailsForContacts(item: LiveInfo, contacts: Set<String>): List<LiveDetail> {
        if (contacts.isEmpty()) return listOf(LiveDetail(item))
        return contacts.sorted().map { contact -> LiveDetail(item, contact) }
    }

    fun resolveDynamicContacts(
        item: DynamicItem,
        dynamic: Map<Long, SubData>,
        bangumi: Map<Long, Bangumi>,
    ): Set<String> {
        if (item.type == DynamicType.DYNAMIC_TYPE_PGC || item.type == DynamicType.DYNAMIC_TYPE_PGC_UNION) {
            val seasonId = item.modules.moduleDynamic.major?.pgc?.seasonId?.toLong()
            return if (seasonId != null) {
                bangumi[seasonId]?.contacts.orEmpty()
            } else {
                bangumi[item.modules.moduleAuthor.mid]?.contacts.orEmpty()
            }
        }

        val subData = dynamic[item.modules.moduleAuthor.mid] ?: return emptySet()
        return subData.contacts - subData.banList.keys
    }

    fun resolveLiveContacts(uid: Long, dynamic: Map<Long, SubData>): Set<String> {
        val subData = dynamic[uid] ?: return emptySet()
        return subData.contacts - subData.banList.keys
    }
}
