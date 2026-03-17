package top.bilibili.core.resource

import kotlinx.serialization.encodeToString
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.data.DynamicItem
import top.bilibili.data.LiveInfo
import top.bilibili.data.ModuleAuthor
import top.bilibili.data.ModuleDispute
import top.bilibili.data.ModuleDynamic
import top.bilibili.draw.*
import top.bilibili.loadFonts
import top.bilibili.skia.DrawingSession
import top.bilibili.skia.SkiaManager
import top.bilibili.utils.CacheType
import top.bilibili.utils.cacheFile
import top.bilibili.utils.json
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.writeBytes
object SkiaDrawSceneFixtures {
    data class DrawSceneCase(
        val name: String,
        val render: suspend () -> ByteArray,
    )

    private val baseConfig = BiliConfig()

    private const val userFace = "scene-user-face.png"
    private const val userPendant = "scene-user-pendant.png"
    private const val userFanCard = "scene-user-fan-card.png"
    private const val imageBadge = "scene-image-badge.png"
    private const val imageWide = "scene-image-wide.png"
    private const val imageTall = "scene-image-tall.png"
    private const val imageSquare = "scene-image-square.png"
    private const val imageBlockedBg = "scene-blocked-bg.png"
    private const val imageBlockedLock = "scene-blocked-lock.png"
    private const val imageAnimated = "scene-animated.gif"
    private const val emojiIcon = "scene-emoji.png"
    private const val otherWide = "scene-other-wide.png"
    private const val otherSquare = "scene-other-square.png"

    suspend fun prepareEnvironment() {
        setRuntimeConfig(baseConfig)
        loadFonts()
        installLocalAssets()
    }

