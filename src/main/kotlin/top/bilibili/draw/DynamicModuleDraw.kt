package top.bilibili.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import top.bilibili.BiliConfigManager
import top.bilibili.api.twemoji
import top.bilibili.data.ModuleAuthor
import top.bilibili.data.ModuleDispute
import top.bilibili.data.ModuleDynamic
import top.bilibili.skia.DrawingSession
import top.bilibili.skia.SkiaManager
import top.bilibili.utils.CacheType
import top.bilibili.utils.FontUtils
import top.bilibili.utils.formatTime
import top.bilibili.utils.getOrDownloadImage
import top.bilibili.utils.translate.trans
import java.util.stream.Collectors
import kotlin.math.abs
import kotlin.math.ceil


/**
 * 生成动态模块的图片列表
 * 注意：返回的 Image 列表由调用方负责关闭
 */
suspend fun ModuleDynamic.makeGeneral(session: DrawingSession, isForward: Boolean = false): List<Image> {
    return mutableListOf<Image>().apply {
        topic?.drawGeneral(session)?.let { add(it) }
        desc?.drawGeneral(session)?.let { add(it) }
        major?.makeGeneral(session, isForward)?.let { add(it) }
        additional?.makeGeneral(session)?.let { add(it) }
    }
}

suspend fun ModuleDynamic.Additional.makeGeneral(session: DrawingSession): Image? {
    return when (type) {
        "ADDITIONAL_TYPE_COMMON" -> {
            drawAdditionalCard(
                session,
                common!!.headText,
                common.cover,
                common.title,
                common.desc1,
                common.desc2
            )
        }

        "ADDITIONAL_TYPE_RESERVE" -> {
            drawAdditionalCard(
                session,
                when (reserve!!.stype) {
                    1 -> "视频预约"
                    2 -> "直播预约"
                    4 -> "首映预告"
                    else -> "预约"
                },
                reserve.premiere?.cover,
                reserve.title,
                "${reserve.desc1.text}  ${reserve.desc2.text}",
                reserve.desc3?.text
            )
        }

        "ADDITIONAL_TYPE_VOTE" -> {
            drawAdditionalCard(
                session,
                "投票",
                null,
                vote!!.desc,
                "结束时间 ${vote.endTime.formatTime}",
                null
            )
        }

        "ADDITIONAL_TYPE_UGC" -> {
            drawAdditionalCard(
                session,
                ugc!!.headText,
                ugc.cover,
                ugc.title,
                "时长 ${ugc.duration}  ${ugc.descSecond}",
                null
            )
        }

        "ADDITIONAL_TYPE_GOODS" -> {
            drawAdditionalCard(
                session,
                goods!!.headText,
                goods.items[0].cover,
                goods.items[0].name,
                "${goods.items[0].price} 起",
                null
            )
        }

        "ADDITIONAL_TYPE_UPOWER_LOTTERY" -> {
            drawAdditionalCard(
                session,
                "充电抽奖",
                null,
                lottery!!.title,
                lottery.desc.text,
                null
            )
        }

        else -> {
            logger.warn("未知类型附加卡片 $type")
            null
        }
    }
}

@Deprecated("Use version with DrawingSession for better resource management")
suspend fun ModuleDynamic.Additional.makeGeneral(): Image? {
    return when (type) {
        "ADDITIONAL_TYPE_COMMON" -> {
            drawAdditionalCard(
                common!!.headText,
                common.cover,
                common.title,
                common.desc1,
                common.desc2
            )
        }

        "ADDITIONAL_TYPE_RESERVE" -> {
            drawAdditionalCard(
                when (reserve!!.stype) {
                    1 -> "视频预约"
                    2 -> "直播预约"
                    4 -> "首映预告"
                    else -> "预约"
                },
                reserve.premiere?.cover,
                reserve.title,
                "${reserve.desc1.text}  ${reserve.desc2.text}",
                reserve.desc3?.text
            )
        }

        "ADDITIONAL_TYPE_VOTE" -> {
            drawAdditionalCard(
                "投票",
                null,
                vote!!.desc,
                "结束时间 ${vote.endTime.formatTime}",
                null
            )
        }

        "ADDITIONAL_TYPE_UGC" -> {
            drawAdditionalCard(
                ugc!!.headText,
                ugc.cover,
                ugc.title,
                "时长 ${ugc.duration}  ${ugc.descSecond}",
                null
            )
        }

        "ADDITIONAL_TYPE_GOODS" -> {
            drawAdditionalCard(
                goods!!.headText,
                goods.items[0].cover,
                goods.items[0].name,
                "${goods.items[0].price} 起",
                null
            )
        }

        "ADDITIONAL_TYPE_UPOWER_LOTTERY" -> {
            drawAdditionalCard(
                "充电抽奖",
                null,
                lottery!!.title,
                lottery.desc.text,
                null
            )
        }

        else -> {
            logger.warn("未知类型附加卡片 $type")
            null
        }
    }
}

