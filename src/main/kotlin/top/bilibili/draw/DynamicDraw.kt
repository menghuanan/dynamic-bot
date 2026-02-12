package top.bilibili.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import top.bilibili.core.BiliBiliBot
import top.bilibili.BiliConfigManager
import top.bilibili.data.*
import top.bilibili.data.DynamicType.DYNAMIC_TYPE_FORWARD
import top.bilibili.data.DynamicType.DYNAMIC_TYPE_NONE
import top.bilibili.draw.Position.*
import top.bilibili.tasker.DynamicMessageTasker.isUnlocked
import top.bilibili.utils.*
import top.bilibili.skia.DrawingSession
import top.bilibili.skia.SkiaManager


val logger by BiliBiliBot::logger

val quality: Quality by lazy {
    val imageConfig = BiliConfigManager.config.imageConfig
    var quality: Quality?
    if (BiliImageQuality.customOverload) {
        quality = BiliImageQuality.customQuality
        logger.warn("图片分辨率配置已重载")
    } else {
        quality = BiliImageQuality.quality[imageConfig.quality]
        if (quality == null) {
            logger.error("未找到 ${imageConfig.quality} 的图片分辨率配置")
            quality = BiliImageQuality.quality.firstNotNullOf { it.value }
        }
    }
    quality.apply {
        badgeHeight = if (imageConfig.badgeEnable.enable) badgeHeight else 0
    }
}

val theme: Theme by lazy {
    val imageConfig = BiliConfigManager.config.imageConfig
    var theme: Theme?
    if (BiliImageTheme.customOverload) {
        theme = BiliImageTheme.customTheme
        logger.warn("图片主题配置已重载")
    } else {
        theme = BiliImageTheme.theme[imageConfig.theme]
        if (theme == null) {
            logger.error("未找到 ${imageConfig.theme} 的图片主题配置")
            theme = BiliImageTheme.theme.firstNotNullOf { it.value }
        }
    }
    theme
}

val cardRect: Rect by lazy {
    Rect.makeLTRB(quality.cardMargin.toFloat(), 0f, quality.imageWidth - quality.cardMargin.toFloat(), 0f)
}

val cardContentRect: Rect by lazy {
    cardRect.inflate(-1f * quality.cardPadding)
}

val mainTypeface: Typeface
    get() = FontManager.mainTypeface

val font: Font
    get() = FontManager.font

val emojiTypeface: Typeface?
    get() = FontManager.emojiTypeface

val emojiFont: Font
    get() = FontManager.emojiFont

val fansCardFont: Font?
    get() = FontManager.fansCardFont

val titleTextStyle by lazy {
    TextStyle().apply {
        fontSize = quality.titleFontSize
        color = theme.titleColor
        fontFamilies = arrayOf(mainTypeface.familyName)
    }
}

val bigTitleTextStyle by lazy {
    TextStyle().apply {
        fontSize = quality.titleFontSize + 3
        color = theme.titleColor
        fontStyle = FontStyle.BOLD
        fontFamilies = arrayOf(mainTypeface.familyName)
    }
}

val descTextStyle by lazy {
    TextStyle().apply {
        fontSize = quality.descFontSize
        color = theme.descColor
        fontFamilies = arrayOf(mainTypeface.familyName)
    }
}

val contentTextStyle by lazy {
    TextStyle().apply {
        fontSize = quality.contentFontSize
        color = theme.contentColor
        fontFamilies = arrayOf(mainTypeface.familyName)
    }
}

val footerTextStyle by lazy {
    TextStyle().apply {
        fontSize = quality.footerFontSize
        color = theme.footerColor
        fontFamilies = arrayOf(mainTypeface.familyName)
    }
}

val footerParagraphStyle by lazy {
    ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = when (BiliConfigManager.config.templateConfig.footer.footerAlign.uppercase()) {
            "LEFT" -> Alignment.LEFT
            "CENTER" -> Alignment.CENTER
            "RIGHT" -> Alignment.RIGHT
            else -> Alignment.LEFT
        }
        textStyle = footerTextStyle
    }
}

val cardBadgeArc: FloatArray by lazy {
    val imageConfig = BiliConfigManager.config.imageConfig
    val left = if (imageConfig.badgeEnable.left) 0f else quality.cardArc
    val right = if (imageConfig.badgeEnable.right) 0f else quality.cardArc
    floatArrayOf(left, right, quality.cardArc, quality.cardArc)
}

val linkPaint = Paint().apply {
    color = theme.linkColor
    isAntiAlias = true
}
val generalPaint = Paint().apply {
    color = theme.contentColor
    isAntiAlias = true
}


