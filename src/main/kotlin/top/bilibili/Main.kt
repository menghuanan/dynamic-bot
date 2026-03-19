package top.bilibili

import org.slf4j.LoggerFactory
import top.bilibili.core.BiliBiliBot
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("Main")

internal fun currentVersionLabel(
    systemVersion: String? = System.getProperty("app.version"),
    implementationVersion: String? = BiliConfigManager::class.java.`package`?.implementationVersion,
): String {
    val resolvedVersion = sequenceOf(systemVersion, implementationVersion)
        .firstOrNull { !it.isNullOrBlank() }

    return resolvedVersion?.let(::normalizeVersionLabel) ?: "unknown"
}

private fun normalizeVersionLabel(version: String): String {
    val trimmedVersion = version.trim()
    return if (trimmedVersion.startsWith("v")) trimmedVersion else "v$trimmedVersion"
}

fun main(args: Array<String>) {
    SkikoInitializer.initialize()

    try {
        var enableDebug: Boolean? = null
        var showHelp = false

        for (arg in args) {
            when (arg.lowercase()) {
                "--debug", "-d" -> enableDebug = true
                "--help", "-h" -> showHelp = true
            }
        }

        if (showHelp) {
            println(
                """
                BiliBili 动态推送 Bot ${currentVersionLabel()}

                用法: java -jar dynamic-bot.jar [选项]

                选项:
                  --debug, -d    启用 Debug 日志模式
                  --help, -h     显示帮助信息

                示例:
                  java -jar dynamic-bot.jar
                  java -jar dynamic-bot.jar --debug
                """.trimIndent(),
            )
            exitProcess(0)
        }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("收到停止信号，正在关闭...")
                try {
                    BiliBiliBot.stop()
                    logger.info("Bot 已正常停止")
                } catch (e: Exception) {
                    logger.error("关闭过程中发生错误: ${e.message}", e)
                }
            },
        )

        BiliBiliBot.start(enableDebug)
        Thread.currentThread().join()
    } catch (_: InterruptedException) {
        logger.info("程序被中断")
    } catch (e: Exception) {
        logger.error("程序运行异常: ${e.message}", e)
        exitProcess(1)
    }
}
