package top.bilibili.utils

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import top.bilibili.core.BiliBiliBot
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists
import kotlin.io.path.writeText

val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
    allowStructuredMapKeys = true
}

/**
 * 将 JSON 字符串解析为目标类型。
 */
inline fun <reified T> String.decode(): T = json.parseToJsonElement(this).decode()

/**
 * 将 [JsonElement] 解析为目标类型，并在失败时落盘上下文供排查。
 */
inline fun <reified T> JsonElement.decode(): T {
    return try {
        json.decodeFromJsonElement(this)
    }catch (e: SerializationException) {
        val time = (System.currentTimeMillis() / 1000).formatTime("yyyy-MM-dd")

        val md5 = e.message?.md5()
        val fileName = "$time-$md5.json"

        BiliBiliBot.dataFolderPath.resolve("exception").apply {
            if (notExists()) createDirectories()
        }.resolve(fileName).apply {
            if (notExists()) {
                // 只在首次失败时写入现场，是为了保留原始上下文并避免重复异常刷盘。
                writeText(e.stackTraceToString())
                appendText("\n\n\n")
                appendText(json.encodeToString(JsonElement.serializer(), this@decode))
            }
        }

        BiliBiliBot.logger.error("json解析失败，请把 /data/exception/ 目录下的 $fileName 文件反馈给开发者\n${e.message}")
        throw e
    }
}