suspend fun drawAdditionalCard(
    session: DrawingSession,
    label: String,
    cover: String?,
    title: String,
    desc1: String,
    desc2: String?
): Image {

    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 1
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = TextStyle().apply {
            fontSize = quality.titleFontSize * 0.8f
            color = theme.titleColor
            fontFamilies = arrayOf(mainTypeface.familyName)
        }
    }

    val height = if (cover != null || desc2 != null)
        quality.additionalCardHeight.toFloat()
    else
        quality.additionalCardHeight * 0.7f


    val additionalCardRect = RRect.makeXYWH(
        quality.cardPadding.toFloat(),
        quality.subTitleFontSize + quality.cardPadding + 1f,
        cardContentRect.width,
        height,
        quality.cardArc
    )

    val coverImg = if (cover != null) getOrDownloadImage(cover, CacheType.OTHER) else null

    return try {
        val surface = session.createSurface(
            cardRect.width.toInt(),
            (height + quality.subTitleFontSize + quality.cardPadding * 2f).toInt()
        )
        val canvas = surface.canvas

        canvas.drawCard(additionalCardRect)
        canvas.drawRectShadowAntiAlias(additionalCardRect.inflate(1f), theme.smallCardShadow)

        val labelFont = with(session) { font.makeWithSize(quality.subTitleFontSize).track() }
        val labelTextLine = with(session) { TextLine.make(label, labelFont).track() }

        canvas.drawTextLine(labelTextLine, additionalCardRect.left + 8, quality.subTitleFontSize, Paint().apply {
            color = theme.subTitleColor
        })

        var x = quality.cardPadding.toFloat()

        coverImg?.let { img ->
            val imgRect = RRect.makeXYWH(
                quality.cardPadding.toFloat(),
                quality.subTitleFontSize + quality.cardPadding + 1f,
                quality.additionalCardHeight.toFloat() * img.width / img.height,
                quality.additionalCardHeight.toFloat(),
                quality.cardArc
            ).inflate(-1f) as RRect
            canvas.drawImageRRect(img, imgRect)
            x += imgRect.width
        }

        x += quality.cardPadding

        val titleParagraph = with(session) {
            ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(title).build()
                .layout(cardContentRect.width - x).track()
        }
        paragraphStyle.apply {
            textStyle = descTextStyle.apply {
                fontSize = quality.subTitleFontSize * 0.8f
            }
        }
        val desc1Paragraph = with(session) {
            ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(desc1).build()
                .layout(cardContentRect.width - x).track()
        }
        val desc2Paragraph = desc2?.let {
            with(session) {
                ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(it).build()
                    .layout(cardContentRect.width - x).track()
            }
        }

        val top = (additionalCardRect.height - (titleParagraph.height * if (desc2 == null) 2 else 3)) / 2

        var y = additionalCardRect.top + top
        titleParagraph.paint(canvas, x, y)

        y += titleParagraph.height
        desc1Paragraph.paint(canvas, x, y)

        if (desc2Paragraph != null) {
            y += titleParagraph.height
            desc2Paragraph.paint(canvas, x, y)
        }

        with(session) { surface.makeImageSnapshot().track() }
    } finally {
        coverImg?.close()
    }
}

@Deprecated("Use version with DrawingSession for better resource management")
suspend fun drawAdditionalCard(
    label: String,
    cover: String?,
    title: String,
    desc1: String,
    desc2: String?
): Image {

    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 1
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = TextStyle().apply {
            fontSize = quality.titleFontSize * 0.8f
            color = theme.titleColor
            fontFamilies = arrayOf(mainTypeface.familyName)
        }
    }

    val height = if (cover != null || desc2 != null)
        quality.additionalCardHeight.toFloat()
    else
        quality.additionalCardHeight * 0.7f


    val additionalCardRect = RRect.makeXYWH(
        quality.cardPadding.toFloat(),
        quality.subTitleFontSize + quality.cardPadding + 1f,
        cardContentRect.width,
        height,
        quality.cardArc
    )

    val coverImg = if (cover != null) getOrDownloadImage(cover, CacheType.OTHER) else null

    return try {
        createImage(
            cardRect.width.toInt(),
            (height + quality.subTitleFontSize + quality.cardPadding * 2f).toInt()
        ) { canvas ->
            canvas.drawCard(additionalCardRect)
            canvas.drawRectShadowAntiAlias(additionalCardRect.inflate(1f), theme.smallCardShadow)

            val labelFont = font.makeWithSize(quality.subTitleFontSize)
            val labelTextLine = TextLine.make(label, labelFont)
            try {
                canvas.drawTextLine(labelTextLine, additionalCardRect.left + 8, quality.subTitleFontSize, Paint().apply {
                    color = theme.subTitleColor
                })
            } finally {
                labelTextLine.close()
                labelFont.close()
            }

            var x = quality.cardPadding.toFloat()

            coverImg?.let { img ->
                val imgRect = RRect.makeXYWH(
                    quality.cardPadding.toFloat(),
                    quality.subTitleFontSize + quality.cardPadding + 1f,
                    quality.additionalCardHeight.toFloat() * img.width / img.height,
                    quality.additionalCardHeight.toFloat(),
                    quality.cardArc
                ).inflate(-1f) as RRect
                canvas.drawImageRRect(img, imgRect)
                x += imgRect.width
            }

            x += quality.cardPadding

            val titleParagraph =
                ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(title).build()
                    .layout(cardContentRect.width - x)
            paragraphStyle.apply {
                textStyle = descTextStyle.apply {
                    fontSize = quality.subTitleFontSize * 0.8f
                }
            }
            val desc1Paragraph =
                ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(desc1).build()
                    .layout(cardContentRect.width - x)
            val desc2Paragraph = desc2?.let {
                ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(it).build().layout(cardContentRect.width - x)
            }

            try {
                val top = (additionalCardRect.height - (titleParagraph.height * if (desc2 == null) 2 else 3)) / 2

                var y = additionalCardRect.top + top
                titleParagraph.paint(canvas, x, y)

                y += titleParagraph.height
                desc1Paragraph.paint(canvas, x, y)

                if (desc2Paragraph != null) {
                    y += titleParagraph.height
                    desc2Paragraph.paint(canvas, x, y)
                }
            } finally {
                titleParagraph.close()
                desc1Paragraph.close()
                desc2Paragraph?.close()
            }
        }
    } finally {
        coverImg?.close()
    }
}

