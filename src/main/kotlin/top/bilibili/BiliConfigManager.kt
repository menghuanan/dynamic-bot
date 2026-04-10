package top.bilibili

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import top.bilibili.utils.normalizeContactSubject
import java.io.File
import java.nio.file.Paths

/**
 * 配置与持久化数据的统一加载、保存和迁移入口。
 */
object BiliConfigManager {
    private val logger = LoggerFactory.getLogger(BiliConfigManager::class.java)
    private const val CURRENT_DATA_VERSION = 3

    lateinit var config: BiliConfig
        private set

    lateinit var data: BiliData
        private set

    private val configDir = Paths.get("config").toFile()
    private val dataDir = Paths.get("data").toFile()

    private val configFile = File(configDir, "BiliConfig.yml")
    private val dataFile = File(configDir, "BiliData.yml")

    private val yaml = Yaml(
        configuration = Yaml.default.configuration.copy(
            strictMode = false,
        ),
    )

    /**
     * 初始化配置目录、配置对象和持久化数据。
     */
    fun init() {
        configDir.mkdirs()
        dataDir.mkdirs()

        config = loadConfig()
        logger.info("配置加载完成")

        data = loadData()
        logger.info("数据加载完成")
    }

    /**
     * 从磁盘加载主配置；文件不存在时创建默认配置。
     */
    private fun loadConfig(): BiliConfig {
        return try {
            if (configFile.exists()) {
                val content = configFile.readText()
                yaml.decodeFromString<BiliConfig>(content)
            } else {
                logger.info("配置文件不存在，创建默认配置")
                val defaultConfig = BiliConfig()
                saveConfig(defaultConfig)
                defaultConfig
            }
        } catch (e: Exception) {
            logger.error("加载配置文件失败，使用默认配置", e)
            BiliConfig()
        }
    }

    /**
     * 从磁盘加载业务数据，并在必要时执行旧版本迁移。
     */
    private fun loadData(): BiliData {
        return try {
            val oldDataFile = File(dataDir, "BiliData.yml")
            if (!dataFile.exists() && oldDataFile.exists()) {
                // 先复制旧位置数据，是为了在升级路径时尽量保住已有订阅状态。
                logger.info("检测到旧数据文件，正在迁移到新位置")
                oldDataFile.copyTo(dataFile, overwrite = false)
            }

            if (!dataFile.exists()) {
                logger.info("数据文件不存在，创建默认数据")
                BiliData.dataVersion = CURRENT_DATA_VERSION
                saveData(BiliData)
                return BiliData
            }

            val content = dataFile.readText()
            if (content.isBlank() || content.trim() == "{}") {
                logger.warn("数据文件为空，使用默认数据")
                BiliData.dataVersion = CURRENT_DATA_VERSION
                return BiliData
            }

            val loadedWrapper = yaml.decodeFromString<BiliDataWrapper>(content)
            BiliDataWrapper.applyTo(loadedWrapper, BiliData)

            val migrated = migrateDataIfNeeded(BiliData)
            if (migrated) {
                logger.info("检测到旧版数据结构，已完成迁移并准备写回")
                saveData(BiliData)
            }

            logger.info(
                "数据加载完成：{} 个订阅，{} 个分组",
                BiliData.dynamic.size,
                BiliData.group.size,
            )
            BiliData
        } catch (e: Exception) {
            logger.error("加载数据文件失败，使用默认数据", e)
            BiliData
        }
    }

    /**
     * 根据数据版本执行兼容迁移，并在内容变更时提升版本号。
     */
    private fun migrateDataIfNeeded(data: BiliData): Boolean {
        var changed = false

        if (data.dataVersion < 2) {
            data.dynamic.values.forEach { sub ->
                if (sub.sourceRefs.isEmpty() && sub.contacts.isNotEmpty()) {
                    sub.contacts.forEach { contact ->
                        // 补齐 direct 引用，是为了让旧订阅数据接入新的来源追踪模型。
                        sub.sourceRefs.add("direct:$contact")
                    }
                    changed = true
                }
            }
        }

        changed = migrateLegacyContactSubjects(data) || changed
        changed = migrateLegacyTemplatePolicies(data) || changed

        data.dynamic.values.forEach { sub ->
            if (sub.sourceRefs.isEmpty() && sub.contacts.isNotEmpty()) {
                sub.contacts.forEach { contact ->
                    sub.sourceRefs.add("direct:$contact")
                }
                changed = true
            }
        }

        if (changed || data.dataVersion < CURRENT_DATA_VERSION) {
            data.dataVersion = CURRENT_DATA_VERSION
        }

        return changed
    }

