package top.bilibili.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.TimeDisplayMode
import top.bilibili.data.LiveInfo
import top.bilibili.utils.*
import top.bilibili.service.DrawCacheKeyService
import top.bilibili.skia.DrawingSession
import top.bilibili.skia.SkiaManager
import kotlin.math.abs


/**
 * 根据主题色列表生成直播卡片图片并缓存到磁盘。
 */
suspend fun LiveInfo.makeDrawLive(colors: List<Int>, subject: String? = null, color: String? = null): String {
    return makeDrawLive(colors.first(), generateLinearGradient(colors), subject, color)
}

/**
 * 根据主色和背景渐变生成直播卡片图片并缓存到磁盘。
 */
suspend fun LiveInfo.makeDrawLive(themeColor: Int, backgroundColors: IntArray, subject: String? = null, color: String? = null): String {
    return SkiaManager.executeDrawing {
        val live = this@makeDrawLive.drawLive(this, themeColor)
        val img = makeCardBg(this, live.height, backgroundColors) {
            it.drawImage(live, 0f, 0f)
        }
        cacheImage(img, color?.let { DrawCacheKeyService.livePath(uid, liveTime, subject, it) } ?: "$uid/${liveTime.formatTime("yyyyMMddHHmmss")}.png", CacheType.DRAW_LIVE)
        // All resources automatically released when session closes
    }
}

/**
 * 将直播信息绘制为完整卡片图像。
 */
suspend fun LiveInfo.drawLive(session: DrawingSession, qrCodeColor: Int): Image {
    val margin = quality.cardMargin * 2

    val avatar = drawAvatar(session, qrCodeColor)
    val fw = cardRect.width - quality.cardOutlineWidth / 2
    val fallbackUrl = imgApi(cover, fw.toInt(), (fw * 0.625).toInt())
    val coverImg = with(session) { getOrDownloadImageDefault(cover, fallbackUrl, CacheType.IMAGES).track() }

    val height = (avatar.height + quality.contentSpace + coverImg.height * cardRect.width / coverImg.width).toInt()

    val footerTemplate = BiliConfigManager.config.templateConfig.footer.liveFooter
    val footerParagraph = if (footerTemplate.isNotBlank()) {
        val footer = footerTemplate
            .replace("{name}", uname)
            .replace("{uid}", uid.toString())
            .replace("{id}", roomId.toString())
            .replace("{time}", liveTime.formatDisplayTime(TimeDisplayMode.ABSOLUTE))
            .replace("{type}", "直播")
            .replace("{area}", area)
        with(session) {
            ParagraphBuilder(footerParagraphStyle, FontUtils.fonts).addText(footer).build().layout(cardRect.width).track()
        }
    } else null

    val liveRoomId = roomId
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

    canvas.drawRectShadowAntiAlias(rrect.inflate(1f), theme.cardShadow)

    if (BiliConfigManager.config.imageConfig.badgeEnable.left) {
        val svg = session.createSvg("icon/LIVE.svg")
        if (svg != null) {
            val badgeImage = svg.makeImage(session, quality.contentFontSize, quality.contentFontSize)
            canvas.drawBadge(
                session,
                "直播",
                font,
                theme.mainLeftBadge.fontColor,
                theme.mainLeftBadge.bgColor,
                rrect,
                Position.TOP_LEFT,
                badgeImage
            )
        } else {
            logger.warn("未找到 LIVE 图标")
        }
    }
    if (BiliConfigManager.config.imageConfig.badgeEnable.right) {
        canvas.drawBadge(session, liveRoomId.toString(), font, Color.WHITE, Color.makeRGB(72, 199, 240), rrect, Position.TOP_RIGHT)
    }

    canvas.drawCard(session, rrect)

    var top = quality.cardMargin + quality.badgeHeight.toFloat()

    canvas.drawScaleWidthImage(avatar, cardRect.width, quality.cardMargin.toFloat(), top)
    top += avatar.height + quality.contentSpace

    val dst = RRect.makeXYWH(
        quality.cardMargin.toFloat(),
        top,
        cardRect.width - quality.cardOutlineWidth / 2,
        (cardRect.width * coverImg.height / coverImg.width)  - quality.cardOutlineWidth / 2,
        quality.cardArc
    )
    canvas.drawImageRRect(coverImg, dst)

    footerParagraph?.paint(canvas, cardRect.left, rrect.bottom + quality.cardMargin / 2)

    return with(session) {
        surface.makeImageSnapshot().track()
    }
}

