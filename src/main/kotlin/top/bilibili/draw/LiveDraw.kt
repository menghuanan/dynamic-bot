package top.bilibili.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.svg.SVGDOM
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.data.LiveInfo
import top.bilibili.utils.*
import top.bilibili.skia.DrawingSession
import top.bilibili.skia.SkiaManager
import kotlin.math.abs


suspend fun LiveInfo.makeDrawLive(colors: List<Int>): String {
    return SkiaManager.executeDrawing {
        val live = this@makeDrawLive.drawLive(this)
        val img = makeCardBg(this, live.height, colors) {
            it.drawImage(live, 0f, 0f)
        }
        cacheImage(img, "$uid/${liveTime.formatTime("yyyyMMddHHmmss")}.png", CacheType.DRAW_LIVE)
        // All resources automatically released when session closes
    }
}

suspend fun LiveInfo.drawLive(session: DrawingSession): Image {
    val margin = quality.cardMargin * 2

    val avatar = drawAvatar(session)
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
            .replace("{time}", liveTime.formatRelativeTime)
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
        val svg = SVGDOM(Data.makeFromBytes(loadResourceBytes("icon/LIVE.svg")))
        val badgeImage = svg.makeImage(session, quality.contentFontSize, quality.contentFontSize)
        canvas.drawBadge(
            "直播",
            font,
            theme.mainLeftBadge.fontColor,
            theme.mainLeftBadge.bgColor,
            rrect,
            Position.TOP_LEFT,
            badgeImage
        )
    }
    if (BiliConfigManager.config.imageConfig.badgeEnable.right) {
        canvas.drawBadge(liveRoomId.toString(), font, Color.WHITE, Color.makeRGB(72, 199, 240), rrect, Position.TOP_RIGHT)
    }

    canvas.drawCard(rrect)

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

suspend fun LiveInfo.drawAvatar(session: DrawingSession): Image {
    val liveFace = face
    val liveTitle = title
    val liveUname = uname
    val liveTime = liveTime
    val liveUid = uid
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
        ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText("$liveUname  ${liveTime.formatRelativeTime}").build()
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

    val color = BiliConfigManager.data.dynamic[liveUid]?.color ?: BiliConfigManager.config.imageConfig.defaultColor
    val colors = color.split(";", "；").map { Color.makeRGB(it.trim()) }.first()
    canvas.drawLiveOrnament(session, "https://live.bilibili.com/$liveRoomId", colors, liveArea)

    return with(session) {
        surface.makeImageSnapshot().track()
    }
}

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
                Paint(),
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