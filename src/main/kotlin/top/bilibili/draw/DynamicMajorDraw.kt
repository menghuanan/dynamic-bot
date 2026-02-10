package top.bilibili.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import top.bilibili.BiliConfigManager
import top.bilibili.data.ModuleDynamic
import top.bilibili.utils.*
import kotlin.math.ceil


private fun formatPgcEvaluateText(
    rawText: String,
    textAreaWidth: Float,
    fontSize: Float
): String {
    val normalizedText = rawText.replace("\r\n", "\n")
    val sizedFont = font.makeWithSize(fontSize)

    fun textWidth(s: String): Float = TextLine.make(s, sizedFont).width

    fun wrapByWidth(text: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            if (text[index] == '\n') {
                result.add("")
                index++
                continue
            }

            val nextNewline = text.indexOf('\n', index).let { if (it == -1) text.length else it }
            val available = nextNewline - index
            if (available <= 0) {
                index = nextNewline + 1
                continue
            }

            var low = 1
            var high = available
            var best = 1

            while (low <= high) {
                val mid = (low + high) ushr 1
                val candidate = text.substring(index, index + mid)
                if (textWidth(candidate) <= textAreaWidth) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }

            result.add(text.substring(index, index + best))
            index += best

            if (index == nextNewline && index < text.length && text[index] == '\n') {
                index++
            }
        }
        return result
    }

    val lines = wrapByWidth(normalizedText)

    if (lines.size < 5) return lines.joinToString("\n")

    val firstSix = lines.take(5).toMutableList()
    val sixthLine = firstSix[4]
    firstSix[4] = if (sixthLine.length >= 15) sixthLine.substring(0, 14) + "…" else sixthLine
    return firstSix.joinToString("\n")
}

suspend fun ModuleDynamic.Major.makeGeneral(isForward: Boolean = false): Image {
    return when (type) {
        "MAJOR_TYPE_ARCHIVE" -> if (isForward) archive!!.drawSmall() else archive!!.drawGeneral()
        "MAJOR_TYPE_BLOCKED" -> blocked!!.drawGeneral()
        "MAJOR_TYPE_DRAW" -> draw!!.drawGeneral()
        "MAJOR_TYPE_ARTICLE" -> article!!.drawGeneral()
        "MAJOR_TYPE_MUSIC" -> music!!.drawGeneral()
        "MAJOR_TYPE_LIVE" -> live!!.drawGeneral()
        "MAJOR_TYPE_LIVE_RCMD" -> liveRcmd!!.drawGeneral()
        "MAJOR_TYPE_PGC" -> pgc!!.drawSmall()
        "MAJOR_TYPE_UGC_SEASON" -> ugcSeason!!.drawSmall()
        "MAJOR_TYPE_COMMON" -> common!!.drawGeneral()
        "MAJOR_TYPE_OPUS" -> opus!!.drawGeneral()
        "MAJOR_TYPE_NONE" -> drawInfoText(none?.tips!!)
        else -> drawInfoText("无法绘制类型为 [$type] 的动态类型, 请把动态链接反馈给开发者")
    }
}

fun drawInfoText(text: String): Image {
    val lineCount = if (TextLine.make(text, font).width / cardContentRect.width > 1) 2 else 1
    return createImage(
        cardRect.width.toInt(),
        quality.contentFontSize.toInt() * lineCount + quality.badgeHeight + quality.cardPadding
    ) { canvas ->
        val paragraphStyle = ParagraphStyle().apply {
            alignment = Alignment.LEFT
            textStyle = contentTextStyle
        }
        val contentParagraph =
            ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(text).build().layout(cardContentRect.width)
        contentParagraph.paint(
            canvas,
            quality.cardPadding.toFloat(),
            quality.contentFontSize + quality.cardPadding / 2
        )
    }
}

suspend fun ModuleDynamic.Major.Opus.drawGeneral(): Image {

    val desc = summary.drawGeneral()
    val draw = if (pics.isNotEmpty()) {
        val imgs = pics.map {
            ModuleDynamic.Major.Draw.DrawItem(it.width, it.height, it.src)
        }
        ModuleDynamic.Major.Draw(0L, imgs).drawGeneral()
    } else null

    val paragraphStyle = ParagraphStyle().apply {
        alignment = Alignment.LEFT
        textStyle = bigTitleTextStyle
    }
    val safeTitle = title.orEmpty()
    val contentParagraph = if (safeTitle.isNotBlank()) {
        ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(safeTitle).build().layout(cardContentRect.width)
    } else null

    val h =
        if (contentParagraph != null) contentParagraph.lineNumber * quality.contentFontSize.toInt() + quality.cardPadding else 0

    return try {
        createImage(
            cardRect.width.toInt(),
            desc.height + (draw?.height ?: 0) + h
        ) { canvas ->
            contentParagraph?.let {
                contentParagraph.paint(
                    canvas,
                    quality.cardPadding.toFloat(),
                    0f
                )
            }

            canvas.drawImage(desc, 0f, h.toFloat())
            draw?.let {
                canvas.drawImage(draw, 0f, h.toFloat() + desc.height)
            }
        }
    } finally {
        // 关闭中间 Image，释放原生内存
        desc.close()
        draw?.close()
    }
}