suspend fun ModuleDispute.drawGeneral(session: DrawingSession): Image {
    val lineCount = useTextLine(title, font) { textLine ->
        if (textLine.width / cardContentRect.width > 1) 2 else 1
    }
    val textCardHeight = (quality.contentFontSize + quality.lineSpace * 2) * lineCount

    val textCardRect = Rect.makeXYWH(
        quality.cardPadding.toFloat(),
        0f,
        cardContentRect.width,
        textCardHeight
    )

    val disputeTitle = title
    val surface = session.createSurface(cardRect.width.toInt(), textCardHeight.toInt())
    val canvas = surface.canvas

    canvas.drawRRect(textCardRect.toRRect(5f), Paint().apply { color = Color.makeRGB(255, 241, 211) })

    var x = quality.cardPadding.toFloat() + 10
    val y = quality.contentFontSize * 0.8f + quality.lineSpace
    try {
        val svg = loadSVG("icon/DISPUTE.svg")
        if (svg != null) {
            val iconSize = quality.contentFontSize
            val iconImage = svg.makeImage(session, iconSize, iconSize)
            canvas.drawImage(iconImage, x, y - quality.contentFontSize * 0.9f)
            x += iconSize + quality.lineSpace
        } else {
            logger.warn("未找到类型为 DISPUTE 的图标")
        }
    } catch (e: Exception) {
        logger.warn("加载 DISPUTE 图标失败: ${e.message}")
    }

    canvas.drawTextArea(disputeTitle, textCardRect, x, y, font, Paint().apply { color = Color.makeRGB(231, 139, 31) })

    return with(session) { surface.makeImageSnapshot().track() }
}

@Deprecated("Use version with DrawingSession for better resource management")
suspend fun ModuleDispute.drawGeneral(): Image {
    val lineCount = useTextLine(title, font) { textLine ->
        if (textLine.width / cardContentRect.width > 1) 2 else 1
    }
    val textCardHeight = (quality.contentFontSize + quality.lineSpace * 2) * lineCount

    val textCardRect = Rect.makeXYWH(
        quality.cardPadding.toFloat(),
        0f,
        cardContentRect.width,
        textCardHeight
    )

    val disputeTitle = title
    return createImage(cardRect.width.toInt(), textCardHeight.toInt()) { canvas ->
        canvas.drawRRect(textCardRect.toRRect(5f), Paint().apply { color = Color.makeRGB(255, 241, 211) })

        var x = quality.cardPadding.toFloat() + 10
        val y = quality.contentFontSize * 0.8f + quality.lineSpace
        try {
            val svg = loadSVG("icon/DISPUTE.svg")
            if (svg != null) {
                val iconSize = quality.contentFontSize
                val iconImage = svg.makeImage(iconSize, iconSize)
                try {
                    canvas.drawImage(iconImage, x, y - quality.contentFontSize * 0.9f)
                } finally {
                    iconImage.close()
                }
                x += iconSize + quality.lineSpace
            } else {
                logger.warn("未找到类型为 DISPUTE 的图标")
            }
        } catch (e: Exception) {
            logger.warn("加载 DISPUTE 图标失败: ${e.message}")
        }

        canvas.drawTextArea(disputeTitle, textCardRect, x, y, font, Paint().apply { color = Color.makeRGB(231, 139, 31) })
    }
}

suspend fun ModuleDynamic.Topic.drawGeneral(session: DrawingSession): Image {

    val lineCount = useTextLine(name, font) { textLine ->
        if (textLine.width / cardContentRect.width > 1) 2 else 1
    }
    val textCardHeight = (quality.contentFontSize + quality.lineSpace * 2) * lineCount

    val textCardRect = Rect.makeXYWH(
        quality.cardPadding.toFloat(),
        0f,
        cardContentRect.width,
        textCardHeight
    )

    val topicName = name
    val surface = session.createSurface(cardRect.width.toInt(), textCardHeight.toInt())
    val canvas = surface.canvas

    var x = quality.cardPadding.toFloat()
    val y = quality.contentFontSize * 0.8f + quality.lineSpace
    try {
        val svg = loadSVG("icon/TOPIC.svg")
        if (svg != null) {
            val iconSize = quality.contentFontSize
            val iconImage = svg.makeImage(session, iconSize, iconSize)
            canvas.drawImage(iconImage, x, y - quality.contentFontSize * 0.9f)
            x += iconSize + quality.lineSpace
        } else {
            logger.warn("未找到类型为 TOPIC 的图标")
        }
    } catch (e: Exception) {
        logger.warn("加载 TOPIC 图标失败: ${e.message}")
    }

    canvas.drawTextArea(topicName, textCardRect, x, y, font, linkPaint)

    return with(session) { surface.makeImageSnapshot().track() }
}

