package top.bilibili.playground.fixture

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.bilibili.data.DynamicItem
import top.bilibili.data.LiveInfo
import top.bilibili.data.ModuleDynamic

internal object FixtureCatalog {
    private val json = Json { encodeDefaults = true }

    fun defaults(assets: FixtureAssets): List<PlaygroundFixture> {
        val rawDynamic = FixtureLoader.loadResource("fixtures/dynamic-sample.json").dynamic!!
        val baseDynamic = rawDynamic.copy(
            modules = rawDynamic.modules.copy(
                moduleAuthor = rawDynamic.modules.moduleAuthor.copy(face = assets.avatarUrl),
            ),
        )
        val rawLive = FixtureLoader.loadResource("fixtures/live-sample.json").live!!
        val baseLive = rawLive.copy(face = assets.avatarUrl, cover = assets.coverUrl)
        val forwardOrigin = sample(
            base = baseDynamic,
            id = "dynamic-forward-origin",
            label = "Forward origin",
            dynamicId = "21001",
            typeStr = "DYNAMIC_TYPE_AV",
            colors = listOf(0x5B8FF9),
            descText = "这是被转发的视频动态，用来预览转发卡片样式。",
            major = archive(
                title = "源动态视频样例",
                coverUrl = assets.coverUrl,
                jumpUrl = "https://www.bilibili.com/video/BV1playground1",
                badgeText = "视频",
            ),
        ).dynamic!!

        return listOf(
            sample(baseDynamic, "dynamic-word", "Dynamic / Word", "11001", "DYNAMIC_TYPE_WORD", listOf(0xD2E3FC), "hello playground"),
            sample(baseDynamic, "dynamic-forward", "Dynamic / Forward", "11002", "DYNAMIC_TYPE_FORWARD", listOf(0xF6C26B), "转发一下这条源动态，检查转发头部和嵌套卡片。", orig = forwardOrigin),
            sample(baseDynamic, "dynamic-archive", "Dynamic / Archive", "11003", "DYNAMIC_TYPE_AV", listOf(0x5B8FF9), "视频卡片样例", major = archive("Compose playground 视频卡片", assets.coverUrl, "https://www.bilibili.com/video/BV1playground2", "视频")),
            sample(baseDynamic, "dynamic-draw", "Dynamic / Draw", "11004", "DYNAMIC_TYPE_DRAW", listOf(0x73D13D), "三图动态样例，用来预览宫格和裁切。", major = draw(assets.coverUrl)),
            sample(baseDynamic, "dynamic-article", "Dynamic / Article", "11005", "DYNAMIC_TYPE_ARTICLE", listOf(0x9254DE), "专栏卡片样例", major = article(assets.coverUrl)),
            sample(baseDynamic, "dynamic-music", "Dynamic / Music", "11006", "DYNAMIC_TYPE_MUSIC", listOf(0x13C2C2), "音乐卡片样例", major = music(assets.coverUrl)),
            sample(baseDynamic, "dynamic-live-card", "Dynamic / Live", "11007", "DYNAMIC_TYPE_LIVE", listOf(0xFF7A45), "直播卡片样例", major = liveCard(assets.coverUrl)),
            sample(baseDynamic, "dynamic-live-rcmd", "Dynamic / Live Rcmd", "11008", "DYNAMIC_TYPE_LIVE_RCMD", listOf(0xFF9C6E), "直播推荐卡片样例", major = liveRcmd(assets.coverUrl)),
            sample(baseDynamic, "dynamic-common", "Dynamic / Common", "11009", "DYNAMIC_TYPE_COMMON_VERTICAL", listOf(0x597EF7), "活动卡片样例", major = common(assets.coverUrl)),
            sample(baseDynamic, "dynamic-opus", "Dynamic / Opus", "11010", "DYNAMIC_TYPE_WORD", listOf(0x36CFC9), "新版图文动态样例", major = opus(assets.coverUrl)),
            sample(baseDynamic, "dynamic-pgc", "Dynamic / PGC", "11011", "DYNAMIC_TYPE_PGC", listOf(0xEB2F96), "番剧卡片样例", major = pgc(assets.coverUrl)),
            sample(baseDynamic, "dynamic-ugc-season", "Dynamic / UGC Season", "11012", "DYNAMIC_TYPE_UGC_SEASON", listOf(0x722ED1), "合集卡片样例", major = ugcSeason(assets.coverUrl)),
            sample(baseDynamic, "dynamic-additional-common", "Dynamic / Additional Common", "11013", "DYNAMIC_TYPE_WORD", listOf(0x2F54EB), "附加活动卡片样例", additional = additionalCommon(assets.coverUrl)),
            sample(baseDynamic, "dynamic-additional-reserve", "Dynamic / Additional Reserve", "11014", "DYNAMIC_TYPE_WORD", listOf(0xFA8C16), "附加预约卡片样例", additional = additionalReserve(assets.coverUrl)),
            sample(baseDynamic, "dynamic-additional-vote", "Dynamic / Additional Vote", "11015", "DYNAMIC_TYPE_WORD", listOf(0x13C2C2), "附加投票卡片样例", additional = additionalVote()),
            sample(baseDynamic, "dynamic-additional-ugc", "Dynamic / Additional UGC", "11016", "DYNAMIC_TYPE_WORD", listOf(0x5CDBD3), "附加相关视频卡片样例", additional = additionalUgc(assets.coverUrl)),
            sample(baseDynamic, "dynamic-additional-goods", "Dynamic / Additional Goods", "11017", "DYNAMIC_TYPE_WORD", listOf(0xF759AB), "附加商品卡片样例", additional = additionalGoods(assets.coverUrl)),
            sample(baseDynamic, "dynamic-additional-lottery", "Dynamic / Additional Lottery", "11018", "DYNAMIC_TYPE_WORD", listOf(0xF5222D), "附加充电抽奖卡片样例", additional = additionalLottery()),
            live(baseLive, "live-room", "Live / Room", listOf(4478310)),
        )
    }