suspend fun ModuleDynamic.Major.Common.drawGeneral(): Image {

    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 1
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = titleTextStyle.apply {
            fontSize = quality.titleFontSize * 0.8f
        }
    }

    val height = quality.additionalCardHeight.toFloat()

    val commonCardRect = RRect.makeXYWH(
        quality.cardPadding.toFloat(),
        1f,
        cardContentRect.width,
        height,
        quality.cardArc
    )

    val coverImg = getOrDownloadImage(cover, CacheType.OTHER)

    return try {
        createImage(
            cardRect.width.toInt(),
            (height + quality.cardPadding).toInt()
        ) { canvas ->
            canvas.drawCard(commonCardRect)
            canvas.drawRectShadowAntiAlias(commonCardRect.inflate(1f), theme.smallCardShadow)

            if (badge.text.isNotBlank()) {
                val labelTextLine = TextLine.make(badge.text, font.makeWithSize(quality.subTitleFontSize))

                canvas.drawLabelCard(
                    labelTextLine,
                    commonCardRect.right - labelTextLine.width - quality.badgePadding * 4 - quality.cardPadding,
                    1 + (height - labelTextLine.height) / 2,
                    Paint().apply {
                        color = Color.makeRGB(badge.color)
                    },
                    Paint().apply {
                        color = Color.makeRGB(badge.bgColor)
                    }
                )
            }

            var x = quality.cardPadding.toFloat()

            coverImg?.let { img ->
                val imgRect = RRect.makeXYWH(
                    quality.cardPadding.toFloat(),
                    1f,
                    quality.additionalCardHeight.toFloat() * img.width / img.height,
                    quality.additionalCardHeight.toFloat(),
                    quality.cardArc
                ).inflate(-1f) as RRect
                canvas.drawImageRRect(img, imgRect)
                x += imgRect.width + quality.cardPadding
            }

            val titleParagraph =
                ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(title).build()
                    .layout(cardContentRect.width - x)
            paragraphStyle.apply {
                textStyle = descTextStyle.apply {
                    fontSize = quality.subTitleFontSize * 0.8f
                }
            }
            val desc1Paragraph =
                ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(desc).build()
                    .layout(cardContentRect.width - x)
            val desc2Paragraph =
                if (label.isNotBlank()) ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(label).build()
                    .layout(cardContentRect.width - x) else null

            val top = (commonCardRect.height - (titleParagraph.height * 3)) / 2

            var y = commonCardRect.top + top + if (label.isBlank()) titleParagraph.height / 4 else 0f
            titleParagraph.paint(canvas, x, y)

            y += titleParagraph.height + if (label.isBlank()) titleParagraph.height / 2 else 0f
            desc1Paragraph.paint(canvas, x, y)

            if (desc2Paragraph != null) {
                y += titleParagraph.height
                desc2Paragraph.paint(canvas, x, y)
            }
        }
    } finally {
        coverImg?.close()
    }
}

suspend fun ModuleDynamic.Major.Archive.drawGeneral(showStat: Boolean = false): Image {

    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = titleTextStyle
    }

    val paragraphWidth = cardContentRect.width - quality.cardPadding

    val titleParagraph = ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(title).build().layout(paragraphWidth)

    paragraphStyle.apply {
        maxLinesCount = 3
        textStyle = descTextStyle
    }

    val descParagraph =
        ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText((desc?:"").replace("\r\n", " ").replace("\n", " ")).build()
            .layout(paragraphWidth)

    val fallbackUrl = imgApi(cover, cardContentRect.width.toInt(), (cardContentRect.width * 0.625).toInt())
    val coverImg = getOrDownloadImageDefault(cover, fallbackUrl, CacheType.IMAGES)

    val videoCoverHeight = cardContentRect.width * coverImg.height / coverImg.width
    val videoCardHeight = videoCoverHeight + titleParagraph.height + descParagraph.height + quality.cardPadding

    val videoCardRect = RRect.makeComplexXYWH(
        quality.cardPadding.toFloat(),
        quality.badgeHeight + 1f,
        cardContentRect.width,
        videoCardHeight,
        cardBadgeArc
    )

    return try {
        createImage(
            cardRect.width.toInt(),
            videoCardHeight.toInt() + quality.badgeHeight + quality.cardPadding
        ) { canvas ->
        // 绘制卡片背景
        canvas.drawCard(videoCardRect)
        // 卡片阴影
        canvas.drawRectShadowAntiAlias(videoCardRect.inflate(1f), theme.smallCardShadow)

        // 封面
        val coverRRect = RRect.makeComplexXYWH(
            videoCardRect.left,
            videoCardRect.top,
            videoCardRect.width,
            videoCoverHeight,
            cardBadgeArc
        ).inflate(-1f) as RRect
        canvas.drawImageRRect(coverImg, coverRRect)

        // 徽章
        if (BiliConfigManager.config.imageConfig.badgeEnable.left) {
            canvas.drawBadge(
                badge.text, font, theme.subLeftBadge.fontColor, theme.subLeftBadge.bgColor, videoCardRect,
                Position.TOP_LEFT
            )
        } else {
            val labelTextLine = TextLine.make(badge.text, font.makeWithSize(quality.subTitleFontSize))

            canvas.drawLabelCard(
                labelTextLine,
                videoCardRect.right - labelTextLine.width - quality.badgePadding * 4 - quality.cardPadding * 1.3f,
                videoCardRect.top + quality.cardPadding,
                Paint().apply { color = Color.makeRGB(badge.color) },
                Paint().apply { color = Color.makeRGB(badge.bgColor) }
            )
        }
        if (BiliConfigManager.config.imageConfig.badgeEnable.right) {
            canvas.drawBadge(
                "av$aid  |  $bvid",
                font,
                theme.subRightBadge.fontColor,
                theme.subRightBadge.bgColor,
                videoCardRect,
                Position.TOP_RIGHT
            )
        }

        // 封面遮罩
        val coverMaskRRect = RRect.makeComplexLTRB(
            coverRRect.left,
            coverRRect.bottom - videoCoverHeight * 0.2f,
            coverRRect.right,
            coverRRect.bottom,
            cardBadgeArc
        )
        canvas.drawRRect(coverMaskRRect, Paint().apply {
            color = Color.BLACK
            alpha = 120
            shader = Shader.makeLinearGradient(
                Point(coverMaskRRect.left, coverMaskRRect.bottom),
                Point(coverMaskRRect.left, coverMaskRRect.top),
                intArrayOf(0xFF000000.toInt(), 0x00000000.toInt())
            )
        })

        val durationTextLine = TextLine.make(durationText, font)
        val combinedText = if (showStat) {
            val play = stat.play.toInt()
            var playStr = play.toString()
            if (play > 10000) {
                playStr = "%.1f".format(play / 10000f) + "万"
            }
            TextLine.make("时长 $durationText  ${playStr}观看 ${stat.danmaku}弹幕", font)
        } else {
            TextLine.make("时长 $durationText", font)
        }

        val textX = coverMaskRRect.left + quality.cardPadding * 1.3f
        val textY = coverRRect.bottom - combinedText.height - quality.cardPadding
        canvas.drawLabelCard(
            combinedText,
            textX,
            textY,
            Paint().apply {
                color = Color.WHITE
            },
            Paint().apply {
                color = Color.BLACK
                alpha = 140
            }
        )

        titleParagraph.paint(
            canvas,
            quality.cardPadding * 1.5f,
            quality.badgeHeight + videoCoverHeight + quality.cardPadding / 2
        )

        descParagraph.paint(
            canvas,
            quality.cardPadding * 1.5f,
            quality.badgeHeight + videoCoverHeight + quality.cardPadding / 2 + titleParagraph.height
        )
    }
    } finally {
        coverImg.close()
    }
}

