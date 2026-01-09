package top.bilibili

import org.slf4j.LoggerFactory
import top.bilibili.core.BiliBiliBot
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("Main")

/**
 * 程序主入口
 */
fun main(args: Array<String>) {
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
                BiliBili 动态推送 Bot v1.3

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

        // 添加 JVM 关闭钩子
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("收到停止信号，正在关闭...")
            BiliBiliBot.stop()
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