/**
 * 绘制直播卡片顶部的主播信息区域。
 */
suspend fun LiveInfo.drawAvatar(session: DrawingSession, qrCodeColor: Int): Image {
    val liveFace = face
    val liveTitle = title
    val liveUname = uname
    val liveTime = liveTime
    // 预留未使用变量 liveUid: val liveUid = uid
    val liveRoomId = roomId
    val liveArea = area

    val surface = session.createSurface(
        cardRect.width.toInt(),
        (quality.faceSize + quality.cardPadding * 2f).toInt()
    )
    val canvas = surface.canvas

    // Note: drawAvatar() uses internal try-finally for resource management
    canvas.drawAvatar(session, liveFace, null, null, quality.faceSize, quality.verifyIconSize)

    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 1
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = titleTextStyle.apply {
            fontSize = quality.nameFontSize
        }
    }

    val w = cardContentRect.width - quality.pendantSize -
        if (BiliConfigManager.config.imageConfig.cardOrnament == "QrCode" ) quality.ornamentHeight else 0f

    val titleParagraph =
        ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(liveTitle).build()
            .layout(w)
    paragraphStyle.apply {
        textStyle = descTextStyle.apply {
            fontSize = quality.subTitleFontSize
        }
    }
    val timeParagraph =
        ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText("$liveUname  ${liveTime.formatDisplayTime(TimeDisplayMode.ABSOLUTE)}").build()
            .layout(w)

    with(session) {
        titleParagraph.track()
        timeParagraph.track()
    }

    val x = quality.faceSize + quality.cardPadding * 3f
    val space = (quality.pendantSize - quality.nameFontSize - quality.subTitleFontSize) / 3
    var y =  space * 1.25f

    titleParagraph.paint(canvas, x, y)

    y += quality.nameFontSize + space * 0.5f
    timeParagraph.paint(canvas, x, y)

    canvas.drawLiveOrnament(session, "https://live.bilibili.com/$liveRoomId", qrCodeColor, liveArea)



    return with(session) {
        surface.makeImageSnapshot().track()
    }
}

@Suppress("UNUSED_PARAMETER")
/**
 * 绘制直播卡片右侧装饰，目前主要用于二维码挂件。
 */
fun Canvas.drawLiveOrnament(session: DrawingSession, link: String?, qrCodeColor: Int?, label: String?) {
    when (BiliConfigManager.config.imageConfig.cardOrnament) {
        "QrCode" -> {
            val qrCodeImg = qrCode(session, link!!, quality.ornamentHeight.toInt(), qrCodeColor!!)
            val y = ((quality.faceSize - qrCodeImg.height + quality.contentSpace) / 2)
            val tarFRect = Rect.makeXYWH(
                cardRect.width - qrCodeImg.width - abs(y),
                y + quality.cardPadding,
                qrCodeImg.width.toFloat(),
                qrCodeImg.height.toFloat()
            )

            val srcFRect = Rect.makeXYWH(0f, 0f, qrCodeImg.width.toFloat(), qrCodeImg.height.toFloat())
            drawImageRect(
                qrCodeImg,
                srcFRect,
                tarFRect,
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                session.createPaint(),
                true
            )
        }
        "None" -> {}
        else -> {
            //val labelTextLine = TextLine.make(label, font.makeWithSize(quality.subTitleFontSize))
            //val y =
            //    ((quality.faceSize - quality.subTitleFontSize - quality.badgePadding * 2 + quality.contentSpace) / 2)
            //drawLabelCard(
            //    labelTextLine,
            //    cardContentRect.right - labelTextLine.width - quality.badgePadding * 4 - abs(y),
            //    y + quality.cardPadding,
            //    Paint().apply {
            //        color = theme.subLeftBadge.fontColor
            //    },
            //    Paint().apply {
            //        color = theme.subLeftBadge.bgColor
            //    }
            //)
        }
    }
}