@Deprecated("Use version with DrawingSession for better resource management")
suspend fun ModuleDynamic.Topic.drawGeneral(): Image {

    val lineCount = useTextLine(name, font) { textLine ->
        if (textLine.width / cardContentRect.width > 1) 2 else 1
    }
    val textCardHeight = (quality.contentFontSize + quality.lineSpace * 2) * lineCount

    val textCardRect = Rect.makeXYWH(
        quality.cardPadding.toFloat(),
        0f,
        cardContentRect.width,
        textCardHeight
    )

    val topicName = name
    return createImage(cardRect.width.toInt(), textCardHeight.toInt()) { canvas ->
        var x = quality.cardPadding.toFloat()
        val y = quality.contentFontSize * 0.8f + quality.lineSpace
        try {
            val svg = loadSVG("icon/TOPIC.svg")
            if (svg != null) {
                val iconSize = quality.contentFontSize
                val iconImage = svg.makeImage(iconSize, iconSize)
                try {
                    canvas.drawImage(iconImage, x, y - quality.contentFontSize * 0.9f)
                } finally {
                    iconImage.close()
                }
                x += iconSize + quality.lineSpace
            } else {
                logger.warn("未找到类型为 TOPIC 的图标")
            }
        } catch (e: Exception) {
            logger.warn("加载 TOPIC 图标失败: ${e.message}")
        }

        canvas.drawTextArea(topicName, textCardRect, x, y, font, linkPaint)
    }
}

suspend fun ModuleDynamic.ContentDesc.drawGeneral(session: DrawingSession): Image {
    val paragraphStyle = ParagraphStyle().apply {
        alignment = Alignment.LEFT
        textStyle = titleTextStyle
    }

    val traCutLineNode = ModuleDynamic.ContentDesc.RichTextNode(
        "RICH_TEXT_NODE_TYPE_TEXT",
        BiliConfigManager.config.translateConfig.cutLine,
        BiliConfigManager.config.translateConfig.cutLine
    )

    val tra = trans(text)

    val textParagraph =
        ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText("$text${traCutLineNode.text}$tra").build()
            .layout(cardContentRect.width)

    val textCardHeight = (quality.contentFontSize + quality.lineSpace * 2) * (textParagraph.lineNumber + 2)
    // 关闭用于计算高度的 paragraph
    textParagraph.close()

    val textCardRect = Rect.makeXYWH(
        quality.cardPadding.toFloat(),
        0f,
        cardContentRect.width,
        textCardHeight
    )

    var x = textCardRect.left
    var y = quality.contentFontSize + quality.lineSpace

    val nodes = if (tra != null) {
        richTextNodes.plus(traCutLineNode).plus(
            ModuleDynamic.ContentDesc.RichTextNode(
                "RICH_TEXT_NODE_TYPE_TEXT", tra, tra
            )
        )
    } else {
        richTextNodes
    }

    val surface = session.createSurface(cardRect.width.toInt(), textCardHeight.toInt())
    val canvas = surface.canvas
    nodes.forEach {
        when (it.type) {
            "RICH_TEXT_NODE_TYPE_TEXT" -> {
                val text = it.text.replace("\r\n", "\n").replace("\r", "\n")
                val point = canvas.drawTextArea(text, textCardRect, x, y, font, generalPaint)
                x = point.x
                y = point.y
            }

            "RICH_TEXT_NODE_TYPE_EMOJI" -> {
                getOrDownloadImage(it.emoji!!.iconUrl, CacheType.EMOJI)?.let { img ->
                    try {
                        val emojiSize = useTextLine("🙂", font) { it.height }

                        if (x + emojiSize > textCardRect.right) {
                            x = textCardRect.left
                            y += emojiSize + quality.lineSpace
                        }
                        val srcRect = Rect.makeXYWH(0f, 0f, img.width.toFloat(), img.height.toFloat())
                        val tarRect = Rect.makeXYWH(x, y - emojiSize * 0.8f, emojiSize, emojiSize)
                        canvas.drawImageRect(
                            img,
                            srcRect,
                            tarRect,
                            FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                            null,
                            true
                        )
                        x += emojiSize
                    } finally {
                        img.close()
                    }
                }
            }

            "RICH_TEXT_NODE_TYPE_WEB",
            "RICH_TEXT_NODE_TYPE_VOTE",
            "RICH_TEXT_NODE_TYPE_LOTTERY",
            "RICH_TEXT_NODE_TYPE_BV" -> {
                try {
                    val svg = loadSVG("icon/${it.type}.svg")
                    if (svg != null) {
                        val iconSize = quality.contentFontSize
                        val iconImage = svg.makeImage(session, iconSize, iconSize)
                        canvas.drawImage(iconImage, x, y - quality.contentFontSize * 0.9f)
                        x += iconSize
                    } else {
                        logger.warn("未找到类型为 ${it.type} 的图标")
                    }
                } catch (e: Exception) {
                    logger.warn("加载 ${it.type} 图标失败: ${e.message}")
                }

                val point = canvas.drawTextArea(it.text, textCardRect, x, y, font, linkPaint)
                x = point.x
                y = point.y
            }

            else -> {
                val point = canvas.drawTextArea(it.text, textCardRect, x, y, font, linkPaint)
                x = point.x
                y = point.y
            }
        }
    }
    return with(session) {
        surface.makeImageSnapshot(IRect.makeXYWH(0, 0, cardRect.width.toInt(), ceil(y + quality.lineSpace * 2).toInt()))!!.track()
    }
}

