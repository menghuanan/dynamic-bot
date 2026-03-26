package top.bilibili.core

import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.io.File

abstract class SimpleDataStorage(
    private val fileName: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val dataDir = File("data")
    private val dataFile = File(dataDir, "$fileName.yml")
    private val yaml = Yaml.default

    protected abstract fun serializeData(): String

    protected abstract fun deserializeData(content: String)

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

    fun save() {
        try {
            val content = serializeData()
            dataFile.writeText(content)
            logger.debug("数据已保存: $fileName")
        } catch (e: Exception) {
            logger.error("保存数据失败: ${e.message}", e)
        }
    }

    fun autoSave() {
        save()
    }
}

data class ContactId(
    val type: String,
    val id: Long,
) {
    override fun toString(): String = when (type) {
        "group" -> "onebot11:group:$id"
        "private" -> "onebot11:private:$id"
        else -> "未知$id"
    }

    companion object {
        fun from(str: String): ContactId {
            return when {
                str.startsWith("onebot11:group:") -> ContactId("group", str.substring("onebot11:group:".length).toLong())
                str.startsWith("onebot11:private:") -> ContactId("private", str.substring("onebot11:private:".length).toLong())
                str.startsWith("group:") -> ContactId("group", str.substring("group:".length).toLong())
                str.startsWith("private:") -> ContactId("private", str.substring("private:".length).toLong())
                str.startsWith("g") && str.length > 1 -> ContactId("group", str.substring(1).toLong())
                str.startsWith("u") && str.length > 1 -> ContactId("private", str.substring(1).toLong())
                else -> throw IllegalArgumentException("无效的联系人 ID: $str")
            }
        }

        fun group(groupId: Long) = ContactId("group", groupId)

        fun private(userId: Long) = ContactId("private", userId)
    }
}

fun ContactId.name(): String {
    return when (type) {
        "group" -> "群 $id"
        "private" -> "用户 $id"
        else -> "未知 $id"
    }
}