suspend fun DynamicItem.makeDrawDynamic(colors: List<Int>): String {
    return SkiaManager.executeDrawing {
        val dynamic = this@makeDrawDynamic.drawDynamic(this, colors.first(), false)
        val img = makeCardBg(this, dynamic.height, colors) {
            it.drawImage(dynamic, 0f, 0f)
        }
        cacheImage(img, "$mid/$idStr.png", CacheType.DRAW_DYNAMIC)
        // All resources automatically released when session closes
    }
}

suspend fun DynamicItem.drawDynamic(session: DrawingSession, themeColor: Int, isForward: Boolean = false): Image {
    val orig = orig?.drawDynamic(session, themeColor, type == DYNAMIC_TYPE_FORWARD)

    var imgList = modules.makeGeneral(session, formatRelativeTime, link, type, themeColor, isForward, isUnlocked())

    // 调整附加卡片顺序
    if (orig != null) {
        imgList = if (this.modules.moduleDynamic.additional != null) {
            val result = ArrayList<Image>(imgList.size + 1)
            result.addAll(imgList.subList(0, imgList.size - 1))
            result.add(orig)
            result.add(imgList.last())
            result
        } else {
            imgList.plus(orig)
        }
    }

    var plusHeight = 0
    if (type == DynamicType.DYNAMIC_TYPE_WORD || type == DYNAMIC_TYPE_NONE) {
        plusHeight += quality.contentSpace * 2
    }

    val footer = if (!isForward) {
        buildFooter(modules.moduleAuthor.name, modules.moduleAuthor.mid, did, formatRelativeTime, type.text)
    } else null

    // assembleCard 会关闭 imgList 中的所有 Image
    return with(session) {
        imgList.assembleCard(session, did, footer, plusHeight, isForward, closeInputImages = true).track()
    }

}

fun buildFooter(name: String, uid: Long, id: String, time: String, type: String): String? {
    val footerTemplate = BiliConfigManager.config.templateConfig.footer.dynamicFooter
    return if (footerTemplate.isNotBlank()) {
        footerTemplate
            .replace("{name}", name)
            .replace("{uid}", uid.toString())
            .replace("{id}", id)
            .replace("{time}", time)
            .replace("{type}", type)
    } else null
}

/**
 * 将多个 Image 组装成一张卡片
 * @param session DrawingSession for resource management
 * @param id 动态 ID
 * @param footer 页脚文本
 * @param plusHeight 额外高度
 * @param isForward 是否为转发动态
 * @param tag 标签
 * @param closeInputImages 是否在组装完成后关闭输入的 Image 列表，默认为 false
 * @return 组装后的 Image
 */
fun List<Image>.assembleCard(session: DrawingSession, id: String, footer: String? = null, plusHeight: Int = 0, isForward: Boolean = false, tag: String? = null, closeInputImages: Boolean = false): Image {
    val imageConfig = BiliConfigManager.config.imageConfig
    val height = sumOf {
        if (it.width > cardRect.width) {
            (cardRect.width * it.height / it.width + quality.contentSpace).toInt()
        } else {
            it.height + quality.contentSpace
        }
    } + plusHeight

    val footerParagraph = if (footer != null) {
        with(session) {
            ParagraphBuilder(footerParagraphStyle, FontUtils.fonts).addText(footer).build().layout(cardRect.width).track()
        }
    } else null

    val margin = if (isForward) quality.cardPadding * 2 else quality.cardMargin * 2
    val imgList = this

    return try {
        val surface = session.createSurface(
            (cardRect.width + margin).toInt(),
            height + quality.badgeHeight + margin + (footerParagraph?.height?.toInt() ?: 0)
        )
        val canvas = surface.canvas

        val rrect = RRect.makeComplexXYWH(
            margin / 2f,
            quality.badgeHeight + margin / 2f,
            cardRect.width,
            height.toFloat(),
            cardBadgeArc
        )

        if (isForward) {
            canvas.drawRectShadowAntiAlias(rrect.inflate(1f), theme.smallCardShadow)
        } else {
            canvas.drawRectShadowAntiAlias(rrect.inflate(1f), theme.cardShadow)
        }

        if (imageConfig.badgeEnable.left) {
            val svg = loadSVG("icon/${if (isForward) "FORWARD" else "BILIBILI_LOGO"}.svg")
            val badgeImage = svg?.makeImage(session, quality.contentFontSize, quality.contentFontSize)
            canvas.drawBadge(
                tag ?: if (isForward) "转发动态" else "动态",
                font,
                theme.mainLeftBadge.fontColor,
                theme.mainLeftBadge.bgColor,
                rrect,
                TOP_LEFT,
                badgeImage
            )
        }
        if (imageConfig.badgeEnable.right) {
            canvas.drawBadge(id, font, theme.mainRightBadge.fontColor, theme.mainRightBadge.bgColor, rrect, TOP_RIGHT)
        }

        canvas.drawCard(rrect)

        var top = quality.cardMargin + quality.badgeHeight.toFloat()
        for (img in imgList) {
            canvas.drawScaleWidthImage(img, cardRect.width, quality.cardMargin.toFloat(), top)

            top += if (img.width > cardRect.width) {
                (cardRect.width * img.height / img.width + quality.contentSpace).toInt()
            } else {
                img.height + quality.contentSpace
            }
        }

        footerParagraph?.paint(canvas, cardRect.left, rrect.bottom + quality.cardMargin / 2)

        with(session) {
            surface.makeImageSnapshot().track()
        }
    } finally {
        // 如果需要关闭输入的 Image 列表
        if (closeInputImages) {
            imgList.forEach { runCatching { it.close() } }
        }
    }
}