    /**
     * 将旧模板绑定回填为按 scope 存储的新模板策略。
     * 旧会话级模板先回填联系人 scope，再由旧 UID 单模板绑定覆盖同一 scope 的默认结果。
     */
    private fun migrateLegacyTemplatePolicies(data: BiliData): Boolean {
        var changed = false
        changed = migrateLegacyTemplatePolicyMap(
            legacyTemplateBindings = data.dynamicPushTemplate,
            legacyTemplateBindingsByUid = data.dynamicPushTemplateByUid,
            targetPolicies = data.dynamicTemplatePolicyByScope,
            subscriptions = data.dynamic,
        ) || changed
        changed = migrateLegacyTemplatePolicyMap(
            legacyTemplateBindings = data.livePushTemplate,
            legacyTemplateBindingsByUid = data.livePushTemplateByUid,
            targetPolicies = data.liveTemplatePolicyByScope,
            subscriptions = data.dynamic,
        ) || changed
        changed = migrateLegacyTemplatePolicyMap(
            legacyTemplateBindings = data.liveCloseTemplate,
            legacyTemplateBindingsByUid = data.liveCloseTemplateByUid,
            targetPolicies = data.liveCloseTemplatePolicyByScope,
            subscriptions = data.dynamic,
        ) || changed
        return changed
    }

    /**
     * 将历史联系人 subject 迁移为当前统一格式。
     */
    private fun migrateLegacyContactSubjects(data: BiliData): Boolean {
        var changed = false

        data.dynamic.values.forEach { sub ->
            changed = migrateStringSet(sub.contacts) || changed
            changed = migrateSourceRefSet(sub.sourceRefs) || changed
        }

        migrateNestedMap(data.filter).also {
            data.filter = it.value
            changed = it.changed || changed
        }
        migrateTemplateBindings(data.dynamicPushTemplate).also {
            data.dynamicPushTemplate = it.value
            changed = it.changed || changed
        }
        migrateTemplateBindings(data.livePushTemplate).also {
            data.livePushTemplate = it.value
            changed = it.changed || changed
        }
        migrateTemplateBindings(data.liveCloseTemplate).also {
            data.liveCloseTemplate = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.dynamicPushTemplateByUid).also {
            data.dynamicPushTemplateByUid = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.livePushTemplateByUid).also {
            data.livePushTemplateByUid = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.liveCloseTemplateByUid).also {
            data.liveCloseTemplateByUid = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.dynamicColorByUid).also {
            data.dynamicColorByUid = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.atAll).also {
            data.atAll = it.value
            changed = it.changed || changed
        }

        data.group.values.forEach { group ->
            changed = migrateStringSet(group.contacts) || changed
            changed = migrateStringSet(group.adminContacts) || changed
            if (group.creatorContact.isBlank() && group.creator > 0L) {
                // 旧数据只存数字 QQ 号时补全 subject，是为了后续统一按平台联系人处理权限。
                group.creatorContact = "onebot11:private:${group.creator}"
                changed = true
            } else {
                val normalizedCreator = normalizeContactSubject(group.creatorContact)
                if (normalizedCreator != null && normalizedCreator != group.creatorContact) {
                    group.creatorContact = normalizedCreator
                    changed = true
                }
            }
            if (group.adminContacts.isEmpty() && group.admin.isNotEmpty()) {
                group.admin.forEach { adminId ->
                    // 这里回填管理员联系人集合，是为了兼容旧版只存数字管理员 ID 的数据结构。
                    group.adminContacts.add("onebot11:private:$adminId")
                }
                changed = true
            }
        }
        data.bangumi.values.forEach { bangumi ->
            changed = migrateStringSet(bangumi.contacts) || changed
        }
        if (data.linkParseBlacklist.isNotEmpty()) {
            data.linkParseBlacklist.forEach { userId ->
                // 黑名单迁移到联系人格式后，后续才能跨平台复用同一套拦截逻辑。
                data.linkParseBlacklistContacts.add("onebot11:private:$userId")
            }
            data.linkParseBlacklist.clear()
            changed = true
        }
        changed = migrateStringSet(data.linkParseBlacklistContacts) || changed

        return changed
    }

