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
    var dynamicTemplatePolicyByScope: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf(),
    var liveTemplatePolicyByScope: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf(),
    var liveCloseTemplatePolicyByScope: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf(),
    var dynamicColorByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf(),
    var atAll: MutableMap<String, MutableMap<Long, MutableSet<AtAllType>>> = mutableMapOf(),
    var group: MutableMap<String, Group> = mutableMapOf(),
    var bangumi: MutableMap<Long, Bangumi> = mutableMapOf(),
    var linkParseBlacklist: MutableSet<Long> = mutableSetOf(),
    var linkParseBlacklistContacts: MutableSet<String> = mutableSetOf(),
) {
    companion object {
        /**
         * 从 [BiliData] 单例创建可序列化的包装对象。
         */
        fun from(biliData: BiliData): BiliDataWrapper {
            return BiliDataWrapper(
                dataVersion = biliData.dataVersion,
                dynamic = biliData.dynamic,
                filter = biliData.filter,
                dynamicTemplatePolicyByScope = biliData.dynamicTemplatePolicyByScope,
                liveTemplatePolicyByScope = biliData.liveTemplatePolicyByScope,
                liveCloseTemplatePolicyByScope = biliData.liveCloseTemplatePolicyByScope,
                dynamicColorByUid = biliData.dynamicColorByUid,
                atAll = biliData.atAll,
                group = biliData.group,
                bangumi = biliData.bangumi,
                linkParseBlacklist = biliData.linkParseBlacklist,
                linkParseBlacklistContacts = biliData.linkParseBlacklistContacts,
            )
        }

        /**
         * 将包装对象中的数据回填到 [BiliData] 单例。
         */
        fun applyTo(wrapper: BiliDataWrapper, biliData: BiliData) {
            biliData.dataVersion = wrapper.dataVersion
            biliData.dynamic = wrapper.dynamic
            biliData.filter = wrapper.filter
            // 新版持久化结构不再保留旧模板绑定字段，加载当前 wrapper 时同步清空旧状态。
            biliData.dynamicPushTemplate = mutableMapOf()
            biliData.livePushTemplate = mutableMapOf()
            biliData.liveCloseTemplate = mutableMapOf()
            biliData.dynamicPushTemplateByUid = mutableMapOf()
            biliData.livePushTemplateByUid = mutableMapOf()
            biliData.liveCloseTemplateByUid = mutableMapOf()
            biliData.dynamicTemplatePolicyByScope = wrapper.dynamicTemplatePolicyByScope
            biliData.liveTemplatePolicyByScope = wrapper.liveTemplatePolicyByScope
            biliData.liveCloseTemplatePolicyByScope = wrapper.liveCloseTemplatePolicyByScope
            biliData.dynamicColorByUid = wrapper.dynamicColorByUid
            biliData.atAll = wrapper.atAll
            biliData.group = wrapper.group
            biliData.bangumi = wrapper.bangumi
            biliData.linkParseBlacklist = wrapper.linkParseBlacklist
            biliData.linkParseBlacklistContacts = wrapper.linkParseBlacklistContacts
        }
    }
}

/**
 * v3 及更早版本的数据兼容读取结构。
 * 仅用于基于 dataVersion 读取旧模板绑定字段，迁移完成后不会再写回该结构。
 */
@Serializable
data class LegacyBiliDataWrapperV3(
    var dataVersion: Int = 0,
    var dynamic: MutableMap<Long, SubData> = mutableMapOf(0L to SubData("ALL")),
    var filter: MutableMap<String, MutableMap<Long, DynamicFilter>> = mutableMapOf(),
    var dynamicPushTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    var livePushTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    var liveCloseTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    var dynamicPushTemplateByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf(),
    var livePushTemplateByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf(),
    var liveCloseTemplateByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf(),
    var dynamicTemplatePolicyByScope: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf(),
    var liveTemplatePolicyByScope: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf(),
    var liveCloseTemplatePolicyByScope: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf(),
    var dynamicColorByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf(),
    var atAll: MutableMap<String, MutableMap<Long, MutableSet<AtAllType>>> = mutableMapOf(),
    var group: MutableMap<String, Group> = mutableMapOf(),
    var bangumi: MutableMap<Long, Bangumi> = mutableMapOf(),
    var linkParseBlacklist: MutableSet<Long> = mutableSetOf(),
    var linkParseBlacklistContacts: MutableSet<String> = mutableSetOf(),
) {
    companion object {
        /**
         * 将旧版 wrapper 的全部字段回填到 [BiliData]。
         * 这里保留旧模板绑定字段，是为了给 dataVersion 迁移阶段提供完整输入。
         */
        fun applyTo(wrapper: LegacyBiliDataWrapperV3, biliData: BiliData) {
            biliData.dataVersion = wrapper.dataVersion
            biliData.dynamic = wrapper.dynamic
            biliData.filter = wrapper.filter
            biliData.dynamicPushTemplate = wrapper.dynamicPushTemplate
            biliData.livePushTemplate = wrapper.livePushTemplate
            biliData.liveCloseTemplate = wrapper.liveCloseTemplate
            biliData.dynamicPushTemplateByUid = wrapper.dynamicPushTemplateByUid
            biliData.livePushTemplateByUid = wrapper.livePushTemplateByUid
            biliData.liveCloseTemplateByUid = wrapper.liveCloseTemplateByUid
            biliData.dynamicTemplatePolicyByScope = wrapper.dynamicTemplatePolicyByScope
            biliData.liveTemplatePolicyByScope = wrapper.liveTemplatePolicyByScope
            biliData.liveCloseTemplatePolicyByScope = wrapper.liveCloseTemplatePolicyByScope
            biliData.dynamicColorByUid = wrapper.dynamicColorByUid
            biliData.atAll = wrapper.atAll
            biliData.group = wrapper.group
            biliData.bangumi = wrapper.bangumi
            biliData.linkParseBlacklist = wrapper.linkParseBlacklist
            biliData.linkParseBlacklistContacts = wrapper.linkParseBlacklistContacts
        }
    }
}
