package top.bilibili.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
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

/**
 * 绘制动态附加卡片。
 */
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


/**
 * 绘制通用附加卡片。
 */
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

    val coverImg = if (cover != null) with(session) { getOrDownloadImage(cover, CacheType.OTHER)?.track() } else null
    val surface = session.createSurface(
        cardRect.width.toInt(),
        (height + quality.subTitleFontSize + quality.cardPadding * 2f).toInt()
    )
    val canvas = surface.canvas

    canvas.drawCard(session, additionalCardRect)
    canvas.drawRectShadowAntiAlias(additionalCardRect.inflate(1f), theme.smallCardShadow)

    val labelFont = session.createFont(font.typeface!!, quality.subTitleFontSize)
    val labelTextLine = session.createTextLine(label, labelFont)

    canvas.drawTextLine(labelTextLine, additionalCardRect.left + 8, quality.subTitleFontSize, session.createPaint {
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
        buildParagraph(paragraphStyle, FontUtils.fonts, cardContentRect.width - x) {
            addText(title)
        }.track()
    }
    paragraphStyle.apply {
        textStyle = descTextStyle.apply {
            fontSize = quality.subTitleFontSize * 0.8f
        }
    }
    val desc1Paragraph = with(session) {
        buildParagraph(paragraphStyle, FontUtils.fonts, cardContentRect.width - x) {
            addText(desc1)
        }.track()
    }
    val desc2Paragraph = desc2?.let {
        with(session) {
            buildParagraph(paragraphStyle, FontUtils.fonts, cardContentRect.width - x) {
                addText(it)
            }.track()
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

    return with(session) { surface.makeImageSnapshot().track() }
}


/**
 * 绘制争议提示模块。
 */
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

    canvas.drawRRect(textCardRect.toRRect(5f), session.createPaint { color = Color.makeRGB(255, 241, 211) })

    var x = quality.cardPadding.toFloat() + 10
    val y = quality.contentFontSize * 0.8f + quality.lineSpace
    val svg = session.createSvg("icon/DISPUTE.svg")
    if (svg != null) {
        val iconSize = quality.contentFontSize
        val iconImage = svg.makeImage(session, iconSize, iconSize)
        canvas.drawImage(iconImage, x, y - quality.contentFontSize * 0.9f)
        x += iconSize + quality.lineSpace
    } else {
        logger.warn("未找到类型为 DISPUTE 的图标")
    }

    canvas.drawTextArea(disputeTitle, textCardRect, x, y, font, session.createPaint { color = Color.makeRGB(231, 139, 31) })

    return with(session) { surface.makeImageSnapshot().track() }
}


/**
 * 绘制话题模块。
 */
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
    val svg = session.createSvg("icon/TOPIC.svg")
    if (svg != null) {
        val iconSize = quality.contentFontSize
        val iconImage = svg.makeImage(session, iconSize, iconSize)
        canvas.drawImage(iconImage, x, y - quality.contentFontSize * 0.9f)
        x += iconSize + quality.lineSpace
    } else {
        logger.warn("未找到类型为 TOPIC 的图标")
    }

    val linkTextPaint = session.createPaint {
        color = theme.linkColor
        isAntiAlias = true
    }
    canvas.drawTextArea(topicName, textCardRect, x, y, font, linkTextPaint)

    return with(session) { surface.makeImageSnapshot().track() }
}


/**
 * 绘制动态正文富文本模块。
 */