    private fun sample(
        base: DynamicItem,
        id: String,
        label: String,
        dynamicId: String,
        typeStr: String,
        colors: List<Int>,
        descText: String,
        major: ModuleDynamic.Major? = null,
        additional: ModuleDynamic.Additional? = null,
        orig: DynamicItem? = null,
    ): PlaygroundFixture {
        val detail = base.copy(
            typeStr = typeStr,
            idStr = dynamicId,
            basic = base.basic.copy(commentIdStr = dynamicId, ridStr = dynamicId),
            modules = base.modules.copy(
                moduleDynamic = base.modules.moduleDynamic.copy(
                    desc = base.modules.moduleDynamic.desc?.copy(text = descText)
                        ?: ModuleDynamic.ContentDesc(emptyList(), descText),
                    major = major,
                    additional = additional,
                ),
            ),
            orig = orig,
        )
        return PlaygroundFixture(id = id, label = label, kind = PlaygroundFixtureKind.DYNAMIC, colors = colors, dynamic = detail)
    }

    private fun live(base: LiveInfo, id: String, label: String, colors: List<Int>): PlaygroundFixture {
        return PlaygroundFixture(id = id, label = label, kind = PlaygroundFixtureKind.LIVE, colors = colors, live = base)
    }

    private fun archive(title: String, coverUrl: String, jumpUrl: String, badgeText: String, badgeBg: String = "#5B8FF9") = ModuleDynamic.Major(
        type = "MAJOR_TYPE_ARCHIVE",
        archive = ModuleDynamic.Major.Archive(
            aid = title.hashCode().toString(),
            bvid = jumpUrl.substringAfterLast('/'),
            title = title,
            cover = coverUrl,
            desc = "用于检查视频卡片标题、封面和统计信息。",
            durationText = "12:34",
            jumpUrl = jumpUrl,
            stat = ModuleDynamic.Major.Stat(play = "12.3万", danmaku = "3456"),
            badge = badge(badgeText, "#FFFFFF", badgeBg),
        ),
    )

    private fun draw(coverUrl: String) = ModuleDynamic.Major(
        type = "MAJOR_TYPE_DRAW",
        draw = ModuleDynamic.Major.Draw(
            id = 3001L,
            items = listOf(
                ModuleDynamic.Major.Draw.DrawItem(960, 960, coverUrl),
                ModuleDynamic.Major.Draw.DrawItem(1280, 720, coverUrl),
                ModuleDynamic.Major.Draw.DrawItem(720, 1280, coverUrl),
            ),
        ),
    )

    private fun article(coverUrl: String) = ModuleDynamic.Major(
        type = "MAJOR_TYPE_ARTICLE",
        article = ModuleDynamic.Major.Article(
            id = "cv11005",
            title = "Compose playground 专栏卡片",
            desc = "用于调整标题、描述和多封面布局。",
            label = "114 阅读",
            jumpUrl = "https://www.bilibili.com/read/cv11005",
            covers = listOf(coverUrl, coverUrl),
        ),
    )