suspend fun ModuleDynamic.Major.Live.drawGeneral(): Image {
    return drawLiveSmallCard(title, "$descFirst $descSecond", cover, badge.text, "$id", null)
}

suspend fun ModuleDynamic.Major.LiveRcmd.drawGeneral(): Image {
    val info = liveInfo.livePlayInfo
    return drawLiveSmallCard(
        info.title,
        "${info.parentAreaName} · ${info.areaName}",
        info.cover,
        when (info.liveStatus) {
            0 -> "未开播"
            1 -> "直播中"
            2 -> "轮播中"
            else -> "直播"
        },
        "${info.roomId}",
        null
    )
}

suspend fun ModuleDynamic.Major.Archive.drawSmall(): Image {
    return drawSmallCard(title, desc, cover, badge.text, "av$aid", durationText)
}

suspend fun ModuleDynamic.Major.Pgc.drawSmall(): Image {
    return drawPgcCard(
        title = title,
        cover = cover,
        areas = areas,
        pubTime = pubTime,
        isFinish = isFinish,
        total = total,
        epid = epid,
        play = stat.play,
        danmaku = stat.danmaku,
        evaluate = evaluate,
        badge = badge.text,
        rating = rating
    )
}

suspend fun drawPgcCard(
    title: String,
    cover: String,
    areas: String?,
    pubTime: String?,
    isFinish: Int?,
    total: Int?,
    epid: Int,
    play: String,
    danmaku: String,
    evaluate: String?,
    badge: String,
    rating: Float?
): Image {
    // 下载封面图
    val desiredCoverWidth = cardContentRect.width * 0.4f
    val fallbackUrl = imgApi(cover, desiredCoverWidth.toInt(), 100)
    val coverImg = getOrDownloadImageDefault(cover, fallbackUrl, CacheType.IMAGES)

    // 计算封面等比缩放后的高度
    val scale = desiredCoverWidth / coverImg.width.toFloat()
    val scaledCoverHeight = coverImg.height.toFloat() * scale

    // 右侧文本区域宽度
    val textAreaWidth = cardContentRect.width - quality.cardPadding - desiredCoverWidth

    // 第一行：标题（加粗）
    val titleStyle = ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = bigTitleTextStyle
    }
    val titleParagraph = ParagraphBuilder(titleStyle, FontUtils.fonts)
        .addText(title)
        .build()
        .layout(textAreaWidth)

    // 第二行：开播时间
    val broadcastTime = if (pubTime != null && pubTime.isNotBlank()) {
        "开播时间：$pubTime"
    } else {
        "开播时间：未知"
    }
    val broadcastStyle = ParagraphStyle().apply {
        maxLinesCount = 1
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = descTextStyle.apply {
            fontSize = quality.subTitleFontSize * 0.9f
        }
    }
    val broadcastParagraph = ParagraphBuilder(broadcastStyle, FontUtils.fonts)
        .addText(broadcastTime)
        .build()
        .layout(textAreaWidth)

    // 第三行：状态 + 评分
    val ratingStr = rating?.let { "  评分%.1f".format(it) } ?: ""
    val statusText = buildString {
        append("状态：")
        if (isFinish != null && isFinish == 1) {
            append("已完结")
        } else {
            append("连载中")
        }
        if (total != null && total > 0) {
            append("  全${total}集")
        }
        append(ratingStr)
    }
    val statusStyle = ParagraphStyle().apply {
        maxLinesCount = 1
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = descTextStyle.apply {
            fontSize = quality.subTitleFontSize * 0.9f
        }
    }
    val statusParagraph = ParagraphBuilder(statusStyle, FontUtils.fonts)
        .addText(statusText)
        .build()
        .layout(textAreaWidth)

    // 第四行：播放数、弹幕数（格式化为万）
    val playNum = play.toIntOrNull() ?: 0
    val playStr = if (playNum > 10000) {
        "%.1f万".format(playNum / 10000f)
    } else {
        playNum.toString()
    }
    val danmakuNum = danmaku.toIntOrNull() ?: 0
    val danmakuStr = if (danmakuNum > 10000) {
        "%.1f万".format(danmakuNum / 10000f)
    } else {
        danmakuNum.toString()
    }
    val statInfo = "${playStr} 播放 · ${danmakuStr} 弹幕"
    val statStyle = ParagraphStyle().apply {
        maxLinesCount = 1
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = descTextStyle.apply {
            fontSize = quality.subTitleFontSize * 0.9f
        }
    }
    val statParagraph = ParagraphBuilder(statStyle, FontUtils.fonts)
        .addText(statInfo)
        .build()
        .layout(textAreaWidth)

    // 第五行：介绍
    val evaluateText = evaluate?.takeIf { it.isNotBlank() } ?: ""
    val evaluateTextStyle = descTextStyle.apply {
        fontSize = quality.subTitleFontSize * 0.85f
    }
    val evaluateTextForRender = if (evaluateText.isNotEmpty()) {
        formatPgcEvaluateText(evaluateText, textAreaWidth, evaluateTextStyle.fontSize)
    } else {
        ""
    }
    val evaluateStyle = ParagraphStyle().apply {
        maxLinesCount = 5
        ellipsis = ""
        alignment = Alignment.LEFT
        textStyle = evaluateTextStyle
    }
    val evaluateParagraph = if (evaluateTextForRender.isNotEmpty()) {
        ParagraphBuilder(evaluateStyle, FontUtils.fonts)
            .addText(evaluateTextForRender)
            .build()
            .layout(textAreaWidth)
    } else null

    // 计算总高度：封面高度和文本高度的最大值
    val textTotalHeight = titleParagraph.height + broadcastParagraph.height + statusParagraph.height +
        statParagraph.height + (evaluateParagraph?.height ?: 0f) + quality.cardPadding * 4  // 行间距
    val contentHeight = maxOf(scaledCoverHeight, textTotalHeight)

    // 调整为标准间距，移除过大的垂直内边距
    val cardHeight = quality.badgeHeight + quality.badgePadding + contentHeight + quality.cardPadding

    return try {
        createImage(
            cardRect.width.toInt(),
            cardHeight.toInt()
        ) { canvas ->
        // 绘制卡片背景（使用标准起始位置）
        val videoCardRect = RRect.makeComplexXYWH(
            quality.cardPadding.toFloat(),
            quality.badgeHeight.toFloat() + quality.badgePadding,
            cardContentRect.width,
            contentHeight,
            cardBadgeArc
        )
        canvas.drawCard(videoCardRect)
        canvas.drawRectShadowAntiAlias(videoCardRect.inflate(1f), theme.smallCardShadow)

        // 绘制徽章
        if (BiliConfigManager.config.imageConfig.badgeEnable.left) {
            canvas.drawBadge(
                badge,
                font,
                theme.subLeftBadge.fontColor,
                theme.subLeftBadge.bgColor,
                videoCardRect,
                Position.TOP_LEFT
            )
        }
        if (BiliConfigManager.config.imageConfig.badgeEnable.right) {
            canvas.drawBadge(
                "ep$epid",
                font,
                theme.subRightBadge.fontColor,
                theme.subRightBadge.bgColor,
                videoCardRect,
                Position.TOP_RIGHT
            )
        }

        // 绘制封面（在卡片内垂直居中）
        val coverTopOffset = (videoCardRect.height - scaledCoverHeight) / 2
        val coverRRect = RRect.makeComplexXYWH(
            videoCardRect.left,
            videoCardRect.top + coverTopOffset,
            desiredCoverWidth,
            scaledCoverHeight,
            cardBadgeArc
        ).inflate(-1f) as RRect
        canvas.drawImageRRect(coverImg, coverRRect)

        // 右侧文本区域起始 X 坐标
        val textX = coverRRect.right + quality.cardPadding
        var textY = coverRRect.top + quality.cardPadding / 2

        // 第一行：标题（加粗，与封面顶部齐平）
        titleParagraph.paint(canvas, textX, textY)
        textY += titleParagraph.height + quality.cardPadding / 2

        // 第二行：开播时间
        broadcastParagraph.paint(canvas, textX, textY)
        textY += broadcastParagraph.height + quality.cardPadding / 2

        // 第三行：状态
        statusParagraph.paint(canvas, textX, textY)
        textY += statusParagraph.height + quality.cardPadding / 2

        // 第四行：统计信息（带渐变背景边框）
        val statRRect = RRect.makeXYWH(
            textX,
            textY,
            statParagraph.longestLine + quality.badgePadding * 2,
            statParagraph.height + quality.badgePadding,
            quality.badgeArc / 2
        )
        // 绘制渐变背景（蓝色到浅蓝色）
        canvas.drawRRect(statRRect, Paint().apply {
            shader = Shader.makeLinearGradient(
                Point(statRRect.left, statRRect.top),
                Point(statRRect.right, statRRect.top),
                intArrayOf(
                    Color.makeRGB(100, 150, 230),  // 蓝色
                    Color.makeRGB(180, 210, 250)   // 浅蓝色
                )
            )
            alpha = 50
        })
        // 绘制边框
        canvas.drawRRect(statRRect, Paint().apply {
            color = Color.TRANSPARENT
            mode = PaintMode.STROKE
            strokeWidth = 1f
            color = theme.titleColor
            alpha = 100
        })
        statParagraph.paint(canvas, textX + quality.badgePadding, textY + quality.badgePadding / 2)
        textY += statRRect.height + quality.cardPadding / 2

        // 第五行：介绍
        evaluateParagraph?.paint(canvas, textX, textY)
    }
    } finally {
        coverImg.close()
    }
}