@Deprecated("Use version with DrawingSession for better resource management")
suspend fun ModuleDynamic.ContentDesc.drawGeneral(): Image {
    val paragraphStyle = ParagraphStyle().apply {
        alignment = Alignment.LEFT
        textStyle = titleTextStyle
    }

    val traCutLineNode = ModuleDynamic.ContentDesc.RichTextNode(
        "RICH_TEXT_NODE_TYPE_TEXT",
        BiliConfigManager.config.translateConfig.cutLine,
        BiliConfigManager.config.translateConfig.cutLine
    )

    val tra = trans(text)

    val textParagraph =
        ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText("$text${traCutLineNode.text}$tra").build()
            .layout(cardContentRect.width)

    val textCardHeight = (quality.contentFontSize + quality.lineSpace * 2) * (textParagraph.lineNumber + 2)
    // 关闭用于计算高度的 paragraph
    textParagraph.close()

    val textCardRect = Rect.makeXYWH(
        quality.cardPadding.toFloat(),
        0f,
        cardContentRect.width,
        textCardHeight
    )

    var x = textCardRect.left
    var y = quality.contentFontSize + quality.lineSpace

    val nodes = if (tra != null) {
        richTextNodes.plus(traCutLineNode).plus(
            ModuleDynamic.ContentDesc.RichTextNode(
                "RICH_TEXT_NODE_TYPE_TEXT", tra, tra
            )
        )
    } else {
        richTextNodes
    }

    val surface = Surface.makeRasterN32Premul(cardRect.width.toInt(), textCardHeight.toInt())
    try {
        val canvas = surface.canvas
        nodes.forEach {
            when (it.type) {
                "RICH_TEXT_NODE_TYPE_TEXT" -> {
                    val text = it.text.replace("\r\n", "\n").replace("\r", "\n")
                    val point = canvas.drawTextArea(text, textCardRect, x, y, font, generalPaint)
                    x = point.x
                    y = point.y
                }

                "RICH_TEXT_NODE_TYPE_EMOJI" -> {
                    getOrDownloadImage(it.emoji!!.iconUrl, CacheType.EMOJI)?.let { img ->
                        try {
                            val emojiSize = useTextLine("🙂", font) { it.height }

                            if (x + emojiSize > textCardRect.right) {
                                x = textCardRect.left
                                y += emojiSize + quality.lineSpace
                            }
                            val srcRect = Rect.makeXYWH(0f, 0f, img.width.toFloat(), img.height.toFloat())
                            val tarRect = Rect.makeXYWH(x, y - emojiSize * 0.8f, emojiSize, emojiSize)
                            canvas.drawImageRect(
                                img,
                                srcRect,
                                tarRect,
                                FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                                null,
                                true
                            )
                            x += emojiSize
                        } finally {
                            img.close()
                        }
                    }
                }

                "RICH_TEXT_NODE_TYPE_WEB",
                "RICH_TEXT_NODE_TYPE_VOTE",
                "RICH_TEXT_NODE_TYPE_LOTTERY",
                "RICH_TEXT_NODE_TYPE_BV" -> {
                    try {
                        val svg = loadSVG("icon/${it.type}.svg")
                        if (svg != null) {
                            val iconSize = quality.contentFontSize
                            canvas.drawImage(svg.makeImage(iconSize, iconSize), x, y - quality.contentFontSize * 0.9f)
                            x += iconSize
                        } else {
                            logger.warn("未找到类型为 ${it.type} 的图标")
                        }
                    } catch (e: Exception) {
                        logger.warn("加载 ${it.type} 图标失败: ${e.message}")
                    }

                    val point = canvas.drawTextArea(it.text, textCardRect, x, y, font, linkPaint)
                    x = point.x
                    y = point.y
                }

                else -> {
                    val point = canvas.drawTextArea(it.text, textCardRect, x, y, font, linkPaint)
                    x = point.x
                    y = point.y
                }
            }
        }
        return surface.makeImageSnapshot(IRect.makeXYWH(0, 0, cardRect.width.toInt(), ceil(y + quality.lineSpace * 2).toInt()))!!
    } finally {
        surface.close()
    }
}

sealed class RichText(
    val text: String
) {
    data class Text(
        val value: String
    ) : RichText(value)

    data class Emoji(
        val value: String
    ) : RichText(value)
}