    /**
     * 迁移纯字符串集合中的联系人 subject。
     */
    private fun migrateStringSet(values: MutableSet<String>): Boolean {
        val migrated = linkedSetOf<String>()
        var changed = false
        values.forEach { value ->
            val normalized = normalizeContactSubject(value) ?: value
            if (normalized != value) {
                changed = true
            }
            migrated.add(normalized)
        }
        if (migrated != values) {
            values.clear()
            values.addAll(migrated)
            changed = true
        }
        return changed
    }

    /**
     * 将旧模板绑定映射迁移为新的联系人 scope 策略。
     * 这里仅在目标策略缺失时回填，避免迁移覆盖已经存在的新结构配置。
     */
    private fun migrateLegacyTemplatePolicyMap(
        legacyTemplateBindings: MutableMap<String, MutableSet<String>>,
        legacyTemplateBindingsByUid: MutableMap<String, MutableMap<Long, String>>,
        targetPolicies: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
        subscriptions: MutableMap<Long, SubData>,
    ): Boolean {
        var changed = false
        val existingPolicyKeys = targetPolicies.flatMap { (scope, policies) ->
            policies.keys.map { uid -> scope to uid }
        }.toSet()

        legacyTemplateBindings.forEach { (templateName, contacts) ->
            contacts.forEach { subject ->
                val scope = "contact:$subject"
                subscriptions.forEach { (uid, subData) ->
                    if (subject in subData.contacts) {
                        changed = upsertLegacyTemplatePolicy(
                            targetPolicies = targetPolicies,
                            scope = scope,
                            uid = uid,
                            templateName = templateName,
                            overwriteExisting = false,
                        ) || changed
                    }
                }
            }
        }

        legacyTemplateBindingsByUid.forEach { (subject, bindings) ->
            val scope = "contact:$subject"
            bindings.forEach { (uid, templateName) ->
                changed = upsertLegacyTemplatePolicy(
                    targetPolicies = targetPolicies,
                    scope = scope,
                    uid = uid,
                    templateName = templateName,
                    overwriteExisting = (scope to uid) !in existingPolicyKeys,
                ) || changed
            }
        }

        return changed
    }

    /**
     * 将单个旧模板名写入新策略。
     * 迁移出的策略始终是单模板且关闭随机，保持旧行为的可预测性。
     */
    private fun upsertLegacyTemplatePolicy(
        targetPolicies: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
        scope: String,
        uid: Long,
        templateName: String,
        overwriteExisting: Boolean,
    ): Boolean {
        val scopePolicies = targetPolicies.getOrPut(scope) { mutableMapOf() }
        val existingPolicy = scopePolicies[uid]
        if (existingPolicy != null && !overwriteExisting) {
            return false
        }

        val nextPolicy = TemplatePolicy(
            templates = mutableListOf(templateName),
            randomEnabled = false,
        )
        if (existingPolicy == nextPolicy) {
            return false
        }

        scopePolicies[uid] = nextPolicy
        return true
    }

    /**
     * 迁移带 `direct:` 前缀的订阅来源引用集合。
     */
    private fun migrateSourceRefSet(values: MutableSet<String>): Boolean {
        val migrated = linkedSetOf<String>()
        var changed = false
        values.forEach { value ->
            val normalized = if (value.startsWith("direct:")) {
                val subject = value.removePrefix("direct:")
                val normalizedSubject = normalizeContactSubject(subject)
                if (normalizedSubject != null) {
                    "direct:$normalizedSubject"
                } else {
                    value
                }
            } else {
                value
            }
            if (normalized != value) {
                changed = true
            }
            migrated.add(normalized)
        }
        if (migrated != values) {
            values.clear()
            values.addAll(migrated)
            changed = true
        }
        return changed
    }