suspend fun drawLiveSmallCard(
    title: String,
    desc: String?,
    cover: String,
    lbadge: String,
    rbadge: String,
    duration: String?
): Image {
    val desiredCoverWidth = cardContentRect.width
    val fallbackUrl = imgApi(cover, desiredCoverWidth.toInt(), 100)
    val coverImg = getOrDownloadImageDefault(cover, fallbackUrl, CacheType.IMAGES)

    val scale = desiredCoverWidth / coverImg.width.toFloat()
    val scaledCoverHeight = coverImg.height.toFloat() * scale

    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = titleTextStyle
    }
    val titleParagraph = ParagraphBuilder(paragraphStyle, FontUtils.fonts)
        .addText(title)
        .build()
        .layout(cardContentRect.width - quality.cardPadding * 2)

    paragraphStyle.apply {
        maxLinesCount = if (titleParagraph.lineNumber == 1) 3 else 2
        textStyle = descTextStyle
    }
    val descParagraph = ParagraphBuilder(paragraphStyle, FontUtils.fonts)
        .addText(desc ?: "")
        .build()
        .layout(cardContentRect.width - quality.cardPadding * 2)

    val contentHeight = scaledCoverHeight + titleParagraph.height + descParagraph.height + quality.cardPadding
    val cardHeight = quality.badgeHeight + contentHeight + quality.cardPadding

    return try {
        createImage(
            cardRect.width.toInt(),
            cardHeight.toInt()
        ) { canvas ->
        val videoCardRect = RRect.makeComplexXYWH(
            quality.cardPadding.toFloat(),
            quality.badgeHeight + 1f,
            cardContentRect.width,
            cardHeight - (quality.badgeHeight + quality.cardPadding),
            cardBadgeArc
        )
        canvas.drawCard(videoCardRect)
        canvas.drawRectShadowAntiAlias(videoCardRect.inflate(1f), theme.smallCardShadow)

        if (BiliConfigManager.config.imageConfig.badgeEnable.left) {
            canvas.drawBadge(
                lbadge,
                font,
                theme.subLeftBadge.fontColor,
                theme.subLeftBadge.bgColor,
                videoCardRect,
                Position.TOP_LEFT
            )
        }
        if (BiliConfigManager.config.imageConfig.badgeEnable.right) {
            canvas.drawBadge(
                rbadge,
                font,
                theme.subRightBadge.fontColor,
                theme.subRightBadge.bgColor,
                videoCardRect,
                Position.TOP_RIGHT
            )
        }

        val coverRRect = RRect.makeComplexXYWH(
            videoCardRect.left,
            videoCardRect.top,
            cardContentRect.width,
            scaledCoverHeight,
            cardBadgeArc
        ).inflate(-1f) as RRect
        canvas.drawImageRRect(coverImg, coverRRect)

        val titleY = coverRRect.bottom + quality.cardPadding / 2
        titleParagraph.paint(
            canvas,
            quality.cardPadding * 1.5f,
            titleY
        )
        descParagraph.paint(
            canvas,
            quality.cardPadding * 1.5f,
            titleY + titleParagraph.height
        )

        if (duration != null) {
            val durationTextLine = TextLine.make(duration, font.makeWithSize(quality.subTitleFontSize))
            canvas.drawLabelCard(
                durationTextLine,
                coverRRect.left + quality.badgePadding * 2,
                coverRRect.bottom - durationTextLine.height - quality.badgePadding * 2,
                Paint().apply { color = Color.WHITE },
                Paint().apply {
                    color = Color.BLACK
                    alpha = 130
                }
            )
        }
    }
    } finally {
        coverImg.close()
    }
}

