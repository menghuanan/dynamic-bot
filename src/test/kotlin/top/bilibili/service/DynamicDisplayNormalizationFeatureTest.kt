package top.bilibili.service

import top.bilibili.data.DynamicItem
import top.bilibili.data.DynamicType
import top.bilibili.data.ModuleAuthor
import top.bilibili.data.ModuleDynamic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DynamicDisplayNormalizationFeatureTest {
    @Test
    fun `normalization should allow overriding article id for opus article while preserving decorate and additional`() {
        val item = articleOpusDynamic(
            dynamicId = "1183668934980665366",
            rid = "47068592",
            covers = listOf("cover-a"),
        )

        val originalDecorate = item.modules.moduleAuthor.decorate
        val originalAdditional = item.modules.moduleDynamic.additional

        item.normalizeArticleOpusDisplayTree(articleIdOverride = "47068592")

        val major = item.modules.moduleDynamic.major!!
        assertEquals("MAJOR_TYPE_ARTICLE", major.type)
        assertEquals("47068592", major.article?.id)
        assertEquals(listOf("cover-a"), major.article?.covers)
        assertNull(major.opus)
        assertSame(originalDecorate, item.modules.moduleAuthor.decorate)
        assertSame(originalAdditional, item.modules.moduleDynamic.additional)
    }

    private fun articleOpusDynamic(dynamicId: String, rid: String, covers: List<String>): DynamicItem {
        return DynamicItem(
            typeStr = DynamicType.DYNAMIC_TYPE_ARTICLE.name,
            basic = DynamicItem.DynamicBasic(commentIdStr = rid, commentType = 12, ridStr = rid),
            idStr = dynamicId,
            modules = DynamicItem.Modules(
                moduleAuthor = authorFixture(),
                moduleDynamic = ModuleDynamic(
                    additional = additionalFixture(),
                    major = ModuleDynamic.Major(
                        type = "MAJOR_TYPE_OPUS",
                        opus = ModuleDynamic.Major.Opus(
                            title = "opus-title-$rid",
                            summary = content("opus-summary-$rid"),
                            pics = covers.map { cover ->
                                ModuleDynamic.Major.Opus.DrawItem(
                                    width = 1280,
                                    height = 720,
                                    src = cover,
                                )
                            },
                        ),
                    ),
                ),
            ),
        )
    }

    private fun authorFixture(): ModuleAuthor {
        val decorate = ModuleAuthor.Decorate(
            id = 1705928214002,
            type = 3,
            name = "fan-card",
            cardUrl = "https://example.com/fan-card.png",
            jumpUrl = "https://example.com/fan-card",
            fan = ModuleAuthor.Decorate.Fan(
                color = "#3DC5EC",
                isFan = true,
                numStr = "000001",
                number = 1,
            ),
        )
        return ModuleAuthor(
            mid = 3493265644980448,
            name = "蔚蓝档案",
            face = "https://example.com/face.png",
            decorate = decorate,
        )
    }

    private fun additionalFixture(): ModuleDynamic.Additional {
        val common = ModuleDynamic.Additional.Common(
            idStr = "109864",
            title = "蔚蓝档案",
            cover = "https://example.com/game.png",
            subType = "game",
            desc1 = "角色扮演/青春/3D",
            desc2 = "限时复刻活动现已开启",
            headText = "相关游戏",
            jumpUrl = "https://example.com/game",
            style = 1,
            button = ModuleDynamic.Additional.Button(
                type = 1,
                jumpUrl = "https://example.com/game",
                jumpStyle = ModuleDynamic.Additional.JumpStyle(
                    text = "进入",
                    iconUrl = "",
                ),
            ),
        )
        val additional = ModuleDynamic.Additional(
            type = "ADDITIONAL_TYPE_COMMON",
            common = common,
        )
        assertNotNull(additional.common)
        return additional
    }

    private fun content(text: String): ModuleDynamic.ContentDesc {
        return ModuleDynamic.ContentDesc(
            richTextNodes = listOf(
                ModuleDynamic.ContentDesc.RichTextNode(
                    type = "RICH_TEXT_NODE_TYPE_TEXT",
                    origText = text,
                    text = text,
                )
            ),
            text = text,
        )
    }
}