    fun sceneCases(): List<DrawSceneCase> {
        val authorDecorated = authorFixture(withDecorate = true, withPendant = true, withIconBadge = true)
        val authorPlain = authorFixture(withDecorate = false, withPendant = false, withIconBadge = false)
        val contentDesc = contentDescFixture()
        val topic = ModuleDynamic.Topic(id = 1, name = "Scene Topic", jumpUrl = "https://example.com/topic")
        val dispute = ModuleDispute("Scene notice", "Dispute body", "https://example.com/dispute")
        val liveInfo = liveInfoFixture()
        val archiveMajor = archiveMajorFixture()

        return listOf(
            DrawSceneCase("login-qr") {
                encodeDetachedImage(loginQrCode("https://example.com/login"))
            },
            DrawSceneCase("author-general-fan-card") {
                withCardOrnament("FanCard") {
                    renderTrackedImage { authorDecorated.drawGeneral(this, "2026-03-17 20:00", "https://example.com/u/1", 0xFF66CC.toInt()) }
                }
            },
            DrawSceneCase("author-general-qr") {
                withCardOrnament("QrCode") {
                    renderTrackedImage { authorPlain.drawGeneral(this, "2026-03-17 20:00", "https://example.com/u/1", 0x33AADD.toInt()) }
                }
            },
            DrawSceneCase("author-forward") {
                renderTrackedImage { authorPlain.drawForward(this, "2h ago") }
            },
            DrawSceneCase("module-dispute") {
                renderTrackedImage { dispute.drawGeneral(this) }
            },
            DrawSceneCase("module-topic") {
                renderTrackedImage { topic.drawGeneral(this) }
            },
            DrawSceneCase("module-content-desc") {
                renderTrackedImage { contentDesc.drawGeneral(this) }
            },
            DrawSceneCase("additional-common") {
                renderTrackedImage { additionalCommonFixture().makeGeneral(this)!! }
            },
            DrawSceneCase("additional-reserve") {
                renderTrackedImage { additionalReserveFixture().makeGeneral(this)!! }
            },
            DrawSceneCase("additional-vote") {
                renderTrackedImage { additionalVoteFixture().makeGeneral(this)!! }
            },
            DrawSceneCase("additional-ugc") {
                renderTrackedImage { additionalUgcFixture().makeGeneral(this)!! }
            },
            DrawSceneCase("additional-goods") {
                renderTrackedImage { additionalGoodsFixture().makeGeneral(this)!! }
            },
            DrawSceneCase("additional-lottery") {
                renderTrackedImage { additionalLotteryFixture().makeGeneral(this)!! }
            },
            DrawSceneCase("major-archive-general") {
                renderTrackedImage { archiveMajor.makeGeneral(this) }
            },
            DrawSceneCase("major-archive-small") {
                renderTrackedImage { archiveMajor.makeGeneral(this, isForward = true) }
            },
            DrawSceneCase("major-blocked") {
                renderTrackedImage { blockedMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-draw") {
                renderTrackedImage { drawMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-article") {
                renderTrackedImage { articleMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-music") {
                renderTrackedImage { musicMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-live") {
                renderTrackedImage { liveMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-live-rcmd") {
                renderTrackedImage { liveRcmdMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-pgc") {
                renderTrackedImage { pgcMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-ugc-season") {
                renderTrackedImage { ugcSeasonMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-common") {
                renderTrackedImage { commonMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-opus") {
                renderTrackedImage { opusMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("major-none") {
                renderTrackedImage { noneMajorFixture().makeGeneral(this) }
            },
            DrawSceneCase("blocked-default") {
                renderTrackedImage { drawBlockedDefault(this) }
            },
            DrawSceneCase("module-dynamic-make-general") {
                renderTrackedImage {
                    val images = moduleDynamicFixture(archiveMajor.archive!!).makeGeneral(this)
                    try {
                        images.assembleCard(this, id = "scene-module-dynamic", closeInputImages = true)
                    } finally {
                        images.forEach { sceneImage -> runCatching { sceneImage.close() } }
                    }
                }
            },
            DrawSceneCase("dynamic-item-word") {
                renderTrackedImage { dynamicWordFixture().drawDynamic(this, 0x44AADD.toInt()) }
            },
            DrawSceneCase("dynamic-item-forward") {
                renderTrackedImage { dynamicForwardFixture().drawDynamic(this, 0x44AADD.toInt()) }
            },
            DrawSceneCase("dynamic-item-make-draw") {
                val path = dynamicForwardFixture().makeDrawDynamic(listOf(0x1144AA, 0x22AA66, 0xCC8844, 0xEE6688), subject = "group:scene", color = "#1144AA;#22AA66;#CC8844;#EE6688")
                Files.readAllBytes(java.nio.file.Path.of(path))
            },
            DrawSceneCase("live-info-draw") {
                withCardOrnament("QrCode") {
                    renderTrackedImage { liveInfo.drawLive(this, 0x33AADD.toInt()) }
                }
            },
            DrawSceneCase("live-info-make-draw") {
                withCardOrnament("QrCode") {
                    val path = liveInfo.makeDrawLive(listOf(0x1144AA, 0x22AA66, 0xCC8844, 0xEE6688), subject = "group:scene", color = "#1144AA;#22AA66;#CC8844;#EE6688")
                    Files.readAllBytes(java.nio.file.Path.of(path))
                }
            },
        )
    }

    private suspend fun renderTrackedImage(draw: suspend DrawingSession.() -> Image): ByteArray {
        return SkiaManager.executeDrawing {
            val image = draw()
            encodeTrackedImage(image)
        }
    }

    private fun encodeTrackedImage(image: Image): ByteArray {
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("failed to encode tracked scene image")
        return try {
            data.bytes
        } finally {
            data.close()
        }
    }

    private fun encodeDetachedImage(image: Image): ByteArray {
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("failed to encode detached scene image")
        return try {
            data.bytes
        } finally {
            data.close()
            image.close()
        }
    }

    private suspend fun <T> withCardOrnament(ornament: String, block: suspend () -> T): T {
        val previous = BiliConfigManager.config
        setRuntimeConfig(previous.copy(imageConfig = previous.imageConfig.copy(cardOrnament = ornament)))
        return try {
            block()
        } finally {
            setRuntimeConfig(previous)
        }
    }

    private fun setRuntimeConfig(config: BiliConfig) {
        val configField = BiliConfigManager::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(BiliConfigManager, config)
    }

    private fun installLocalAssets() {
        writeAsset(CacheType.USER, userFace, 240, 240, 0xFF3B82F6.toInt())
        writeAsset(CacheType.USER, userPendant, 320, 320, 0xFFF59E0B.toInt())
        writeAsset(CacheType.USER, userFanCard, 500, 160, 0xFF1F2937.toInt())
        writeAsset(CacheType.IMAGES, imageBadge, 120, 40, 0xFFEF4444.toInt())
        writeAsset(CacheType.IMAGES, imageWide, 640, 360, 0xFF10B981.toInt())
        writeAsset(CacheType.IMAGES, imageTall, 320, 900, 0xFF8B5CF6.toInt())
        writeAsset(CacheType.IMAGES, imageSquare, 512, 512, 0xFF06B6D4.toInt())
        writeAsset(CacheType.IMAGES, imageBlockedBg, 800, 450, 0xFF111827.toInt())
        writeAsset(CacheType.IMAGES, imageBlockedLock, 128, 128, 0xFFF3F4F6.toInt())
        writeAsset(CacheType.IMAGES, imageAnimated, 480, 480, 0xFFEC4899.toInt())
        writeAsset(CacheType.EMOJI, emojiIcon, 96, 96, 0xFFF97316.toInt())
        writeAsset(CacheType.OTHER, otherWide, 640, 360, 0xFF22C55E.toInt())
        writeAsset(CacheType.OTHER, otherSquare, 512, 512, 0xFF14B8A6.toInt())
    }

    private fun writeAsset(cacheType: CacheType, fileName: String, width: Int, height: Int, argb: Int) {
        val file = cacheType.cacheFile(fileName)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = AwtColor(argb, true)
            graphics.fillRect(0, 0, width, height)
            graphics.color = AwtColor.WHITE
            graphics.drawRect(0, 0, width - 1, height - 1)
            graphics.drawString(fileName.substringBefore('.'), 8, (height / 2).coerceAtLeast(16))
        } finally {
            graphics.dispose()
        }
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
        file.writeBytes(bytes)
    }

    private fun cacheUrl(fileName: String) = "cache/$fileName"

    private fun authorFixture(withDecorate: Boolean, withPendant: Boolean, withIconBadge: Boolean): ModuleAuthor {
        return ModuleAuthor(
            mid = 10001L,
            name = "SceneAuthor",
            face = cacheUrl(userFace),
            pubTs = 1710000000L,
            pubTime = "2026-03-17 20:00",
            officialVerify = ModuleAuthor.OfficialVerify(type = 0, desc = "verified"),
            pendant = if (withPendant) ModuleAuthor.Pendant(pid = 1L, name = "Pendant", image = cacheUrl(userPendant)) else null,
            iconBadge = if (withIconBadge) ModuleAuthor.IconBadge(icon = "badge", renderImg = cacheUrl(imageBadge), text = "SceneBadge") else null,
            decorate = if (withDecorate) {
                ModuleAuthor.Decorate(
                    id = 1L,
                    type = 3,
                    name = "FanCard",
                    cardUrl = cacheUrl(userFanCard),
                    jumpUrl = "https://example.com/fan-card",
                    fan = ModuleAuthor.Decorate.Fan(color = "#ff6699", isFan = true, numStr = "1024", number = 1024),
                )
            } else null,
        )
    }

    private fun moduleDynamicFixture(archive: ModuleDynamic.Major.Archive): ModuleDynamic {
        return ModuleDynamic(
            topic = ModuleDynamic.Topic(id = 1, name = "Scene Topic", jumpUrl = "https://example.com/topic"),
            desc = contentDescFixture(),
            major = ModuleDynamic.Major(type = "MAJOR_TYPE_ARCHIVE", archive = archive),
            additional = additionalReserveFixture(),
        )
    }

    private fun contentDescFixture(): ModuleDynamic.ContentDesc {
        val nodes = listOf(
            ModuleDynamic.ContentDesc.RichTextNode(type = "RICH_TEXT_NODE_TYPE_TEXT", origText = "Scene text ", text = "Scene text "),
            ModuleDynamic.ContentDesc.RichTextNode(
                type = "RICH_TEXT_NODE_TYPE_EMOJI",
                origText = "[emoji]",
                text = "[emoji]",
                emoji = ModuleDynamic.ContentDesc.RichTextNode.Emoji(type = 1, iconUrl = cacheUrl(emojiIcon), size = 1, text = ":)")
            ),
            ModuleDynamic.ContentDesc.RichTextNode(type = "RICH_TEXT_NODE_TYPE_AT", origText = "@scene", text = "@scene", rid = "10001", jumpUrl = "https://example.com/u/10001"),
            ModuleDynamic.ContentDesc.RichTextNode(type = "RICH_TEXT_NODE_TYPE_TOPIC", origText = "#topic#", text = "#topic#", jumpUrl = "https://example.com/topic"),
            ModuleDynamic.ContentDesc.RichTextNode(type = "RICH_TEXT_NODE_TYPE_WEB", origText = "https://example.com", text = "link", jumpUrl = "https://example.com"),
            ModuleDynamic.ContentDesc.RichTextNode(type = "RICH_TEXT_NODE_TYPE_VOTE", origText = "vote", text = "vote", jumpUrl = "https://example.com/vote"),
            ModuleDynamic.ContentDesc.RichTextNode(type = "RICH_TEXT_NODE_TYPE_LOTTERY", origText = "lottery", text = "lottery", jumpUrl = "https://example.com/lottery"),
            ModuleDynamic.ContentDesc.RichTextNode(type = "RICH_TEXT_NODE_TYPE_BV", origText = "BV1xx", text = "BV1xx", jumpUrl = "https://example.com/video/BV1xx"),
        )
        return ModuleDynamic.ContentDesc(richTextNodes = nodes, text = nodes.joinToString("") { it.text })
    }

    private fun additionalCommonFixture() = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_COMMON",
        common = ModuleDynamic.Additional.Common(
            idStr = "common-1",
            title = "Common Additional",
            cover = cacheUrl(otherWide),
            subType = "game",
            desc1 = "Common desc 1",
            desc2 = "Common desc 2",
            headText = "Common Head",
            jumpUrl = "https://example.com/common",
            style = 1,
            button = ModuleDynamic.Additional.Button(type = 1),
        ),
    )

    private fun additionalReserveFixture() = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_RESERVE",
        reserve = ModuleDynamic.Additional.Reserve(
            rid = 1L,
            upMid = 10001L,
            title = "Reserve Additional",
            reserveTotal = 10,
            desc1 = ModuleDynamic.Additional.Desc(text = "Reserve one", style = 0),
            desc2 = ModuleDynamic.Additional.Desc(text = "Reserve two", style = 0),
            desc3 = ModuleDynamic.Additional.Desc(text = "Reserve three", style = 0),
            premiere = ModuleDynamic.Additional.Reserve.Premiere(cover = cacheUrl(otherWide), online = "1024"),
            state = 1,
            stype = 2,
            jumpUrl = "https://example.com/reserve",
            button = ModuleDynamic.Additional.Button(type = 1),
        ),
    )

    private fun additionalVoteFixture() = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_VOTE",
        vote = ModuleDynamic.Additional.Vote(
            uid = 10001L,
            voteId = 2L,
            desc = "Vote Additional",
            type = 1,
            status = 1,
            joinNum = 99,
            endTime = 1710000000L,
            choiceCnt = 1,
            defaultShare = 0,
        ),
    )

    private fun additionalUgcFixture() = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_UGC",
        ugc = ModuleDynamic.Additional.Ugc(
            idStr = "ugc-1",
            title = "UGC Additional",
            cover = cacheUrl(otherWide),
            descSecond = "UGC second line",
            duration = "03:20",
            headText = "UGC Head",
            jumpUrl = "https://example.com/ugc",
            multiLine = "0",
        ),
    )

    private fun additionalGoodsFixture() = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_GOODS",
        goods = ModuleDynamic.Additional.Goods(
            headIcon = "goods",
            headText = "Goods Head",
            jumpUrl = "https://example.com/goods",
            items = listOf(
                ModuleDynamic.Additional.Goods.GoodItem(
                    id = "goods-1",
                    name = "Scene Goods",
                    brief = "Goods brief",
                    cover = cacheUrl(otherSquare),
                    price = "88",
                    jumpDesc = "Buy",
                    jumpUrl = "https://example.com/goods/1",
                )
            ),
        ),
    )

    private fun additionalLotteryFixture() = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_UPOWER_LOTTERY",
        lottery = ModuleDynamic.Additional.Lottery(
            rid = 1L,
            title = "Charge Lottery",
            mid = 10001L,
            state = 1,
            desc = ModuleDynamic.Additional.Desc(text = "Lottery desc", style = 0),
            button = ModuleDynamic.Additional.Button(type = 1),
            jumpUrl = "https://example.com/lottery",
        ),
    )

    private fun badge(text: String) = ModuleDynamic.Major.Badge(bgColor = "#ff6699", color = "#ffffff", text = text)
    private fun stat() = ModuleDynamic.Major.Stat(danmaku = "1024", play = "2048")

    private fun archiveMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_ARCHIVE",
        archive = ModuleDynamic.Major.Archive(
            aid = "100",
            bvid = "BV1scene0001",
            title = "Archive Scene",
            cover = cacheUrl(imageWide),
            desc = "Archive description",
            durationText = "10:00",
            jumpUrl = "https://example.com/archive",
            stat = stat(),
            badge = badge("VIDEO"),
        ),
    )

    private fun blockedMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_BLOCKED",
        blocked = ModuleDynamic.Major.Blocked(
            blockType = 1,
            button = ModuleDynamic.Major.Blocked.Button(icon = "lock", jumpUrl = "https://example.com/unlock", text = "Unlock"),
            bgImg = ModuleDynamic.Major.Blocked.Img(imgDark = cacheUrl(imageBlockedBg), imgDay = cacheUrl(imageBlockedBg)),
            hintMessage = "Blocked hint",
            icon = ModuleDynamic.Major.Blocked.Img(imgDark = cacheUrl(imageBlockedLock), imgDay = cacheUrl(imageBlockedLock)),
        ),
    )

    private fun drawMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_DRAW",
        draw = ModuleDynamic.Major.Draw(
            id = 1L,
            items = listOf(
                ModuleDynamic.Major.Draw.DrawItem(width = 480, height = 480, src = cacheUrl(imageAnimated)),
                ModuleDynamic.Major.Draw.DrawItem(width = 320, height = 900, src = cacheUrl(imageTall)),
                ModuleDynamic.Major.Draw.DrawItem(
                    width = 512,
                    height = 512,
                    src = cacheUrl(imageSquare),
                    tags = listOf(
                        ModuleDynamic.Major.Draw.DrawItem.Tag(
                            tid = 1L,
                            itemId = "tag-1",
                            type = 1,
                            text = "Tag",
                            orientation = 0,
                            x = 40,
                            y = 60,
                        )
                    ),
                ),
            ),
        ),
    )

    private fun articleMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_ARTICLE",
        article = ModuleDynamic.Major.Article(
            id = "100",
            title = "Article Scene",
            desc = "Article description for scene coverage",
            label = "Read 1024",
            jumpUrl = "https://example.com/article",
            covers = listOf(cacheUrl(imageWide), cacheUrl(imageSquare), cacheUrl(imageTall)),
        ),
    )