suspend fun drawSmallCard(
    title: String,
    desc: String?,
    cover: String,
    lbadge: String,
    rbadge: String,
    duration: String?
): Image {
    // 1) 先把需要的图片下载好，拿到它的实际宽高
    val desiredCoverWidth = cardContentRect.width * 0.4f
    val fallbackUrl = imgApi(cover, desiredCoverWidth.toInt(), 100)
    val coverImg = getOrDownloadImageDefault(cover, fallbackUrl, CacheType.IMAGES)

    // 2) 根据图片原始宽高和想要的 cover 显示宽度，计算出它等比缩放后的显示高度
    val originWidth = coverImg.width.toFloat()
    val originHeight = coverImg.height.toFloat()

    val scale = desiredCoverWidth / originWidth
    val scaledCoverHeight = originHeight * scale

    // 3) 接下来根据 title/desc 段落高度、徽章高度等，计算出最终卡片整体高度
    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = titleTextStyle
    }

    val textAreaWidth = cardContentRect.width - quality.cardPadding - desiredCoverWidth
    val titleParagraph = ParagraphBuilder(paragraphStyle, FontUtils.fonts)
        .addText(title)
        .build()
        .layout(textAreaWidth)

    paragraphStyle.apply {
        maxLinesCount = if (titleParagraph.lineNumber == 1) 3 else 2
        textStyle = descTextStyle
    }
    val descParagraph = ParagraphBuilder(paragraphStyle, FontUtils.fonts)
        .addText(desc ?: "")
        .build()
        .layout(textAreaWidth)

    // 计算出卡片最终高度
    val cardHeight =
        quality.badgeHeight +
        scaledCoverHeight +
        quality.cardPadding

    return try {
        createImage(
            cardRect.width.toInt(),
            cardHeight.toInt()
        ) { canvas ->
        val videoCardRect = RRect.makeComplexXYWH(
            quality.cardPadding.toFloat(),
            quality.badgeHeight + 1f,
            cardContentRect.width,
            cardHeight - (quality.badgeHeight + quality.cardPadding),
            cardBadgeArc
        )
        canvas.drawCard(videoCardRect)
        canvas.drawRectShadowAntiAlias(
            videoCardRect.inflate(1f),
            theme.smallCardShadow
        )

        // 绘制徽章
        if (BiliConfigManager.config.imageConfig.badgeEnable.left) {
            canvas.drawBadge(
                lbadge,
                font,
                theme.subLeftBadge.fontColor,
                theme.subLeftBadge.bgColor,
                videoCardRect,
                Position.TOP_LEFT
            )
        }
        if (BiliConfigManager.config.imageConfig.badgeEnable.right) {
            canvas.drawBadge(
                rbadge,
                font,
                theme.subRightBadge.fontColor,
                theme.subRightBadge.bgColor,
                videoCardRect,
                Position.TOP_RIGHT
            )
        }

        // 绘制封面图片
        val coverRRect = RRect.makeComplexXYWH(
            videoCardRect.left,
            videoCardRect.top,
            desiredCoverWidth,
            scaledCoverHeight,
            cardBadgeArc
        ).inflate(-1f) as RRect
        canvas.drawImageRRect(coverImg, coverRRect)

        // 在右侧绘制标题/描述
        val textX = coverRRect.right + quality.cardPadding

        val totalTextHeight = titleParagraph.height + descParagraph.height
        val space = (scaledCoverHeight - totalTextHeight) / 3
        val textRegionTop = videoCardRect.top
        val startY = textRegionTop + space

        titleParagraph.paint(
            canvas,
            textX,
            startY
        )
        descParagraph.paint(
            canvas,
            textX,
            startY + titleParagraph.height + space
        )

        // 如果有 duration，就在封面右下角再叠加一个小标签
        if (duration != null) {
            val durationTextLine = TextLine.make(duration, font.makeWithSize(quality.subTitleFontSize))
            canvas.drawLabelCard(
                durationTextLine,
                coverRRect.left + quality.badgePadding * 2,
                coverRRect.bottom - durationTextLine.height - quality.badgePadding * 2,
                Paint().apply { color = Color.WHITE },
                Paint().apply {
                    color = Color.BLACK
                    alpha = 130
                }
            )
        }
    }
    } finally {
        coverImg.close()
    }
}

