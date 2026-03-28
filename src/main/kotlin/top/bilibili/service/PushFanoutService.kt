package top.bilibili.service

import top.bilibili.Bangumi
import top.bilibili.SubData
import top.bilibili.data.DynamicDetail
import top.bilibili.data.DynamicItem
import top.bilibili.data.DynamicType
import top.bilibili.data.LiveDetail
import top.bilibili.data.LiveInfo

/**
 * 负责把订阅联系人展开为具体推送明细，避免消息任务重复做扇出计算。
 */
object PushFanoutService {
    /**
     * 为动态消息生成按联系人拆分后的推送明细，便于后续按会话渲染。
     */
    fun dynamicDetailsForContacts(item: DynamicItem, contacts: Set<String>): List<DynamicDetail> {
        if (contacts.isEmpty()) return listOf(DynamicDetail(item))
        return contacts.sorted().map { contact -> DynamicDetail(item, contact) }
    }

    /**
     * 为直播消息生成按联系人拆分后的推送明细，保持动态与直播扇出规则一致。
     */
    fun liveDetailsForContacts(item: LiveInfo, contacts: Set<String>): List<LiveDetail> {
        if (contacts.isEmpty()) return listOf(LiveDetail(item))
        return contacts.sorted().map { contact -> LiveDetail(item, contact) }
    }

    /**
     * 解析动态消息最终命中的联系人集合，并在番剧场景下优先按 season 订阅查找。
     */
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

    /**
     * 解析直播消息最终命中的联系人集合，并统一扣除已禁推目标。
     */
    fun resolveLiveContacts(uid: Long, dynamic: Map<Long, SubData>): Set<String> {
        val subData = dynamic[uid] ?: return emptySet()
        return subData.contacts - subData.banList.keys
    }
}
