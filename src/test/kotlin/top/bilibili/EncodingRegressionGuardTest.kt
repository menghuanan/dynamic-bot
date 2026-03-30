package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class EncodingRegressionGuardTest {
    @Test
    fun `tracked project text files should not use utf8 bom`() {
        val root = Path.of(".").toAbsolutePath().normalize()
        val offendingFiles = trackedTextFiles(root)
            .filter { hasUtf8Bom(it) }
            .map { root.relativize(it).toString().replace('\\', '/') }
            .sorted()

        assertTrue(
            offendingFiles.isEmpty(),
            "UTF-8 BOM found in: ${offendingFiles.joinToString(", ")}",
        )
    }

    @Test
    fun `editorconfig should declare utf8 charset`() {
        val text = Files.readString(Path.of(".editorconfig"), StandardCharsets.UTF_8)
        assertTrue(text.contains("charset = utf-8"), ".editorconfig should enforce UTF-8 without BOM")
    }

    @Test
    fun `wrapper scripts should declare and use platform compatible line endings`() {
        // 批处理脚本包含中文注释时必须保持 CRLF，否则 cmd.exe 会把注释内容误判为命令。
        val batchBytes = Files.readAllBytes(Path.of("gradlew.bat"))
        assertTrue(
            hasCrLfOnly(batchBytes),
            "gradlew.bat should use CRLF only for Windows cmd compatibility",
        )

        // Shell 启动脚本必须保持 LF，避免 Linux 解释器读取到回车字符。
        val shellBytes = Files.readAllBytes(Path.of("gradlew"))
        assertTrue(
            hasLfOnly(shellBytes),
            "gradlew should use LF only for Linux shell compatibility",
        )

        // 根目录属性文件需要固定脚本换行规则，避免不同平台 checkout 后再次漂移。
        val attributesPath = Path.of(".gitattributes")
        assertTrue(Files.exists(attributesPath), ".gitattributes should pin wrapper script line endings")
        val attributesText = Files.readString(attributesPath, StandardCharsets.UTF_8)
        assertTrue(attributesText.contains("*.bat text eol=crlf"))
        assertTrue(attributesText.contains("*.sh text eol=lf"))
        assertTrue(attributesText.contains("gradlew text eol=lf"))
    }

    @Test
    fun `garbled chinese text should be restored in source files`() {
        val policy = read("src/main/kotlin/top/bilibili/core/resource/TaskResourcePolicyRegistry.kt")
        assertTrue(policy.contains("周期轮询任务"))
        assertTrue(policy.contains("持续消费消息通道"))
        assertTrue(policy.contains("周期缓存维护"))
        assertTrue(policy.contains("周期日志维护"))
        assertTrue(policy.contains("周期 Skia 维护"))
        assertTrue(policy.contains("任务资源策略未覆盖:"))

        val jsonTool = read("src/main/kotlin/top/bilibili/utils/Json2DataClass.kt")
        assertTrue(jsonTool.contains("请求最终失败，无法生成 Data Class:"))
        assertTrue(jsonTool.contains("请求失败，3秒后重试"))
        assertTrue(jsonTool.contains("Json2DataClass 解析失败:"))

        val sendTasker = read("src/main/kotlin/top/bilibili/tasker/SendTasker.kt")
        assertTrue(sendTasker.contains("消息发送任务"))
        assertTrue(sendTasker.contains("启动消息队列处理协程"))
        assertTrue(sendTasker.contains("启动消息接收处理协程"))
        assertTrue(sendTasker.contains("处理消息发送队列"))
        assertTrue(sendTasker.contains("处理从 messageChannel 接收的消息"))
        assertTrue(sendTasker.contains("如果消息已指定联系人，只发送给该联系人"))
        assertTrue(sendTasker.contains("发送给所有订阅该用户的联系人"))
        assertTrue(sendTasker.contains("构建消息段"))
        assertTrue(sendTasker.contains("加入发送队列"))
        assertTrue(sendTasker.contains("消息间隔"))
        assertTrue(sendTasker.contains("构建 OneBot v11 消息段"))
    }

    @Test
    fun `encoding sensitive io should explicitly use utf8`() {
        val httpGet = read("src/main/kotlin/top/bilibili/utils/translate/HttpGet.kt")
        assertTrue(httpGet.contains("InputStreamReader(`is`, StandardCharsets.UTF_8)"))

        val guardian = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")
        assertTrue(guardian.contains("OutputStreamWriter(FileOutputStream(logFile, true), StandardCharsets.UTF_8)"))
        assertTrue(guardian.contains("OutputStreamWriter(FileOutputStream(logFile), StandardCharsets.UTF_8)"))
    }

    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    // 逐字节校验 Windows 批处理脚本没有裸 LF，确保每个换行都由 CRLF 组成。
    private fun hasCrLfOnly(bytes: ByteArray): Boolean = bytes.indices
        .filter { bytes[it] == '\n'.code.toByte() }
        .all { index -> index > 0 && bytes[index - 1] == '\r'.code.toByte() }

    // 逐字节校验 Linux Shell 脚本没有 CR，避免 shebang 与命令解析携带回车。
    private fun hasLfOnly(bytes: ByteArray): Boolean = bytes.none { it == '\r'.code.toByte() }

    private fun trackedTextFiles(root: Path): List<Path> {
        val process = ProcessBuilder("git", "ls-files")
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readLines() }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ls-files failed: ${output.joinToString("\\n")}" }

        return output
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { root.resolve(it).normalize() }
            .filter { Files.isRegularFile(it) }
            .filter { isProjectTextFile(it.fileName.toString()) }
            .toList()
    }

    private fun isProjectTextFile(fileName: String): Boolean {
        val exactNames = setOf(
            ".editorconfig",
            ".gitattributes",
            ".gitignore",
            "Dockerfile",
            "LICENSE",
            "gradlew",
        )
        if (fileName in exactNames) {
            return true
        }

        val lowerName = fileName.lowercase()
        return lowerName.endsWith(".bat") ||
            lowerName.endsWith(".gradle") ||
            lowerName.endsWith(".java") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".kt") ||
            lowerName.endsWith(".kts") ||
            lowerName.endsWith(".md") ||
            lowerName.endsWith(".properties") ||
            lowerName.endsWith(".sh") ||
            lowerName.endsWith(".txt") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".yaml") ||
            lowerName.endsWith(".yml")
    }

    private fun hasUtf8Bom(path: Path): Boolean {
        val bytes = Files.readAllBytes(path)
        return bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
    }
}