suspend fun DynamicItem.Modules.makeGeneral(
    session: DrawingSession,
    time: String,
    link: String,
    type: DynamicType,
    themeColor: Int,
    isForward: Boolean = false,
    isUnlocked: Boolean = false
): List<Image> {
    return mutableListOf<Image>().apply {
        if (type != DYNAMIC_TYPE_NONE)
            add(if (isForward) moduleAuthor.drawForward(session, time) else moduleAuthor.drawGeneral(session, time, link, themeColor))
        if(isUnlocked){
            add(drawBlockedDefault(session))
        }else{
            moduleDispute?.drawGeneral(session)?.let { add(it) }
            addAll(moduleDynamic.makeGeneral(session, isForward))
        }
    }
}

fun drawBlockedDefault(session: DrawingSession): Image {
    val bgImg = with(session) {
        Image.makeFromEncoded(loadResourceBytes("image/Blocked_BG_Day.png")).track()
    }
    val bgWidth = cardContentRect.width - 2 * quality.cardPadding
    val bgHeight = bgImg.height.toFloat() / bgImg.width.toFloat() * bgWidth

    val textStyle = ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = Alignment.CENTER
        textStyle = titleTextStyle.apply {
            color = Color.WHITE
        }
    }
    val text = with(session) {
        ParagraphBuilder(textStyle, FontUtils.fonts)
            .addText("此动态为专属动态\n请自行查看详情内容")
            .build().layout(bgWidth).track()
    }

    val surface = session.createSurface(
        cardContentRect.width.toInt(), (bgHeight + 3.0f * quality.cardPadding).toInt()
    )
    val canvas = surface.canvas

    val x = quality.cardPadding.toFloat()
    var y = quality.cardPadding.toFloat()
    canvas.drawImageClip(session, bgImg, RRect.Companion.makeXYWH(x, y, bgWidth, bgHeight, quality.cardArc))

    y += (bgHeight - text.height) / 2.0f
    text.paint(canvas, x, y)

    return with(session) {
        surface.makeImageSnapshot().track()
    }
}

fun Rect.textVertical(text: TextLine) =
    bottom - (height - text.capHeight) / 2

fun Canvas.drawCard(rrect: RRect, bgColor: Int = theme.cardBgColor) {
    drawRRect(rrect, Paint().apply {
        color = bgColor
        mode = PaintMode.FILL
        isAntiAlias = true
    })
    drawRRect(rrect, Paint().apply {
        color = theme.cardOutlineColors.first()
        mode = PaintMode.STROKE
        strokeWidth = quality.cardOutlineWidth
        isAntiAlias = true
        shader = Shader.makeSweepGradient(
            rrect.left + rrect.width / 2,
            rrect.top + rrect.height / 2,
            theme.cardOutlineColors
        )
    })
}

fun makeCardBg(session: DrawingSession, height: Int, colors: List<Int>, block: (Canvas) -> Unit): Image {
    val imageRect = Rect.makeXYWH(0f, 0f, quality.imageWidth.toFloat(), height.toFloat())
    val surface = session.createSurface(imageRect.width.toInt(), height)
    val canvas = surface.canvas

    canvas.drawRect(imageRect, Paint().apply {
        shader = Shader.makeLinearGradient(
            Point(imageRect.left, imageRect.top),
            Point(imageRect.right, imageRect.bottom),
            generateLinearGradient(colors)
        )
    })
    block(canvas)

    return with(session) {
        surface.makeImageSnapshot().track()
    }
}