    private fun musicMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_MUSIC",
        music = ModuleDynamic.Major.Music(
            id = 1L,
            title = "Music Scene",
            cover = cacheUrl(imageSquare),
            label = "Track Label",
            jumpUrl = "https://example.com/music",
        ),
    )

    private fun liveMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_LIVE",
        live = ModuleDynamic.Major.Live(
            id = 1L,
            title = "Live Scene",
            cover = cacheUrl(imageWide),
            descFirst = "Live now",
            descSecond = "Game Area",
            jumpUrl = "https://live.bilibili.com/1",
            liveState = 1,
            reserveType = 0,
            badge = badge("LIVE"),
        ),
    )

    private fun liveRcmdMajorFixture(): ModuleDynamic.Major {
        val content = ModuleDynamic.Major.LiveRcmd.LiveRcmdContent(
            type = 1,
            livePlayInfo = ModuleDynamic.Major.LiveRcmd.LiveRcmdContent.LivePlayInfo(
                uid = 10001L,
                roomId = 1L,
                liveId = "live-1",
                liveStatus = 1,
                title = "Live Rcmd Scene",
                cover = cacheUrl(imageWide),
                parentAreaName = "Game",
                parentAreaId = 1,
                areaName = "RPG",
                areaId = 2,
                link = "https://live.bilibili.com/1",
                room_type = 0,
                liveScreenType = 0,
                liveStartTime = 1710000000L,
                playType = 0,
                online = 2048,
                roomPaidType = 0,
                watchedShow = ModuleDynamic.Major.LiveRcmd.LiveRcmdContent.LivePlayInfo.WatchedShow(
                    num = 2048,
                    textSmall = "2048",
                    textLarge = "2048 watching",
                    icon = "https://example.com/icon.png",
                    iconLocation = "",
                    iconWeb = "https://example.com/icon-web.png",
                    switch = true,
                ),
            ),
        )
        return ModuleDynamic.Major(
            type = "MAJOR_TYPE_LIVE_RCMD",
            liveRcmd = ModuleDynamic.Major.LiveRcmd(
                content = json.encodeToString(ModuleDynamic.Major.LiveRcmd.LiveRcmdContent.serializer(), content),
                reserveType = 0,
            ),
        )
    }

    private fun pgcMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_PGC",
        pgc = ModuleDynamic.Major.Pgc(
            type = 2,
            subType = 1,
            epid = 1,
            seasonId = 1,
            title = "PGC Scene",
            cover = cacheUrl(imageWide),
            jumpUrl = "https://example.com/pgc",
            stat = stat(),
            badge = badge("PGC"),
            evaluate = "PGC evaluation content for scene coverage",
            areas = "Action / Drama",
            pubTime = "2026-03-17",
            isFinish = 1,
            total = 12,
            rating = 9.2f,
        ),
    )

    private fun ugcSeasonMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_UGC_SEASON",
        ugcSeason = archiveMajorFixture().archive,
    )

    private fun commonMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_COMMON",
        common = ModuleDynamic.Major.Common(
            id = "common-major",
            sketchId = "sketch-1",
            bizType = 1,
            title = "Common Major Scene",
            cover = cacheUrl(otherSquare),
            desc = "Common major description",
            label = "Common label",
            jumpUrl = "https://example.com/common-major",
            style = 1,
            badge = badge("COMMON"),
        ),
    )

    private fun opusMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_OPUS",
        opus = ModuleDynamic.Major.Opus(
            title = "Opus Scene",
            summary = contentDescFixture(),
            pics = listOf(
                ModuleDynamic.Major.Opus.DrawItem(width = 640, height = 360, src = cacheUrl(imageWide)),
                ModuleDynamic.Major.Opus.DrawItem(width = 320, height = 900, src = cacheUrl(imageTall)),
            ),
        ),
    )

    private fun noneMajorFixture() = ModuleDynamic.Major(
        type = "MAJOR_TYPE_NONE",
        none = ModuleDynamic.Major.None(tips = "None scene tips"),
    )

    private fun dynamicWordFixture(): DynamicItem {
        return DynamicItem(
            typeStr = "DYNAMIC_TYPE_WORD",
            basic = DynamicItem.DynamicBasic(commentIdStr = "1", commentType = 11, ridStr = "1"),
            idStr = "734439407827600001",
            modules = DynamicItem.Modules(
                moduleAuthor = authorFixture(withDecorate = true, withPendant = true, withIconBadge = true),
                moduleDynamic = ModuleDynamic(
                    topic = ModuleDynamic.Topic(id = 2, name = "Dynamic Topic", jumpUrl = "https://example.com/topic/2"),
                    desc = contentDescFixture(),
                    additional = additionalGoodsFixture(),
                ),
                moduleDispute = ModuleDispute("Word notice", "Word dispute", "https://example.com/dispute/word"),
            ),
        )
    }

    private fun dynamicForwardFixture(): DynamicItem {
        return DynamicItem(
            typeStr = "DYNAMIC_TYPE_FORWARD",
            basic = DynamicItem.DynamicBasic(commentIdStr = "2", commentType = 11, ridStr = "2"),
            idStr = "734439407827600002",
            modules = DynamicItem.Modules(
                moduleAuthor = authorFixture(withDecorate = false, withPendant = false, withIconBadge = false),
                moduleDynamic = ModuleDynamic(
                    desc = ModuleDynamic.ContentDesc(
                        richTextNodes = listOf(ModuleDynamic.ContentDesc.RichTextNode(type = "RICH_TEXT_NODE_TYPE_TEXT", origText = "Forward desc", text = "Forward desc")),
                        text = "Forward desc"
                    ),
                    major = archiveMajorFixture(),
                    additional = additionalVoteFixture(),
                ),
            ),
            orig = dynamicWordFixture(),
        )
    }

    private fun liveInfoFixture() = LiveInfo(
        title = "LiveInfo Scene",
        roomId = 1000L,
        uid = 10001L,
        uname = "SceneAuthor",
        face = cacheUrl(userFace),
        cover = cacheUrl(imageWide),
        liveTimeStart = 1710000000L,
        liveTimeDuration = 1710000000L,
        liveStatus = 1,
        area = "Game",
    )
}
