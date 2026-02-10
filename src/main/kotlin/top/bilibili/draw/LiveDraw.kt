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
import kotlin.math.abs


suspend fun LiveInfo.makeDrawLive(colors: List<Int>): String {
    val live = drawLive()
    val img = makeCardBg(live.height, colors) {
        it.drawImage(live, 0f, 0f)
    }
    // 关闭中间 Image，释放原生内存
    return try {
        cacheImage(img, "$uid/${liveTime.formatTime("yyyyMMddHHmmss")}.png", CacheType.DRAW_LIVE)
    } finally {
        live.close()
        img.close()
    }
}

suspend fun LiveInfo.drawLive(): Image {
    val margin = quality.cardMargin * 2

    val avatar = drawAvatar()
    val fw = cardRect.width - quality.cardOutlineWidth / 2
    val fallbackUrl = imgApi(cover, fw.toInt(), (fw * 0.625).toInt())
    val coverImg = getOrDownloadImageDefault(cover, fallbackUrl, CacheType.IMAGES)

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
        ParagraphBuilder(footerParagraphStyle, FontUtils.fonts).addText(footer).build().layout(cardRect.width)
    } else null

    val liveRoomId = roomId
    return try {
        createImage(
            (cardRect.width + margin).toInt(),
            height + quality.badgeHeight + margin + (footerParagraph?.height?.toInt() ?: 0)
        ) { canvas ->
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
                val badgeImage = svg.makeImage(quality.contentFontSize, quality.contentFontSize)
                canvas.drawBadge(
                    "直播",
                    font,
                    theme.mainLeftBadge.fontColor,
                    theme.mainLeftBadge.bgColor,
                    rrect,
                    Position.TOP_LEFT,
                    badgeImage
                )
                // 关闭 badge Image
                badgeImage.close()
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
        }
    } finally {
        // 关闭 Image，释放原生内存
        avatar.close()
        coverImg.close()
    }
}

suspend fun LiveInfo.drawAvatar(): Image {
    val liveFace = face
    val liveTitle = title
    val liveUname = uname
    val liveTime = liveTime
    val liveUid = uid
    val liveRoomId = roomId
    val liveArea = area
    return createImage(
        cardRect.width.toInt(),
        (quality.faceSize + quality.cardPadding * 2f).toInt()
    ) { canvas ->
        canvas.drawAvatar(liveFace, null, null, quality.faceSize, quality.verifyIconSize)

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

        val x = quality.faceSize + quality.cardPadding * 3f
        val space = (quality.pendantSize - quality.nameFontSize - quality.subTitleFontSize) / 3
        var y =  space * 1.25f

        titleParagraph.paint(canvas, x, y)

        y += quality.nameFontSize + space * 0.5f
        timeParagraph.paint(canvas, x, y)

        val color = BiliConfigManager.data.dynamic[liveUid]?.color ?: BiliConfigManager.config.imageConfig.defaultColor
        val colors = color.split(";", "；").map { Color.makeRGB(it.trim()) }.first()
        canvas.drawLiveOrnament("https://live.bilibili.com/$liveRoomId", colors, liveArea)
    }
}

fun Canvas.drawLiveOrnament(link: String?, qrCodeColor: Int?, label: String?) {
    when (BiliConfigManager.config.imageConfig.cardOrnament) {
        "QrCode" -> {
            val qrCodeImg = qrCode(link!!, quality.ornamentHeight.toInt(), qrCodeColor!!)
            try {
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
            } finally {
                qrCodeImg.close()
            }
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