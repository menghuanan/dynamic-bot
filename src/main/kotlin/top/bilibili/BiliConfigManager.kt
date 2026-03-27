package top.bilibili

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import top.bilibili.utils.normalizeContactSubject
import java.io.File
import java.nio.file.Paths

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

    fun init() {
        configDir.mkdirs()
        dataDir.mkdirs()

        config = loadConfig()
        logger.info("配置加载完成")

        data = loadData()
        logger.info("数据加载完成")
    }

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

    private fun loadData(): BiliData {
        return try {
            val oldDataFile = File(dataDir, "BiliData.yml")
            if (!dataFile.exists() && oldDataFile.exists()) {
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

    private fun migrateDataIfNeeded(data: BiliData): Boolean {
        var changed = false

        if (data.dataVersion < 2) {
            data.dynamic.values.forEach { sub ->
                if (sub.sourceRefs.isEmpty() && sub.contacts.isNotEmpty()) {
                    sub.contacts.forEach { contact ->
                        sub.sourceRefs.add("direct:$contact")
                    }
                    changed = true
                }
            }
        }

        changed = migrateLegacyContactSubjects(data) || changed

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
                data.linkParseBlacklistContacts.add("onebot11:private:$userId")
            }
            data.linkParseBlacklist.clear()
            changed = true
        }
        changed = migrateStringSet(data.linkParseBlacklistContacts) || changed

        return changed
    }

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

    @Suppress("UNCHECKED_CAST")
    private fun <T> mergeNestedValues(existing: T?, incoming: T): T {
        if (existing is MutableMap<*, *> && incoming is MutableMap<*, *>) {
            val merged = linkedMapOf<Any?, Any?>()
            merged.putAll(existing as MutableMap<Any?, Any?>)
            merged.putAll(incoming as MutableMap<Any?, Any?>)
            return merged.toMutableMap() as T
        }
        return incoming
    }

    fun saveConfig(configToSave: BiliConfig = config) {
        try {
            configFile.writeText(yaml.encodeToString(configToSave))
            logger.debug("配置已保存")
        } catch (e: Exception) {
            logger.error("保存配置文件失败", e)
        }
    }

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

    fun saveAll() {
        saveConfig()
        saveData()
    }

    fun reloadConfig() {
        config = loadConfig()
        logger.info("配置已重新加载")
    }

    fun reloadData() {
        data = loadData()
        logger.info("数据已重新加载")
    }

    fun reloadAll() {
        reloadConfig()
        reloadData()
    }

    private data class MigrationResult<T>(
        val value: T,
        val changed: Boolean,
    )
}