suspend fun ModuleDynamic.ContentDesc.drawGeneral(session: DrawingSession): Image {
    val paragraphStyle = ParagraphStyle().apply {
        alignment = Alignment.LEFT
        textStyle = titleTextStyle
    }

    val tra = trans(text)
    val nodes = buildContentDescRenderNodes(
        richTextNodes = richTextNodes,
        translation = tra,
        cutLine = BiliConfigManager.config.translateConfig.cutLine,
    )

    val textCardHeight = useParagraph(
        paragraphStyle,
        FontUtils.fonts,
        cardContentRect.width,
        buildBlock = { addText(buildContentDescMeasureText(nodes)) },
        useBlock = { paragraph ->
            (quality.contentFontSize + quality.lineSpace * 2) * (paragraph.lineNumber + 2)
        }
    )

    val textCardRect = Rect.makeXYWH(
        quality.cardPadding.toFloat(),
        0f,
        cardContentRect.width,
        textCardHeight
    )

    var x = textCardRect.left
    var y = quality.contentFontSize + quality.lineSpace

    val surface = session.createSurface(cardRect.width.toInt(), textCardHeight.toInt())
    val canvas = surface.canvas
    val generalTextPaint = session.createPaint {
        color = theme.contentColor
        isAntiAlias = true
    }
    val linkTextPaint = session.createPaint {
        color = theme.linkColor
        isAntiAlias = true
    }

    nodes.forEach {
        when (it.type) {
            "RICH_TEXT_NODE_TYPE_TEXT" -> {
                val text = it.text.replace("\r\n", "\n").replace("\r", "\n")
                val point = canvas.drawTextArea(text, textCardRect, x, y, font, generalTextPaint)
                x = point.x
                y = point.y
            }

            "RICH_TEXT_NODE_TYPE_EMOJI" -> {
                getOrDownloadImage(it.emoji!!.iconUrl, CacheType.EMOJI)?.let { img ->
                    try {
                        val emojiSize = useTextLine("🙂", font) { it.height }

                        // emoji 需要按文本流参与换行，否则行宽计算与真实渲染会脱节。
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
                val svg = session.createSvg("icon/${it.type}.svg")
                if (svg != null) {
                    val iconSize = quality.contentFontSize
                    val iconImage = svg.makeImage(session, iconSize, iconSize)
                    canvas.drawImage(iconImage, x, y - quality.contentFontSize * 0.9f)
                    x += iconSize
                } else {
                    logger.warn("未找到类型为 ${it.type} 的图标")
                }

                val point = canvas.drawTextArea(it.text, textCardRect, x, y, font, linkTextPaint)
                x = point.x
                y = point.y
            }

            else -> {
                val point = canvas.drawTextArea(it.text, textCardRect, x, y, font, linkTextPaint)
                x = point.x
                y = point.y
            }
        }
    }
    return with(session) {
        surface.makeImageSnapshot(IRect.makeXYWH(0, 0, cardRect.width.toInt(), ceil(y + quality.lineSpace * 2).toInt()))!!.track()
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

internal fun String.toCodePointStrings(): List<String> {
    val codePoints = ArrayList<String>(length)
    var index = 0

    while (index < length) {
        val codePoint = codePointAt(index)
        codePoints += String(Character.toChars(codePoint))
        index += Character.charCount(codePoint)
    }

    return codePoints
}

/**
 * 将 emoji 文本转换为 Twemoji 所需的 code point key。
 */
internal fun String.toEmojiCodePointKey(): String {
    return buildList {
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            add(codePoint.toString(16))
            index += Character.charCount(codePoint)
        }
    }.joinToString("-")
}

/**
 * 在指定区域内按文本流规则绘制文本与 emoji。
 */
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
                for (c in node.value.toCodePointStrings()) {
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
                } else {
                    val emoji = node.value.toEmojiCodePointKey()
                    val emojiSize = useTextLine("🙂", font) { it.height }

                    var emojiImg: Image? = null
                    try {
                        var e = emoji.split("-")
                        // 非 ZWJ 场景保留去掉 FE0F 的回退形式，可提升 Twemoji 命中率。
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

/**
 * 绘制转发态作者头部信息。
 */
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
    // 这里仍使用 TextLine 的单字体单行渲染；如果昵称或副标题后续出现 emoji、组合字形或生僻字，
    // 仍可能出现方框、拆分或宽度测量偏差，届时需要改成支持字体回退的排版方案，而不是继续在这里打补丁。
    val textLineName = with(session) { TextLine.make(authorName, nameFont).track() }
    val textLineTime = with(session) { TextLine.make(normalizeSingleLineTemplateText(time), timeFont).track() }

    var x = faceSize + quality.cardPadding * 2.5f
    var y = ((faceSize - quality.nameFontSize) / 2) + quality.nameFontSize + quality.cardPadding

    canvas.drawTextLine(textLineName, x, y, session.createPaint { color = theme.nameColor })

    y -= (quality.nameFontSize - quality.subTitleFontSize) / 2
    x += textLineName.width + quality.cardPadding
    canvas.drawTextLine(textLineTime, x, y, session.createPaint { color = theme.subTitleColor })

    return with(session) { surface.makeImageSnapshot().track() }
}

/**
 * 将当前模板里只能单行展示的文本压成单行，避免控制字符在 Skia TextLine 中被绘成占位方框。
 *
 * 这次先把处理放在作者头模板入口，是因为问题目前稳定复现于直播/用户链接解析的个性签名单行渲染，
 * 且用户要求先在对应模板逻辑上止血；如果后续更多链路要复用个性签名，应把规则上移到统一展示归一化层。
 */
private fun normalizeSingleLineTemplateText(text: String): String {
    return text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("\n+"), " ")
        .filterNot(Char::isISOControl)
        .replace(Regex(" {2,}"), " ")
        .trim()
}


/**
 * 绘制普通动态作者头部信息。
 */
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
    // 这里仍使用 TextLine 的单字体单行渲染；如果昵称或副标题后续出现 emoji、组合字形或生僻字，
    // 仍可能出现方框、拆分或宽度测量偏差，届时需要改成支持字体回退的排版方案，而不是继续在这里打补丁。
    val textLineName = with(session) { TextLine.make(authorName, nameFont).track() }
    val textLineTime = with(session) { TextLine.make(normalizeSingleLineTemplateText(time), timeFont).track() }

    var x = quality.faceSize + quality.cardPadding * 3.2f
    val space = (quality.pendantSize - quality.nameFontSize - quality.subTitleFontSize) / 3
    var y = quality.nameFontSize + space * 1.25f

    canvas.drawTextLine(textLineName, x, y, session.createPaint { color = theme.nameColor })

    y += quality.subTitleFontSize + space * 0.5f
    canvas.drawTextLine(textLineTime, x, y, session.createPaint { color = theme.subTitleColor })

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


/**
 * 绘制作者区域右侧装饰，目前支持粉丝卡和二维码。
 */
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
                                        session.createPaint { color = Color.makeRGB(decorate.fan!!.color) }
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
            // 二维码挂件与粉丝卡互斥，避免在有限头像区域内叠加两种装饰造成遮挡。
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
                session.createPaint(),
                true
            )
        }
    }
}