suspend fun ModuleDynamic.Major.Draw.drawGeneral(): Image {

    var drawItemWidth = 0f
    var drawItemHeight = 0f
    var drawItemSpace = quality.drawSpace * 2
    var drawItemNum = 1

    when (items.size) {
        1 -> {
            // 验证图片尺寸是否有效
            if (items[0].width <= 0 || items[0].height <= 0) {
                logger.warn("动态图片尺寸无效: width=${items[0].width}, height=${items[0].height}, src=${items[0].src}")
                // 使用默认的正方形尺寸
                drawItemWidth = cardContentRect.width / 2
                drawItemHeight = drawItemWidth
            } else {
                drawItemWidth = if (items[0].width > cardContentRect.width / 2) {
                    cardContentRect.width
                } else {
                    items[0].width * 2f
                }
                val drawHeight = items[0].height.toFloat() / items[0].width.toFloat() * drawItemWidth
                drawItemHeight = if (drawHeight > drawItemWidth * 2) {
                    drawItemWidth * 2
                } else {
                    drawHeight
                }
            }
        }

        2, 4 -> {
            drawItemWidth = (cardContentRect.width - quality.drawSpace) / 2
            drawItemHeight = drawItemWidth
            if (items.size >= 3) {
                drawItemSpace += quality.drawSpace
            }
            drawItemNum = 2
        }

        3, in 5..9 -> {
            drawItemWidth = (cardContentRect.width - quality.drawSpace * 2) / 3
            drawItemHeight = drawItemWidth
            drawItemSpace += if (items.size <= 6) {
                quality.drawSpace
            } else {
                quality.drawSpace * 2
            }
            drawItemNum = 3
        }

        else -> {
            // 10张及以上图片，使用3列布局
            drawItemWidth = (cardContentRect.width - quality.drawSpace * 2) / 3
            drawItemHeight = drawItemWidth
            drawItemSpace += quality.drawSpace * ((items.size - 1) / 3)
            drawItemNum = 3
            logger.debug("动态包含 ${items.size} 张图片，使用3列布局")
        }
    }

    val drawItems = items
    return createImage(
        cardRect.width.toInt(),
        (drawItemHeight * ceil(items.size / drawItemNum.toFloat()) + drawItemSpace).toInt()
    ) { canvas ->
        var x = quality.cardPadding.toFloat()
        var y = quality.drawSpace.toFloat()

        drawItems.forEachIndexed { index, drawItem ->
            val fallbackUrl = imgApi(drawItem.src, drawItemWidth.toInt(), drawItemHeight.toInt())
            val img = getOrDownloadImageDefault(drawItem.src, fallbackUrl, CacheType.IMAGES)
            try {
                val dstRect = RRect.makeXYWH(x, y, drawItemWidth, drawItemHeight, quality.cardArc)

                canvas.drawRRect(dstRect, Paint().apply {
                    color = Color.WHITE
                    alpha = 160
                    mode = PaintMode.FILL
                    isAntiAlias = true
                })

                canvas.drawImageClip(img, dstRect)

                canvas.drawRRect(dstRect, Paint().apply {
                    color = theme.drawOutlineColor
                    mode = PaintMode.STROKE
                    strokeWidth = quality.drawOutlineWidth
                    isAntiAlias = true
                })

                x += drawItemWidth + quality.drawSpace

                if ((index + 1) % drawItemNum == 0) {
                    x = quality.cardPadding.toFloat()
                    y += drawItemHeight + quality.drawSpace
                }
            } finally {
                img.close()
            }
        }
    }
}

