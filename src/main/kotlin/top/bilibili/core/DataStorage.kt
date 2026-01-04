package top.bilibili.core

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 简单的数据存储实现，替代 Mirai Console 的 AutoSavePluginData
 */
abstract class SimpleDataStorage(
    private val fileName: String
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val dataDir = File("data")
    private val dataFile = File(dataDir, "$fileName.yml")
    private val yaml = Yaml.default

    protected abstract fun serializeData(): String
    protected abstract fun deserializeData(content: String)

    /** 加载数据 */
    fun reload() {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }

        if (dataFile.exists()) {
            try {
                val content = dataFile.readText()
                deserializeData(content)
                logger.info("成功加载数据文件: $fileName")
            } catch (e: Exception) {
                logger.error("加载数据文件失败: ${e.message}", e)
            }
        } else {
            logger.info("数据文件不存在，使用默认数据: $fileName")
            save()
        }
    }

    /** 保存数据 */
    fun save() {
        try {
            val content = serializeData()
            dataFile.writeText(content)
            logger.debug("数据已保存: $fileName")
        } catch (e: Exception) {
            logger.error("保存数据失败: ${e.message}", e)
        }
    }

    /** 自动保存（定期调用） */
    fun autoSave() {
        save()
    }
}

/**
 * 联系人 ID（替代 Mirai Contact）
 * 格式: "g{群号}" 或 "u{QQ号}"
 */
data class ContactId(
    val type: String, // "group" 或 "private"
    val id: Long
) {
    override fun toString(): String = when (type) {
        "group" -> "g$id"
        "private" -> "u$id"
        else -> "unknown$id"
    }

    companion object {
        fun from(str: String): ContactId {
            return when {
                // 支持新格式：group:123456 或 private:123456
                str.startsWith("group:") -> ContactId("group", str.substring(6).toLong())
                str.startsWith("private:") -> ContactId("private", str.substring(8).toLong())
                // 支持旧格式：g123456 或 u123456
                str.startsWith("g") && str.length > 1 -> ContactId("group", str.substring(1).toLong())
                str.startsWith("u") && str.length > 1 -> ContactId("private", str.substring(1).toLong())
                else -> throw IllegalArgumentException("Invalid contact id: $str, expected format: 'group:123456' or 'g123456'")
            }
        }

        fun group(groupId: Long) = ContactId("group", groupId)
        fun private(userId: Long) = ContactId("private", userId)
    }
}

/**
 * 获取联系人名称
 */
fun ContactId.name(): String {
    return when (type) {
        "group" -> "群 $id"
        "private" -> "用户 $id"
        else -> "unknown $id"
    }
}
