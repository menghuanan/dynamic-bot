package top.bilibili.data

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Color
import top.bilibili.draw.makeRGB
import java.io.File

/**
 * 动态卡片绘制时使用的颜色主题配置。
 */
@Serializable
data class Theme(
    val cardBgColorHex: String,

    val cardOutlineColorHex: String,
    val faceOutlineColorHex: String,
    val drawOutlineColorHex: String,

    val nameColorHex: String,
    val titleColorHex: String,
    val subTitleColorHex: String,
    val descColorHex: String,
    val contentColorHex: String,
    val linkColorHex: String,
    val footerColorHex: String,

    val cardShadow: Shadow,
    val smallCardShadow: Shadow,

    val mainLeftBadge: BadgeColor,
    val mainRightBadge: BadgeColor,
    val subLeftBadge: BadgeColor,
    val subRightBadge: BadgeColor,

) {

    /**
     * 阴影样式配置。
     */
    @Serializable
    data class Shadow(
        val shadowColorHex: String,
        val offsetX: Float,
        val offsetY: Float,
        val blur: Float,
        val spread: Float = 0f,
    ) {
        val shadowColor: Int get() = Color.makeRGB(shadowColorHex)
    }

    /**
     * 徽章文本与背景颜色配置。
     */
    @Serializable
    data class BadgeColor(
        val fontColorHex: String,
        val bgColorHex: String,
    ) {

        val fontColor: Int get() = Color.makeRGB(fontColorHex)
        val bgColor: Int get() = Color.makeRGB(bgColorHex)
    }

    val cardBgColor: Int get() = Color.makeRGB(cardBgColorHex)

    //val cardOutlineColor: Int get() = Color.makeRGB(cardOutlineColorHex)
    val cardOutlineColors: IntArray get() = cardOutlineColorHex.split(";").map { Color.makeRGB(it) }.toIntArray()
    val faceOutlineColor: Int get() = Color.makeRGB(faceOutlineColorHex)
    val drawOutlineColor: Int get() = Color.makeRGB(drawOutlineColorHex)

    val nameColor: Int get() = Color.makeRGB(nameColorHex)
    val titleColor: Int get() = Color.makeRGB(titleColorHex)
    val subTitleColor: Int get() = Color.makeRGB(subTitleColorHex)
    val descColor: Int get() = Color.makeRGB(descColorHex)
    val contentColor: Int get() = Color.makeRGB(contentColorHex)
    val linkColor: Int get() = Color.makeRGB(linkColorHex)
    val footerColor: Int get() = Color.makeRGB(footerColorHex)

}

/**
 * 图片主题配置管理
 * 移除 Mirai 依赖，使用纯 Kotlin 实现
 */
object BiliImageTheme {
    var customOverload: Boolean = false
    var customTheme: Theme = Theme(
        "#B4FFFFFF",
        "#FFFFFF",
        "#A0FFFFFF",
        "#FFFFFF",
        "#FB7299",
        "#313131",
        "#9C9C9C",
        "#666666",
        "#222222",
        "#178BCF",
        "#9C9C9C",
        Theme.Shadow("#46000000", 6f, 6f, 25f, 0f),
        Theme.Shadow("#1E000000", 5f, 5f, 15f, 0f),
        Theme.BadgeColor("#00CBFF", "#B4FFFFFF"),
        Theme.BadgeColor("#FFFFFF", "#48C7F0"),
        Theme.BadgeColor("#FFFFFF", "#FB7299"),
        Theme.BadgeColor("#FFFFFF", "#48C7F0"),
    )

    val theme: Map<String, Theme> = mapOf(
        "v3" to Theme(
            "#B4FFFFFF",
            "#FFFFFF",
            "#A0FFFFFF",
            "#FFFFFF",
            "#FB7299",
            "#313131",
            "#9C9C9C",
            "#666666",
            "#222222",
            "#178BCF",
            "#9C9C9C",
            Theme.Shadow("#46000000", 6f, 6f, 25f, 0f),
            Theme.Shadow("#1E000000", 5f, 5f, 15f, 0f),
            Theme.BadgeColor("#00CBFF", "#B4FFFFFF"),
            Theme.BadgeColor("#FFFFFF", "#48C7F0"),
            Theme.BadgeColor("#FFFFFF", "#FB7299"),
            Theme.BadgeColor("#FFFFFF", "#48C7F0"),
        ),
        "v3RainbowOutline" to Theme(
            "#B4FFFFFF",
            "#ff0000;#ff00ff;#0000ff;#00ffff;#00ff00;#ffff00;#ff0000",
            "#A0FFFFFF",
            "#FFFFFF",
            "#FB7299",
            "#313131",
            "#9C9C9C",
            "#666666",
            "#222222",
            "#178BCF",
            "#9C9C9C",
            Theme.Shadow("#46000000", 6f, 6f, 25f, 0f),
            Theme.Shadow("#1E000000", 5f, 5f, 15f, 0f),
            Theme.BadgeColor("#00CBFF", "#B4FFFFFF"),
            Theme.BadgeColor("#FFFFFF", "#48C7F0"),
            Theme.BadgeColor("#FFFFFF", "#FB7299"),
            Theme.BadgeColor("#FFFFFF", "#48C7F0"),
        ),
        "v2" to Theme(
            "#C8FFFFFF",
            "#FFFFFF",
            "#A0FFFFFF",
            "#FFFFFF",
            "#FB7299",
            "#313131",
            "#9C9C9C",
            "#666666",
            "#222222",
            "#178BCF",
            "#9C9C9C",
            Theme.Shadow("#00000000", 0f, 0f, 0f, 0f),
            Theme.Shadow("#00000000", 0f, 0f, 0f, 0f),
            Theme.BadgeColor("#00CBFF", "#C8FFFFFF"),
            Theme.BadgeColor("#FFFFFF", "#48C7F0"),
            Theme.BadgeColor("#FFFFFF", "#FB7299"),
            Theme.BadgeColor("#FFFFFF", "#48C7F0"),
        )
    )

    /**
     * 重新加载自定义图片主题配置。
     */
    fun reload() {
        val configFile = File("config/ImageTheme.custom.yml")
        if (!configFile.exists()) {
            // 文件缺失时回退内置主题，避免渲染流程依赖外部配置文件存在。
            customOverload = false
            return
        }

        runCatching {
            val loaded = Yaml.default.decodeFromString<Theme>(configFile.readText())
            customTheme = loaded
            customOverload = true
        }.onFailure {
            // 主题文件格式错误时禁用覆盖，优先保证图片仍可正常生成。
            customOverload = false
        }
    }
}