suspend fun ModuleDynamic.Major.Blocked.drawGeneral(): Image {
    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = Alignment.CENTER
        textStyle = TextStyle().apply {
            fontSize = quality.titleFontSize
            color = Color.WHITE
            fontFamilies = arrayOf(mainTypeface.familyName)
        }
    }
    val hintMessage = ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText("包月充电专属动态").build()
    val bgImage = getOrDownloadImage(bgImg.imgDay, CacheType.IMAGES)!!
    val lockIcon = getOrDownloadImage(icon.imgDay, CacheType.IMAGES)!!

    return try {
        val bgWidth = cardContentRect.width - quality.cardPadding * 2
        val bgHeight = bgWidth / bgImage.width * bgImage.height

        val lockWidth = bgWidth / 7
        val lockHeight = lockWidth / lockIcon.width * lockIcon.height

        createImage(
            cardContentRect.width.toInt(), (bgHeight + quality.cardPadding * 2).toInt()
        ) { canvas ->
            var x = quality.cardPadding.toFloat()
            var y = 0f
            canvas.drawImageClip(bgImage, RRect.makeXYWH(x, y, bgWidth, bgHeight, quality.cardArc))
            x += (bgWidth - lockWidth) / 2
            y += bgHeight / 3
            canvas.drawImageClip(lockIcon, RRect.makeXYWH(x, y, lockWidth, lockHeight, quality.cardArc))

            x = quality.cardPadding.toFloat()
            y += lockHeight + quality.drawSpace
            hintMessage.layout(bgWidth).paint(canvas, x, y)
        }
    } finally {
        bgImage.close()
        lockIcon.close()
    }
}