suspend fun Canvas.drawTextArea(text: String, rect: Rect, textX: Float, textY: Float, font: Font, paint: Paint): Point {
    var x = textX
    var y = textY

    val textNode = mutableListOf<RichText>()
    var index = 0

    emojiRegex.findAll(text).forEach {
        if (index != it.range.first) {
            textNode.add(RichText.Text(text.substring(index, it.range.first)))
        }
        textNode.add(RichText.Emoji(it.value))
        index = it.range.last + 1
    }

    if (index != text.length) {
        textNode.add(RichText.Text(text.substring(index, text.length)))
    }

    for (node in textNode) {
        when (node) {
            is RichText.Text -> {
                for (point in node.value.codePoints()) {
                    val c = String(intArrayOf(point), 0, intArrayOf(point).size)
                    if (c == "\n") {
                        x = rect.left
                        y += quality.contentFontSize + quality.lineSpace
                    } else {
                        val charLine = TextLine.make(c, font)
                        try {
                            if (x + charLine.width > rect.right) {
                                x = rect.left
                                y += quality.contentFontSize + quality.lineSpace
                            }
                            drawTextLine(charLine, x, y, paint)
                            x += charLine.width
                        } finally {
                            charLine.close()
                        }
                    }
                }
            }

            is RichText.Emoji -> {
                if (emojiTypeface != null) {
                        val tl = TextLine.make(node.value, emojiFont)
                        try {
                            if (x + tl.width > rect.right) {
                                x = rect.left
                                y += tl.height + quality.lineSpace
                            }
                            drawTextLine(tl, x, y, paint)
                            x += tl.width
                        } finally {
                            tl.close()
                        }
                }else {
                    val emoji = node.value.codePoints().mapToObj { it.toString(16) }.collect(Collectors.joining("-"))
                    val emojiSize = useTextLine("🙂", font) { it.height }

                    var emojiImg: Image? = null
                    try {
                        var e = emoji.split("-")
                        if (e.last() == "fe0f" && !e.contains("200d")) {
                            e = e.dropLast(1)
                        }
                        val et = e.joinToString("-")
                        emojiImg = getOrDownloadImage(twemoji(et), CacheType.EMOJI)

                        if (x + emojiSize > rect.right) {
                            x = rect.left
                            y += emojiSize + quality.lineSpace
                        }
                        if (emojiImg != null) {
                            val srcRect = Rect.makeXYWH(0f, 0f, emojiImg.width.toFloat(), emojiImg.height.toFloat())
                            val tarRect = Rect.makeXYWH(x, y - emojiSize * 0.8f, emojiSize * 0.9f, emojiSize * 0.9f)
                            drawImageRect(
                                emojiImg,
                                srcRect,
                                tarRect,
                                FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                                null,
                                true
                            )
                        }
                        x += emojiSize
                    } catch (e: Exception) {
                        logger.warn("加载 Emoji 图片失败 ($emoji): ${e.message}")
                    } finally {
                        emojiImg?.close()
                    }
                }
            }
        }
    }

    return Point(x, y)
}

suspend fun ModuleAuthor.drawForward(session: DrawingSession, time: String): Image {
    val authorFace = face
    val authorName = name
    val authorVerify = officialVerify?.type
    val surface = session.createSurface(
        quality.imageWidth - quality.cardMargin * 2,
        (quality.faceSize + quality.cardPadding).toInt()
    )
    val canvas = surface.canvas

    val faceSize = quality.faceSize * 0.6f
    canvas.drawAvatar(session, authorFace, "", authorVerify, faceSize, quality.verifyIconSize * 0.8f, true)

    val nameFont = with(session) { font.makeWithSize(quality.nameFontSize).track() }
    val timeFont = with(session) { font.makeWithSize(quality.subTitleFontSize).track() }
    val textLineName = with(session) { TextLine.make(authorName, nameFont).track() }
    val textLineTime = with(session) { TextLine.make(time, timeFont).track() }

    var x = faceSize + quality.cardPadding * 2.5f
    var y = ((faceSize - quality.nameFontSize) / 2) + quality.nameFontSize + quality.cardPadding

    canvas.drawTextLine(textLineName, x, y, Paint().apply { color = theme.nameColor })

    y -= (quality.nameFontSize - quality.subTitleFontSize) / 2
    x += textLineName.width + quality.cardPadding
    canvas.drawTextLine(textLineTime, x, y, Paint().apply { color = theme.subTitleColor })

    return with(session) { surface.makeImageSnapshot().track() }
}

@Deprecated("Use version with DrawingSession for better resource management")
suspend fun ModuleAuthor.drawForward(time: String): Image {
    val authorFace = face
    val authorName = name
    val authorVerify = officialVerify?.type
    return SkiaManager.executeDrawing {
        val surface = createSurface(
            quality.imageWidth - quality.cardMargin * 2,
            (quality.faceSize + quality.cardPadding).toInt()
        )
        val canvas = surface.canvas

        val faceSize = quality.faceSize * 0.6f
        canvas.drawAvatar(this, authorFace, "", authorVerify, faceSize, quality.verifyIconSize * 0.8f, true)

        val nameFont = font.makeWithSize(quality.nameFontSize)
        val timeFont = font.makeWithSize(quality.subTitleFontSize)
        val textLineName = TextLine.make(authorName, nameFont)
        val textLineTime = TextLine.make(time, timeFont)

        try {
            var x = faceSize + quality.cardPadding * 2.5f
            var y = ((faceSize - quality.nameFontSize) / 2) + quality.nameFontSize + quality.cardPadding

            canvas.drawTextLine(textLineName, x, y, Paint().apply { color = theme.nameColor })

            y -= (quality.nameFontSize - quality.subTitleFontSize) / 2
            x += textLineName.width + quality.cardPadding
            canvas.drawTextLine(textLineTime, x, y, Paint().apply { color = theme.subTitleColor })
        } finally {
            textLineName.close()
            textLineTime.close()
            nameFont.close()
            timeFont.close()
        }

        surface.makeImageSnapshot()
    }
}