    private fun music(coverUrl: String) = ModuleDynamic.Major(
        type = "MAJOR_TYPE_MUSIC",
        music = ModuleDynamic.Major.Music(
            id = 11006L,
            title = "Compose playground 音乐卡片",
            cover = coverUrl,
            label = "音乐人精选",
            jumpUrl = "https://www.bilibili.com/audio/au11006",
        ),
    )

    private fun liveCard(coverUrl: String) = ModuleDynamic.Major(
        type = "MAJOR_TYPE_LIVE",
        live = ModuleDynamic.Major.Live(
            id = 11007L,
            title = "Compose playground 直播中",
            cover = coverUrl,
            descFirst = "游戏区",
            descSecond = "2.4万人看过",
            jumpUrl = "https://live.bilibili.com/11007",
            liveState = 1,
            reserveType = 0,
            badge = badge("直播中", "#FFFFFF", "#FF7A45"),
        ),
    )

    private fun liveRcmd(coverUrl: String) = ModuleDynamic.Major(
        type = "MAJOR_TYPE_LIVE_RCMD",
        liveRcmd = ModuleDynamic.Major.LiveRcmd(
            content = json.encodeToString(
                ModuleDynamic.Major.LiveRcmd.LiveRcmdContent(
                    type = 1,
                    livePlayInfo = ModuleDynamic.Major.LiveRcmd.LiveRcmdContent.LivePlayInfo(
                        uid = 10086L,
                        roomId = 11008L,
                        liveId = "11008",
                        liveStatus = 1,
                        title = "Compose playground 推荐直播",
                        cover = coverUrl,
                        parentAreaName = "游戏",
                        parentAreaId = 2,
                        areaName = "动作游戏",
                        areaId = 236,
                        link = "https://live.bilibili.com/11008",
                        room_type = 0,
                        liveScreenType = 0,
                        liveStartTime = 1773187200L,
                        playType = 0,
                        online = 27431,
                        roomPaidType = 0,
                        watchedShow = ModuleDynamic.Major.LiveRcmd.LiveRcmdContent.LivePlayInfo.WatchedShow(
                            num = 27431,
                            textSmall = "2.7万",
                            textLarge = "2.7万人看过",
                            icon = coverUrl,
                            iconLocation = "",
                            iconWeb = coverUrl,
                            switch = true,
                        ),
                    ),
                ),
            ),
            reserveType = 0,
        ),
    )

    private fun common(coverUrl: String) = ModuleDynamic.Major(
        type = "MAJOR_TYPE_COMMON",
        common = ModuleDynamic.Major.Common(
            id = "common11009",
            sketchId = "sketch11009",
            bizType = 1,
            title = "Compose playground 活动卡片",
            cover = coverUrl,
            desc = "副标题和徽章都来自真实 draw 路径。",
            label = "活动进行中",
            jumpUrl = "https://www.bilibili.com/blackboard/activity-playground.html",
            style = 1,
            badge = badge("活动", "#FFFFFF", "#597EF7"),
        ),
    )

    private fun opus(coverUrl: String) = ModuleDynamic.Major(
        type = "MAJOR_TYPE_OPUS",
        opus = ModuleDynamic.Major.Opus(
            title = "Compose playground Opus",
            summary = ModuleDynamic.ContentDesc(emptyList(), "这是新版图文动态的 summary，用来检查标题、正文和图片的组合布局。"),
            pics = listOf(
                ModuleDynamic.Major.Opus.DrawItem(1080, 1350, coverUrl),
                ModuleDynamic.Major.Opus.DrawItem(1080, 1080, coverUrl),
            ),
        ),
    )

    private fun pgc(coverUrl: String) = ModuleDynamic.Major(
        type = "MAJOR_TYPE_PGC",
        pgc = ModuleDynamic.Major.Pgc(
            type = 2,
            subType = 1,
            epid = 11011,
            seasonId = 22011,
            title = "Compose playground 番剧卡片",
            cover = coverUrl,
            jumpUrl = "https://www.bilibili.com/bangumi/play/ep11011",
            stat = ModuleDynamic.Major.Stat(play = "98.7万", danmaku = "4.6万"),
            badge = badge("番剧", "#FFFFFF", "#EB2F96"),
            evaluate = "这是番剧卡片的简介文本，用来检查多行评价文字的换行和截断。",
            areas = "剧情 / 科幻",
            pubTime = "2026-03-11",
            isFinish = 0,
            total = 12,
            rating = 9.7f,
        ),
    )