suspend fun ModuleDynamic.Major.Article.drawGeneral(): Image {

    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = titleTextStyle
    }

    val paragraphWidth = cardContentRect.width - quality.cardPadding

    val titleParagraph = ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(title).build().layout(paragraphWidth)

    paragraphStyle.apply {
        maxLinesCount = 3
        textStyle = descTextStyle
    }

    val descParagraph = ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(desc).build().layout(paragraphWidth)
    val articleCoverHeight = cardContentRect.width * if (covers.size == 1) 0.35f else 0.23166f
    val articleCardHeight = articleCoverHeight + titleParagraph.height + descParagraph.height + quality.cardPadding

    val articleCardRect = RRect.makeComplexXYWH(
        quality.cardPadding.toFloat(),
        quality.badgeHeight + 1f,
        cardContentRect.width,
        articleCardHeight,
        cardBadgeArc
    )

    val articleCovers = covers
    return createImage(
        cardRect.width.toInt(),
        articleCardHeight.toInt() + quality.badgeHeight + quality.cardPadding
    ) { canvas ->
        // 绘制卡片背景
        canvas.drawCard(articleCardRect)
        // 卡片阴影
        canvas.drawRectShadowAntiAlias(articleCardRect.inflate(1f), theme.smallCardShadow)

        // 封面
        val coverRRect = RRect.makeComplexXYWH(
            articleCardRect.left,
            articleCardRect.top,
            articleCardRect.width,
            articleCoverHeight,
            cardBadgeArc
        ).inflate(-1f) as RRect
        if (articleCovers.size == 1) {
            val fallbackUrl = imgApi(articleCovers[0], articleCardRect.width.toInt(), articleCoverHeight.toInt())
            val coverImg = getOrDownloadImageDefault(articleCovers[0], fallbackUrl, CacheType.IMAGES)
            try {
                canvas.drawImageRRect(coverImg, coverRRect)
            } finally {
                coverImg.close()
            }
        } else {
            var imgX = articleCardRect.left
            val imgW = articleCardRect.width / 3 - 4
            canvas.save()
            canvas.clipRRect(coverRRect, true)
            articleCovers.forEach {
                val fallbackUrl = imgApi(it, imgW.toInt(), articleCoverHeight.toInt())
                val img = getOrDownloadImageDefault(it, fallbackUrl, CacheType.IMAGES)
                try {
                    val tar = RRect.makeXYWH(imgX, articleCardRect.top, imgW, articleCoverHeight, 0f)
                    canvas.drawImageClip(img, tar, Paint())
                    imgX += articleCardRect.width / 3 + 2
                } finally {
                    img.close()
                }
            }
            canvas.restore()
        }

        // 徽章
        if (BiliConfigManager.config.imageConfig.badgeEnable.left) {
            canvas.drawBadge(
                "专栏", font, theme.subLeftBadge.fontColor, theme.subLeftBadge.bgColor, articleCardRect,
                Position.TOP_LEFT
            )
        } else {
            val labelTextLine = TextLine.make("专栏", font.makeWithSize(quality.subTitleFontSize))
            canvas.drawLabelCard(
                labelTextLine,
                articleCardRect.right - labelTextLine.width - quality.badgePadding * 4 - quality.cardPadding,
                articleCardRect.top + quality.cardPadding * 0.8f,
                Paint().apply { color = Color.WHITE },
                Paint().apply { color = Color.makeRGB(251, 114, 153) }
            )
        }
        if (BiliConfigManager.config.imageConfig.badgeEnable.right) {
            canvas.drawBadge(
                "cv$id",
                font,
                theme.subRightBadge.fontColor,
                theme.subRightBadge.bgColor,
                articleCardRect,
                Position.TOP_RIGHT
            )
        }

        titleParagraph.paint(
            canvas,
            quality.cardPadding * 1.5f,
            quality.badgeHeight + articleCoverHeight + quality.cardPadding / 2
        )

        descParagraph.paint(
            canvas,
            quality.cardPadding * 1.5f,
            quality.badgeHeight + articleCoverHeight + quality.cardPadding / 2 + titleParagraph.height
        )
    }
}

suspend fun ModuleDynamic.Major.Music.drawGeneral(): Image {

    val paragraphStyle = ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = Alignment.LEFT
        textStyle = titleTextStyle
    }

    val musicCardHeight = cardContentRect.width * 0.19f

    val paragraphWidth = cardContentRect.width - quality.cardPadding * 2 - musicCardHeight

    val titleParagraph = ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(title).build().layout(paragraphWidth)

    paragraphStyle.apply {
        textStyle = descTextStyle
    }

    val descParagraph = ParagraphBuilder(paragraphStyle, FontUtils.fonts).addText(label).build().layout(paragraphWidth)

    val musicCardRect = RRect.makeComplexXYWH(
        quality.cardPadding.toFloat(),
        quality.badgeHeight + 1f,
        cardContentRect.width,
        musicCardHeight,
        cardBadgeArc
    )

    val musicCover = cover
    val musicId = id
    return createImage(
        cardRect.width.toInt(),
        musicCardHeight.toInt() + quality.badgeHeight + quality.cardPadding
    ) { canvas ->
        // 绘制卡片背景
        canvas.drawCard(musicCardRect)
        // 卡片阴影
        canvas.drawRectShadowAntiAlias(musicCardRect.inflate(1f), theme.smallCardShadow)

        // 徽章
        if (BiliConfigManager.config.imageConfig.badgeEnable.left) {
            canvas.drawBadge(
                "音乐", font, theme.subLeftBadge.fontColor, theme.subLeftBadge.bgColor, musicCardRect,
                Position.TOP_LEFT
            )
        }
        if (BiliConfigManager.config.imageConfig.badgeEnable.right) {
            canvas.drawBadge(
                "au$musicId",
                font,
                theme.subRightBadge.fontColor,
                theme.subRightBadge.bgColor,
                musicCardRect,
                Position.TOP_RIGHT
            )
        }

        // 封面
        val fallbackUrl = imgApi(musicCover, musicCardHeight.toInt(), musicCardHeight.toInt())
        val coverImg = getOrDownloadImageDefault(musicCover, fallbackUrl, CacheType.IMAGES)
        try {
            val coverRRect = RRect.makeComplexXYWH(
                musicCardRect.left,
                musicCardRect.top,
                musicCardHeight,
                musicCardHeight,
                cardBadgeArc
            ).inflate(-1f) as RRect
            canvas.drawImageRRect(coverImg, coverRRect)
        } finally {
            coverImg.close()
        }

        val space = (musicCardHeight - titleParagraph.height - descParagraph.height) / 3
        val y = musicCardRect.top + space

        titleParagraph.paint(
            canvas,
            musicCardHeight + quality.cardMargin * 2,
            y
        )

        descParagraph.paint(
            canvas,
            musicCardHeight + quality.cardMargin * 2,
            y + space + titleParagraph.height
        )
    }
}