suspend fun ModuleAuthor.drawGeneral(session: DrawingSession, time: String, link: String, themeColor: Int): Image {
    val authorFace = face
    val authorName = name
    val authorPendant = pendant?.image
    val authorVerify = officialVerify?.type
    val authorDecorate = decorate
    val authorIconBadge = iconBadge
    val surface = session.createSurface(
        quality.imageWidth - quality.cardMargin * 2,
        quality.pendantSize.toInt()
    )
    val canvas = surface.canvas

    canvas.drawAvatar(session, authorFace, authorPendant, authorVerify, quality.faceSize, quality.verifyIconSize)

    val nameFont = with(session) { font.makeWithSize(quality.nameFontSize).track() }
    val timeFont = with(session) { font.makeWithSize(quality.subTitleFontSize).track() }
    val textLineName = with(session) { TextLine.make(authorName, nameFont).track() }
    val textLineTime = with(session) { TextLine.make(time, timeFont).track() }

    var x = quality.faceSize + quality.cardPadding * 3.2f
    val space = (quality.pendantSize - quality.nameFontSize - quality.subTitleFontSize) / 3
    var y = quality.nameFontSize + space * 1.25f

    canvas.drawTextLine(textLineName, x, y, Paint().apply { color = theme.nameColor })

    y += quality.subTitleFontSize + space * 0.5f
    canvas.drawTextLine(textLineTime, x, y, Paint().apply { color = theme.subTitleColor })

    authorIconBadge?.let {
        val img = getOrDownloadImage(it.renderImg, CacheType.IMAGES)
        if (img != null) {
            try {
                val iconHeight = quality.subTitleFontSize
                val iconWidth = img.width / img.height * iconHeight
                x += textLineTime.width + quality.subTitleFontSize * 0.5f
                y -= textLineTime.height - iconHeight / 2
                canvas.drawImageRRect(img, RRect.Companion.makeXYWH(x, y, iconWidth, iconHeight, 0f))
            } finally {
                img.close()
            }
        }
    }

    canvas.drawOrnament(session, authorDecorate, link, themeColor)

    return with(session) { surface.makeImageSnapshot().track() }
}

@Deprecated("Use version with DrawingSession for better resource management")
suspend fun ModuleAuthor.drawGeneral(time: String, link: String, themeColor: Int): Image {
    val authorFace = face
    val authorName = name
    val authorPendant = pendant?.image
    val authorVerify = officialVerify?.type
    val authorDecorate = decorate
    val authorIconBadge = iconBadge
    return SkiaManager.executeDrawing {
        val surface = createSurface(
            quality.imageWidth - quality.cardMargin * 2,
            quality.pendantSize.toInt()
        )
        val canvas = surface.canvas

        canvas.drawAvatar(this, authorFace, authorPendant, authorVerify, quality.faceSize, quality.verifyIconSize)

        val nameFont = font.makeWithSize(quality.nameFontSize)
        val timeFont = font.makeWithSize(quality.subTitleFontSize)
        val textLineName = TextLine.make(authorName, nameFont)
        val textLineTime = TextLine.make(time, timeFont)

        try {
            var x = quality.faceSize + quality.cardPadding * 3.2f
            val space = (quality.pendantSize - quality.nameFontSize - quality.subTitleFontSize) / 3
            var y = quality.nameFontSize + space * 1.25f

            canvas.drawTextLine(textLineName, x, y, Paint().apply { color = theme.nameColor })

            y += quality.subTitleFontSize + space * 0.5f
            canvas.drawTextLine(textLineTime, x, y, Paint().apply { color = theme.subTitleColor })

            authorIconBadge?.let {
                val img = getOrDownloadImage(it.renderImg, CacheType.IMAGES)
                if (img != null) {
                    try {
                        val iconHeight = quality.subTitleFontSize
                        val iconWidth = img.width / img.height * iconHeight
                        x += textLineTime.width + quality.subTitleFontSize * 0.5f
                        y -= textLineTime.height - iconHeight / 2
                        canvas.drawImageRRect(img, RRect.Companion.makeXYWH(x, y, iconWidth, iconHeight, 0f))
                    } finally {
                        img.close()
                    }
                }
            }
        } finally {
            textLineName.close()
            textLineTime.close()
            nameFont.close()
            timeFont.close()
        }

        canvas.drawOrnament(authorDecorate, link, themeColor)

        surface.makeImageSnapshot()
    }
}