suspend fun Canvas.drawAvatar(
    session: DrawingSession,
    face: String,
    pendant: String?,
    verifyType: Int?,
    faceSize: Float,
    verifyIconSize: Float,
    isForward: Boolean = false
) {

    val faceImg = getOrDownloadImage(face, CacheType.USER)

    val hasPendant = pendant != null && pendant != ""

    var tarFaceRect = RRect.makeXYWH(
        quality.cardPadding * if (isForward) 1.5f else 1.8f,
        quality.cardPadding * if (isForward) 1f else 1.2f,
        faceSize,
        faceSize,
        faceSize / 2
    )
    if (!hasPendant) {
        tarFaceRect = tarFaceRect.inflate(quality.noPendantFaceInflate) as RRect
        drawCircle(
            tarFaceRect.left + tarFaceRect.width / 2,
            tarFaceRect.top + tarFaceRect.width / 2,
            tarFaceRect.width / 2 + quality.noPendantFaceInflate / 2,
            Paint().apply { color = theme.faceOutlineColor })
    }

    faceImg?.let {
        try {
            drawImageRRect(it, tarFaceRect)
        } finally {
            it.close()
        }
    }

    if (hasPendant) {
        getOrDownloadImage(pendant!!, CacheType.USER)?.let { pendantImg ->
            try {
                val srcPendantRect = Rect(0f, 0f, pendantImg.width.toFloat(), pendantImg.height.toFloat())
                val tarPendantRect = Rect.makeXYWH(
                    tarFaceRect.left + tarFaceRect.width / 2 - quality.pendantSize / 2,
                    tarFaceRect.top + tarFaceRect.height / 2 - quality.pendantSize / 2,
                    quality.pendantSize, quality.pendantSize
                )
                drawImageRect(
                    pendantImg,
                    srcPendantRect,
                    tarPendantRect,
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                    null,
                    true
                )
            } finally {
                pendantImg.close()
            }
        }
    }

    val verifyIcon = when (verifyType) {
        0 -> "PERSONAL_OFFICIAL_VERIFY"
        1 -> "ORGANIZATION_OFFICIAL_VERIFY"
        else -> ""
    }

    if (verifyIcon != "") {
        val svg = loadSVG("icon/$verifyIcon.svg")
        if (svg != null) {
            val size = if (hasPendant) verifyIconSize - quality.noPendantFaceInflate / 2 else verifyIconSize
            val verifyImg = svg.makeImage(session, size, size)
            drawImage(
                verifyImg,
                tarFaceRect.right - size,
                tarFaceRect.bottom - size
            )
        }
    }
}

fun Canvas.drawBadge(
    text: String,
    font: Font,
    fontColor: Int,
    bgColor: Int,
    cardRect: Rect,
    position: Position,
    icon: Image? = null
) {

    val textLine = TextLine.make(text, font)

    try {
        val badgeWidth = textLine.width + quality.badgePadding * 8 + (icon?.width ?: 0)

        val rrect = when (position) {
            TOP_LEFT -> RRect.makeXYWH(
                cardRect.left, cardRect.top - quality.badgeHeight, badgeWidth,
                quality.badgeHeight.toFloat(), quality.badgeArc, quality.badgeArc, 0f, 0f
            )

            TOP_RIGHT -> RRect.makeXYWH(
                cardRect.right - badgeWidth, cardRect.top - quality.badgeHeight, badgeWidth,
                quality.badgeHeight.toFloat(), quality.badgeArc, quality.badgeArc, 0f, 0f
            )

            BOTTOM_LEFT -> RRect.makeXYWH(
                cardRect.left, cardRect.bottom + quality.badgeHeight, badgeWidth,
                quality.badgeHeight.toFloat(), 0f, 0f, quality.badgeArc, quality.badgeArc
            )

            BOTTOM_RIGHT -> RRect.makeXYWH(
                cardRect.right - badgeWidth, cardRect.bottom + quality.badgeHeight, badgeWidth,
                quality.badgeHeight.toFloat(), 0f, 0f, quality.badgeArc, quality.badgeArc
            )
        }

        drawRectShadowAntiAlias(rrect.inflate(1f), theme.smallCardShadow)

        drawCard(rrect, bgColor)

        var x = rrect.left + quality.badgePadding * 4
        if (icon != null) {
            x -= quality.badgePadding
            drawImage(icon, x, rrect.top + (quality.badgeHeight - icon.height) / 2)
            x += icon.width + quality.badgePadding * 2
        }

        drawTextLine(
            textLine,
            x,
            rrect.bottom - (quality.badgeHeight - textLine.capHeight) / 2,
            Paint().apply { color = fontColor })
    } finally {
        textLine.close()
    }

}

fun Canvas.drawLabelCard(
    textLine: TextLine,
    x: Float,
    y: Float,
    fontPaint: Paint,
    bgPaint: Paint
) {

    val rrect = RRect.makeXYWH(
        x,
        y,
        textLine.width + quality.badgePadding * 4,
        textLine.height,
        quality.badgeArc
    )
    drawRRect(rrect, bgPaint)

    drawTextLine(
        textLine,
        rrect.left + quality.badgePadding * 2,
        rrect.bottom - quality.badgePadding,
        //rrect.textVertical(textLine),
        fontPaint
    )
}

