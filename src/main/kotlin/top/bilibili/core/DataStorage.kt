package top.bilibili.core

import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 简单 YAML 数据文件的通用读写基类。
 */
abstract class SimpleDataStorage(
    private val fileName: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val dataDir = File("data")
    private val dataFile = File(dataDir, "$fileName.yml")
    private val yaml = Yaml.default

    /**
     * 将当前内存数据序列化为字符串。
     */
    protected abstract fun serializeData(): String

    /**
     * 从磁盘内容反序列化并回填内存数据。
     */
    protected abstract fun deserializeData(content: String)

    /**
     * 从磁盘重新加载当前数据文件。
     */
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
            // 首次运行时立即落默认文件，避免后续保存路径分支反复判断。
            save()
        }
    }

    /**
     * 将当前内存数据保存到磁盘。
     */
    fun save() {
        try {
            val content = serializeData()
            dataFile.writeText(content)
            logger.debug("数据已保存: $fileName")
        } catch (e: Exception) {
            logger.error("保存数据失败: ${e.message}", e)
        }
    }

    /**
     * 自动保存入口，当前直接复用同步保存逻辑。
     */
    fun autoSave() {
        save()
    }
}

/**
 * 统一表示联系人类型与其原始 ID。
 */
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
        /**
         * 从字符串形式的联系人标识解析为结构化对象。
         */
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

        /**
         * 创建群聊联系人 ID。
         */
        fun group(groupId: Long) = ContactId("group", groupId)

        /**
         * 创建私聊联系人 ID。
         */
        fun private(userId: Long) = ContactId("private", userId)
    }
}

/**
 * 返回适合日志或提示文案使用的联系人名称。
 */
fun ContactId.name(): String {
    return when (type) {
        "group" -> "群 $id"
        "private" -> "用户 $id"
        else -> "未知 $id"
    }
}
