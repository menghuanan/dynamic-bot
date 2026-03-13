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

/**
 * 程序主入口
 */
fun main(args: Array<String>) {
    // ⚠️ 必须在任何 Skiko 类加载之前初始化
    SkikoInitializer.initialize()

    try {
        // 解析命令行参数
        var enableDebug: Boolean? = null
        var showHelp = false

        for (arg in args) {
            when (arg.lowercase()) {
                "--debug", "-d" -> enableDebug = true
                "--help", "-h" -> showHelp = true
            }
        }

        // 显示帮助信息
        if (showHelp) {
            println("""
                BiliBili 动态推送 Bot ${currentVersionLabel()}

                用法: java -jar dynamic-bot.jar [选项]

                选项:
                  --debug, -d    启用 Debug 日志模式（覆盖配置文件设置）
                  --help, -h     显示此帮助信息

                示例:
                  java -jar dynamic-bot.jar           # 使用配置文件设置
                  java -jar dynamic-bot.jar --debug   # 启用 Debug 模式
            """.trimIndent())
            exitProcess(0)
        }

        // ✅ P3修复: 增强 shutdown hook，添加超时保护
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("收到停止信号，正在关闭...")
            try {
                val shutdownThread = Thread {
                    try {
                        BiliBiliBot.stop()
                    } catch (e: Exception) {
                        logger.error("停止 Bot 时发生错误: ${e.message}", e)
                    }
                }
                shutdownThread.start()
                shutdownThread.join(15000)  // 最多等待15秒

                if (shutdownThread.isAlive) {
                    logger.warn("停止操作超时 (15秒)，强制退出")
                    shutdownThread.interrupt()
                } else {
                    logger.info("Bot 已正常停止")
                }
            } catch (e: Exception) {
                logger.error("关闭过程中发生错误: ${e.message}", e)
            }
        })

        // 启动 Bot（传入命令行参数）
        BiliBiliBot.start(enableDebug)

        // 保持程序运行
        Thread.currentThread().join()

    } catch (e: InterruptedException) {
        logger.info("程序被中断")
    } catch (e: Exception) {
        logger.error("程序运行异常: ${e.message}", e)
        exitProcess(1)
    }
}
