package top.bilibili.utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Path


val allNullMode = true
val noteValue = false
private val jsonLogger = LoggerFactory.getLogger("Json2DataClass")

/**
 * 从远程 JSON 拉取数据并生成 Kotlin Data Class 文件。
 */
suspend fun json2DataClassFile(url: String, baseClassName: String, path: Path) {
    val data = json2DataClass(url, baseClassName)
    withContext(Dispatchers.IO) {
        val file = path.resolve("$baseClassName.kt").toFile()
        file.createNewFile()
        file.appendText("import kotlinx.serialization.SerialName\n")
        file.appendText("import kotlinx.serialization.Serializable\n\n")
        file.appendText(data)
    }
}

/**
 * 从远程 JSON 拉取数据并生成 Kotlin Data Class 源码字符串。
 */
suspend fun json2DataClass(url: String, baseClassName: String): String {
    val client = HttpClient(OkHttp)
    var retryCount = 0
    val maxRetries = 1

    try {
    while (true) {
        try {
            val resStr = client.get(url).body<String>()
            val resJson = json.parseToJsonElement(resStr)
            return resJson.jsonObject.decodeJsonObject(baseClassName)
        } catch (e: Exception) {
            if (retryCount >= maxRetries) {
                jsonLogger.error("请求最终失败，无法生成 Data Class: ${e.message}", e)
                throw e
            }
            retryCount++
            jsonLogger.warn("请求失败，3秒后重试 (第$retryCount 次): ${e.message}")
            kotlinx.coroutines.delay(3000)
        }
    }
    } finally {
        runCatching { client.close() }
            .onFailure { jsonLogger.warn("Json2DataClass 关闭客户端失败: ${it.message}", it) }
    }
}


/**
 * 递归解析 JSON 对象并生成对应的 Kotlin Data Class 定义。
 */
private fun JsonObject.decodeJsonObject(objName: String): String {
    var obj = ""
    // 统一为空安全模式，是为了让生成结果更适合直接对接字段不稳定的外部接口。
    val plus = if (allNullMode) "? = null," else ","
    return buildString {
        appendLine("@Serializable")
        appendLine("data class $objName(")

        entries.forEach {
            val key = it.key.replace(" ", "_")
            if (noteValue && it.value is JsonPrimitive) {
                appendLine("    // ${it.value}")
            }
            appendLine("    @SerialName(\"$key\")")
            try {
                when (it.value) {
                    is JsonPrimitive -> {
                        val attr = it.value.jsonPrimitive.parse()
                        appendLine("    val ${snakeToCamelLowerFirst(key)}: $attr$plus")
                    }
                    is JsonObject -> {
                        val objKey = snakeToCamel(key)
                        appendLine("    val ${snakeToCamelLowerFirst(key)}: $objKey$plus")
                        obj += "\n" + it.value.jsonObject.decodeJsonObject(objKey)
                    }
                    is JsonArray -> {
                        val arr = it.value.jsonArray.first()
                        val attr = if (arr is JsonPrimitive) {
                            arr.jsonPrimitive.parse()
                        } else {
                            val k = snakeToCamel(key)
                            obj += "\n" + arr.jsonObject.decodeJsonObject(k)
                            k
                        }
                        appendLine("    val ${snakeToCamelLowerFirst(key)}: List<$attr>$plus")
                    }
                    is JsonNull -> {
                        appendLine("    val ${snakeToCamelLowerFirst(key)}: JsonElement? = null,")
                    }
                }
            } catch (e: Exception) {
                jsonLogger.warn("Json2DataClass 解析失败: ${it.key} - ${e.message}", e)
            }
        }
        append(")")
        if (obj != "") {
            append("{")
            appendLine(obj.replace("\n", "\n    "))
            append("}")
        }
    }
}

/**
 * 根据 JSON 原始值推断最合适的 Kotlin 基础类型。
 */
private fun JsonPrimitive.parse() =
    if (intOrNull != null) "Int"
    else if (longOrNull != null) "Long"
    else if (booleanOrNull != null) "Boolean"
    else if (floatOrNull != null) "Float"
    else if (isString) "String"
    else "String"

/**
 * 将下划线命名转换为首字母大写的驼峰命名。
 */
private fun snakeToCamel(name: String) =
    name.split("_").joinToString("") { s -> s.replaceRange(0, 1, s.first().uppercase()) }

/**
 * 将下划线命名转换为首字母小写的驼峰命名。
 */
private fun snakeToCamelLowerFirst(name: String): String {
    val k = snakeToCamel(name)
    return k.replaceRange(0, 1, k.first().lowercase())
}