    private fun ugcSeason(coverUrl: String) = ModuleDynamic.Major(
        type = "MAJOR_TYPE_UGC_SEASON",
        ugcSeason = ModuleDynamic.Major.Archive(
            aid = "11012",
            bvid = "BV1playground3",
            title = "Compose playground 合集卡片",
            cover = coverUrl,
            desc = "合集副标题",
            durationText = "12:34",
            jumpUrl = "https://www.bilibili.com/video/BV1playground3",
            stat = ModuleDynamic.Major.Stat(play = "1.2万", danmaku = "789"),
            badge = badge("合集", "#FFFFFF", "#722ED1"),
        ),
    )

    private fun additionalCommon(coverUrl: String) = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_COMMON",
        common = ModuleDynamic.Additional.Common(
            idStr = "add-common-11013",
            title = "Compose playground 附加活动",
            cover = coverUrl,
            subType = "official_activity",
            desc1 = "第一行描述",
            desc2 = "第二行描述",
            headText = "相关活动",
            jumpUrl = "https://www.bilibili.com/blackboard/activity-playground.html",
            style = 1,
            button = actionButton("查看详情"),
        ),
    )

    private fun additionalReserve(coverUrl: String) = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_RESERVE",
        reserve = ModuleDynamic.Additional.Reserve(
            rid = 11014L,
            upMid = 10086L,
            title = "Compose playground 首映预告",
            reserveTotal = 3141,
            desc1 = additionalDesc("3141人预约", 1),
            desc2 = additionalDesc("03-20 20:00 开始", 0),
            desc3 = additionalDesc("点击查看预约详情", 0),
            premiere = ModuleDynamic.Additional.Reserve.Premiere(cover = coverUrl, online = "1.2万人想看"),
            state = 1,
            stype = 4,
            jumpUrl = "https://www.bilibili.com/video/BV1reserve11014",
            button = actionButton("立即预约"),
        ),
    )

    private fun additionalVote() = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_VOTE",
        vote = ModuleDynamic.Additional.Vote(
            uid = 10086L,
            voteId = 11015L,
            desc = "你更希望先调哪种卡片布局？",
            type = 1,
            status = 1,
            joinNum = 248,
            endTime = 1773792000L,
            choiceCnt = 1,
            defaultShare = 0,
        ),
    )

    private fun additionalUgc(coverUrl: String) = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_UGC",
        ugc = ModuleDynamic.Additional.Ugc(
            idStr = "11016",
            title = "Compose playground 相关视频",
            cover = coverUrl,
            descSecond = "播放 1.8万",
            duration = "08:21",
            headText = "相关视频",
            jumpUrl = "https://www.bilibili.com/video/BV1ugc11016",
            multiLine = "0",
        ),
    )

    private fun additionalGoods(coverUrl: String) = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_GOODS",
        goods = ModuleDynamic.Additional.Goods(
            headIcon = coverUrl,
            headText = "精选商品",
            jumpUrl = "https://mall.bilibili.com/playground",
            items = listOf(ModuleDynamic.Additional.Goods.GoodItem(
                id = "goods-11017",
                name = "Compose playground 周边",
                brief = "示意商品",
                cover = coverUrl,
                price = "59",
                jumpDesc = "去看看",
                jumpUrl = "https://mall.bilibili.com/playground/item/11017",
            )),
        ),
    )

    private fun additionalLottery() = ModuleDynamic.Additional(
        type = "ADDITIONAL_TYPE_UPOWER_LOTTERY",
        lottery = ModuleDynamic.Additional.Lottery(
            rid = 11018L,
            title = "Compose playground 充电抽奖",
            mid = 10086L,
            state = 1,
            desc = additionalDesc("3 月 20 日开奖", 0),
            button = actionButton("参与抽奖"),
            jumpUrl = "https://www.bilibili.com/blackboard/upower_lottery.html",
        ),
    )

    private fun badge(text: String, color: String, bgColor: String) = ModuleDynamic.Major.Badge(bgColor = bgColor, color = color, text = text)

    private fun actionButton(text: String) = ModuleDynamic.Additional.Button(
        type = 1,
        status = 1,
        jumpUrl = "https://www.bilibili.com",
        jumpStyle = ModuleDynamic.Additional.JumpStyle(text = text, iconUrl = ""),
        check = ModuleDynamic.Additional.Button.Check(text = text, iconUrl = null),
        uncheck = ModuleDynamic.Additional.Button.Check(text = text, iconUrl = null),
    )

    private fun additionalDesc(text: String, style: Int) = ModuleDynamic.Additional.Desc(text = text, style = style)
}