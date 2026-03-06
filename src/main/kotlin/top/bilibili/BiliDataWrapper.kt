package top.bilibili

import kotlinx.serialization.Serializable

/**
 * BiliData 包装类，用于序列化
 * 因为 kotlinx.serialization 无法正确序列化 object 的 var 字段
 */
@Serializable
data class BiliDataWrapper(
    var dataVersion: Int = 0,
    var dynamic: MutableMap<Long, SubData> = mutableMapOf(0L to SubData("ALL")),
    var filter: MutableMap<String, MutableMap<Long, DynamicFilter>> = mutableMapOf(),
    var dynamicPushTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    var livePushTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    var liveCloseTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    var dynamicPushTemplateByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf(),
    var livePushTemplateByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf(),
    var liveCloseTemplateByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf(),
    var atAll: MutableMap<String, MutableMap<Long, MutableSet<AtAllType>>> = mutableMapOf(),
    var group: MutableMap<String, Group> = mutableMapOf(),
    var bangumi: MutableMap<Long, Bangumi> = mutableMapOf(),
    var linkParseBlacklist: MutableSet<Long> = mutableSetOf()
) {
    companion object {
        /** 从 BiliData object 创建包装类 */
        fun from(biliData: BiliData): BiliDataWrapper {
            return BiliDataWrapper(
                dataVersion = biliData.dataVersion,
                dynamic = biliData.dynamic,
                filter = biliData.filter,
                dynamicPushTemplate = biliData.dynamicPushTemplate,
                livePushTemplate = biliData.livePushTemplate,
                liveCloseTemplate = biliData.liveCloseTemplate,
                dynamicPushTemplateByUid = biliData.dynamicPushTemplateByUid,
                livePushTemplateByUid = biliData.livePushTemplateByUid,
                liveCloseTemplateByUid = biliData.liveCloseTemplateByUid,
                atAll = biliData.atAll,
                group = biliData.group,
                bangumi = biliData.bangumi,
                linkParseBlacklist = biliData.linkParseBlacklist
            )
        }

        /** 应用到 BiliData object */
        fun applyTo(wrapper: BiliDataWrapper, biliData: BiliData) {
            biliData.dataVersion = wrapper.dataVersion
            biliData.dynamic = wrapper.dynamic
            biliData.filter = wrapper.filter
            biliData.dynamicPushTemplate = wrapper.dynamicPushTemplate
            biliData.livePushTemplate = wrapper.livePushTemplate
            biliData.liveCloseTemplate = wrapper.liveCloseTemplate
            biliData.dynamicPushTemplateByUid = wrapper.dynamicPushTemplateByUid
            biliData.livePushTemplateByUid = wrapper.livePushTemplateByUid
            biliData.liveCloseTemplateByUid = wrapper.liveCloseTemplateByUid
            biliData.atAll = wrapper.atAll
            biliData.group = wrapper.group
            biliData.bangumi = wrapper.bangumi
            biliData.linkParseBlacklist = wrapper.linkParseBlacklist
        }
    }
}