suspend fun Canvas.drawOrnament(
    session: DrawingSession,
    decorate: ModuleAuthor.Decorate?,
    link: String?,
    qrCodeColor: Int?
) {

    when (BiliConfigManager.config.imageConfig.cardOrnament) {
        "FanCard" -> {
            if (decorate != null) {
                getOrDownloadImage(decorate.cardUrl, CacheType.USER)?.let {fanImg ->
                    try {
                        val srcFRect = Rect(0f, 0f, fanImg.width.toFloat(), fanImg.height.toFloat())

                        val cardHeight = when (decorate.type) {
                            1, 2 -> quality.ornamentHeight * 0.6f
                            else -> quality.ornamentHeight
                        }

                        val cardWidth = fanImg.width * cardHeight / fanImg.height

                        val y = ((quality.faceSize - cardHeight + quality.contentSpace) / 2)
                        val tarFRect = Rect.makeXYWH(
                            cardContentRect.right - cardWidth - abs(y),
                            y + quality.cardPadding,
                            cardWidth,
                            cardHeight
                        )

                        drawImageRect(
                            fanImg,
                            srcFRect,
                            tarFRect,
                            FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                            null,
                            true
                        )
                        // 绘制粉丝数量（需要粉丝卡字体）
                        fansCardFont?.let { font ->
                            if (decorate.type == 3 && decorate.fan?.numStr != "") {
                                val textLineFan = TextLine.make(decorate.fan?.numStr, font)
                                try {
                                    drawTextLine(
                                        textLineFan,
                                        tarFRect.right - textLineFan.width * 2,
                                        tarFRect.bottom - (cardHeight - font.size) / 2,
                                        Paint().apply { color = Color.makeRGB(decorate.fan!!.color) }
                                    )
                                } finally {
                                    textLineFan.close()
                                }
                            }
                        }
                    } finally {
                        fanImg.close()
                    }
                }
            }
        }

        "QrCode" -> {
            val qrCodeImg = qrCode(session, link!!, quality.ornamentHeight.toInt(), qrCodeColor!!)
            val y = ((quality.faceSize - qrCodeImg.height + quality.contentSpace) / 2)
            val tarFRect = Rect.makeXYWH(
                cardContentRect.right - qrCodeImg.width - abs(y),
                y + quality.cardPadding,
                qrCodeImg.width.toFloat(),
                qrCodeImg.height.toFloat()
            )
            val srcFRect = Rect(0f, 0f, qrCodeImg.width.toFloat(), qrCodeImg.height.toFloat())
            drawImageRect(
                qrCodeImg,
                srcFRect,
                tarFRect,
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                Paint(),
                true
            )
        }
    }
}

@Deprecated("Use version with DrawingSession for better resource management")
suspend fun Canvas.drawOrnament(decorate: ModuleAuthor.Decorate?, link: String?, qrCodeColor: Int?) {

    when (BiliConfigManager.config.imageConfig.cardOrnament) {
        "FanCard" -> {
            if (decorate != null) {
                getOrDownloadImage(decorate.cardUrl, CacheType.USER)?.let {fanImg ->
                    try {
                        val srcFRect = Rect(0f, 0f, fanImg.width.toFloat(), fanImg.height.toFloat())

                        val cardHeight = when (decorate.type) {
                            1, 2 -> quality.ornamentHeight * 0.6f
                            else -> quality.ornamentHeight
                        }

                        val cardWidth = fanImg.width * cardHeight / fanImg.height

                        val y = ((quality.faceSize - cardHeight + quality.contentSpace) / 2)
                        val tarFRect = Rect.makeXYWH(
                            cardContentRect.right - cardWidth - abs(y),
                            y + quality.cardPadding,
                            cardWidth,
                            cardHeight
                        )

                        drawImageRect(
                            fanImg,
                            srcFRect,
                            tarFRect,
                            FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                            null,
                            true
                        )
                        // 绘制粉丝数量（需要粉丝卡字体）
                        fansCardFont?.let { font ->
                            if (decorate.type == 3 && decorate.fan?.numStr != "") {
                                val textLineFan = TextLine.make(decorate.fan?.numStr, font)
                                try {
                                    drawTextLine(
                                        textLineFan,
                                        tarFRect.right - textLineFan.width * 2,
                                        tarFRect.bottom - (cardHeight - font.size) / 2,
                                        Paint().apply { color = Color.makeRGB(decorate.fan!!.color) }
                                    )
                                } finally {
                                    textLineFan.close()
                                }
                            }
                        }
                    } finally {
                        fanImg.close()
                    }
                }
            }
        }

        "QrCode" -> {
            val qrCodeImg = qrCode(link!!, quality.ornamentHeight.toInt(), qrCodeColor!!)
            try {
                val y = ((quality.faceSize - qrCodeImg.height + quality.contentSpace) / 2)
                val tarFRect = Rect.makeXYWH(
                    cardContentRect.right - qrCodeImg.width - abs(y),
                    y + quality.cardPadding,
                    qrCodeImg.width.toFloat(),
                    qrCodeImg.height.toFloat()
                )
                val srcFRect = Rect(0f, 0f, qrCodeImg.width.toFloat(), qrCodeImg.height.toFloat())
                drawImageRect(
                    qrCodeImg,
                    srcFRect,
                    tarFRect,
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                    Paint(),
                    true
                )
            } finally {
                qrCodeImg.close()
            }
        }
    }
}