    /**
     * 迁移模板到联系人集合的绑定映射。
     */
    private fun migrateTemplateBindings(
        source: MutableMap<String, MutableSet<String>>,
    ): MigrationResult<MutableMap<String, MutableSet<String>>> {
        var changed = false
        val result = linkedMapOf<String, MutableSet<String>>()
        source.forEach { (template, contacts) ->
            val migrated = linkedSetOf<String>()
            contacts.forEach { contact ->
                val normalized = normalizeContactSubject(contact) ?: contact
                if (normalized != contact) {
                    changed = true
                }
                migrated.add(normalized)
            }
            result[template] = migrated
            if (migrated != contacts) {
                changed = true
            }
        }
        return MigrationResult(if (changed) result.toMutableMap() else source, changed)
    }

    /**
     * 迁移以联系人 subject 为键的嵌套映射。
     */
    private fun <T> migrateNestedMap(
        source: MutableMap<String, T>,
    ): MigrationResult<MutableMap<String, T>> {
        var changed = false
        val result = linkedMapOf<String, T>()
        source.forEach { (subject, value) ->
            val normalized = normalizeContactSubject(subject) ?: subject
            if (normalized != subject) {
                changed = true
            }
            val existing = result[normalized]
            result[normalized] = mergeNestedValues(existing, value)
        }
        return MigrationResult(
            value = if (changed || result.size != source.size) result.toMutableMap() else source,
            changed = changed || result.size != source.size,
        )
    }

    /**
     * 在归一化键冲突时合并旧值与新值，尽量避免迁移过程中覆盖原有配置。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> mergeNestedValues(existing: T?, incoming: T): T {
        if (existing is MutableMap<*, *> && incoming is MutableMap<*, *>) {
            val merged = linkedMapOf<Any?, Any?>()
            // 新值后写入，是为了让显式归一化后的条目优先覆盖旧键映射结果。
            merged.putAll(existing as MutableMap<Any?, Any?>)
            merged.putAll(incoming as MutableMap<Any?, Any?>)
            return merged.toMutableMap() as T
        }
        return incoming
    }

    /**
     * 将当前配置写回配置文件。
     */
    fun saveConfig(configToSave: BiliConfig = config) {
        try {
            configFile.writeText(yaml.encodeToString(configToSave))
            logger.debug("配置已保存")
        } catch (e: Exception) {
            logger.error("保存配置文件失败", e)
        }
    }

    /**
     * 将当前业务数据写回数据文件，并对空写入结果做保护检查。
     */
    fun saveData(dataToSave: BiliData = BiliData): Boolean {
        return try {
            val wrapper = BiliDataWrapper.from(dataToSave)
            val yamlContent = yaml.encodeToString(wrapper)
            dataFile.writeText(yamlContent)

            val savedContent = dataFile.readText()
            if (savedContent.trim() == "{}") {
                logger.error("警告：保存的数据文件为空！")
                false
            } else {
                logger.info("数据已保存到 {}", dataFile.absolutePath)
                true
            }
        } catch (e: Exception) {
            logger.error("保存数据文件失败", e)
            false
        }
    }

    /**
     * 同时保存配置与业务数据。
     */
    fun saveAll() {
        saveConfig()
        saveData()
    }

    /**
     * 重新加载主配置。
     */
    fun reloadConfig() {
        config = loadConfig()
        logger.info("配置已重新加载")
    }

    /**
     * 重新加载业务数据。
     */
    fun reloadData() {
        data = loadData()
        logger.info("数据已重新加载")
    }

    /**
     * 重新加载配置与业务数据。
     */
    fun reloadAll() {
        reloadConfig()
        reloadData()
    }

    /**
     * 迁移操作的统一返回结果。
     */
    private data class MigrationResult<T>(
        val value: T,
        val changed: Boolean,
    )
}
