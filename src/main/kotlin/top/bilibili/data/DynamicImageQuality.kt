package top.bilibili.data

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Quality(
    val imageWidth: Int,
    val cardMargin: Int,
    val cardPadding: Int,
    val cardArc: Float,

    val nameFontSize: Float,
    val titleFontSize: Float,
    val subTitleFontSize: Float,
    val descFontSize: Float,
    val contentFontSize: Float,
    val footerFontSize: Float,

    val cardOutlineWidth: Float,
    val drawOutlineWidth: Float,

    val faceSize: Float,
    val noPendantFaceInflate: Float,
    val pendantSize: Float,
    val verifyIconSize: Float,
    val ornamentHeight: Float,

    var badgeHeight: Int,
    val badgePadding: Int,
    val badgeArc: Float,

    val lineSpace: Int,
    val drawSpace: Int,
    val contentSpace: Int,

    val smallCardHeight: Int,
    val additionalCardHeight: Int
)

/**
 * 图片质量配置管理
 * 移除 Mirai 依赖，使用纯 Kotlin 实现
 */
object BiliImageQuality {
    var customOverload: Boolean = false
    var customQuality: Quality = Quality(
        imageWidth = 1000,
        cardMargin = 30,
        cardPadding = 30,
        cardArc = 15f,
        nameFontSize = 36f,
        titleFontSize = 32f,
        subTitleFontSize = 28f,
        descFontSize = 26f,
        contentFontSize = 32f,
        footerFontSize = 28f,
        cardOutlineWidth = 3f,
        drawOutlineWidth = 3f,
        faceSize = 80f,
        noPendantFaceInflate = 10f,
        pendantSize = 140f,
        verifyIconSize = 30f,
        ornamentHeight = 125f,
        badgeHeight = 45,
        badgePadding = 8,
        badgeArc = 8f,
        lineSpace = 11,
        drawSpace = 15,
        contentSpace = 12,
        smallCardHeight = 200,
        additionalCardHeight = 130
    )

    val quality: Map<String, Quality> = mapOf(
        "800w" to Quality(
            imageWidth = 800,
            cardMargin = 20,
            cardPadding = 20,
            cardArc = 10f,

            nameFontSize = 30f,
            titleFontSize = 26f,
            subTitleFontSize = 22f,
            descFontSize = 20f,
            contentFontSize = 26f,
            footerFontSize = 22f,

            cardOutlineWidth = 2f,
            drawOutlineWidth = 2f,

            faceSize = 64f,
            noPendantFaceInflate = 5f,
            pendantSize = 112f,
            verifyIconSize = 20f,
            ornamentHeight = 90f,

            badgeHeight = 36,
            badgePadding = 5,
            badgeArc = 5f,

            lineSpace = 8,
            drawSpace = 10,
            contentSpace = 10,

            smallCardHeight = 160,
            additionalCardHeight = 90
        ),
        "1000w" to Quality(
            imageWidth = 1000,
            cardMargin = 30,
            cardPadding = 30,
            cardArc = 15f,

            nameFontSize = 36f,
            titleFontSize = 32f,
            subTitleFontSize = 28f,
            descFontSize = 26f,
            contentFontSize = 32f,
            footerFontSize = 28f,

            cardOutlineWidth = 3f,
            drawOutlineWidth = 3f,

            faceSize = 80f,
            noPendantFaceInflate = 10f,
            pendantSize = 140f,
            verifyIconSize = 30f,
            ornamentHeight = 125f,

            badgeHeight = 45,
            badgePadding = 8,
            badgeArc = 8f,

            lineSpace = 11,
            drawSpace = 15,
            contentSpace = 12,

            smallCardHeight = 200,
            additionalCardHeight = 130
        ),
        "1200w" to Quality(
            imageWidth = 1200,
            cardMargin = 40,
            cardPadding = 40,
            cardArc = 20f,

            nameFontSize = 42f,
            titleFontSize = 38f,
            subTitleFontSize = 34f,
            descFontSize = 32f,
            contentFontSize = 38f,
            footerFontSize = 34f,

            cardOutlineWidth = 4f,
            drawOutlineWidth = 4f,

            faceSize = 95f,
            noPendantFaceInflate = 13f,
            pendantSize = 170f,
            verifyIconSize = 40f,
            ornamentHeight = 140f,

            badgeHeight = 55,
            badgePadding = 11,
            badgeArc = 11f,

            lineSpace = 14,
            drawSpace = 20,
            contentSpace = 17,

            smallCardHeight = 240,
            additionalCardHeight = 160
        ),
        "1500w" to Quality(
            imageWidth = 1500,
            cardMargin = 50,
            cardPadding = 50,
            cardArc = 30f,

            nameFontSize = 51f,
            titleFontSize = 46f,
            subTitleFontSize = 43f,
            descFontSize = 40f,
            contentFontSize = 47f,
            footerFontSize = 43f,

            cardOutlineWidth = 6f,
            drawOutlineWidth = 6f,

            faceSize = 100f,
            noPendantFaceInflate = 18f,
            pendantSize = 190f,
            verifyIconSize = 50f,
            ornamentHeight = 150f,

            badgeHeight = 72,
            badgePadding = 15,
            badgeArc = 16f,

            lineSpace = 20,
            drawSpace = 25,
            contentSpace = 20,

            smallCardHeight = 300,
            additionalCardHeight = 205
        )
    )

    fun reload() {
        val configFile = File("config/ImageQuality.custom.yml")
        if (!configFile.exists()) {
            customOverload = false
            return
        }

        runCatching {
            val loaded = Yaml.default.decodeFromString<Quality>(configFile.readText())
            customQuality = loaded
            customOverload = true
        }.onFailure {
            customOverload = false
        }
    }
}
