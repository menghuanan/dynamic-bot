package top.bilibili.tasker

import kotlinx.coroutines.runBlocking
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.EnableConfig
import top.bilibili.LinkResolveConfig
import top.bilibili.data.BASE_DYNAMIC
import top.bilibili.data.DynamicItem
import top.bilibili.data.DynamicType
import top.bilibili.data.ModuleAuthor
import top.bilibili.data.ModuleDynamic
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DynamicMessageTaskerForwardDisplayNormalizationTest {
    @BeforeTest
    fun setRuntimeConfig() {
        val configField = BiliConfigManager::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(
            BiliConfigManager,
            BiliConfig(
                enableConfig = EnableConfig(drawEnable = false, pushDrawEnable = false),
                linkResolveConfig = LinkResolveConfig(drawEnable = false),
            )
        )
    }

    @Test
    fun `buildMessage should normalize forwarded article opus before collecting images`() = runBlocking {
        val outer = forwardDynamic(
            id = "1182219246111817744",
            orig = articleOpusDynamic(
                id = "1182215530975592489",
                rid = "46986817",
                covers = listOf("cover-a", "cover-b"),
            )
        )

        val message = DynamicMessageTasker.run { outer.buildMessage() }

        assertEquals(DynamicType.DYNAMIC_TYPE_FORWARD, message.type)
        assertEquals(listOf("cover-a", "cover-b"), message.images)
        assertEquals("$BASE_DYNAMIC/${outer.did}", message.links?.get(0)?.value)
        assertEquals("$BASE_DYNAMIC/${outer.orig!!.did}", message.links?.get(1)?.value)

        val normalizedMajor = outer.orig!!.modules.moduleDynamic.major!!
        assertEquals("MAJOR_TYPE_ARTICLE", normalizedMajor.type)
        assertEquals(listOf("cover-a", "cover-b"), normalizedMajor.article?.covers)
        assertNull(normalizedMajor.opus)
    }

    @Test
    fun `buildMessage should normalize deepest forwarded article opus recursively`() = runBlocking {
        val outer = forwardDynamic(
            id = "1182219246111817744",
            orig = forwardDynamic(
                id = "1182218000000000000",
                orig = articleOpusDynamic(
                    id = "1182215530975592489",
                    rid = "46986817",
                    covers = listOf("deep-cover"),
                )
            )
        )

        val message = DynamicMessageTasker.run { outer.buildMessage() }

        assertEquals(DynamicType.DYNAMIC_TYPE_FORWARD, message.type)
        assertEquals(listOf("deep-cover"), message.images)
        assertEquals("$BASE_DYNAMIC/${outer.did}", message.links?.get(0)?.value)
        assertEquals("$BASE_DYNAMIC/${outer.orig!!.did}", message.links?.get(1)?.value)

        val normalizedMajor = outer.orig!!.orig!!.modules.moduleDynamic.major!!
        assertEquals("MAJOR_TYPE_ARTICLE", normalizedMajor.type)
        assertEquals(listOf("deep-cover"), normalizedMajor.article?.covers)
        assertNull(normalizedMajor.opus)
    }

    private fun forwardDynamic(id: String, orig: DynamicItem): DynamicItem {
        return DynamicItem(
            typeStr = DynamicType.DYNAMIC_TYPE_FORWARD.name,
            basic = DynamicItem.DynamicBasic(commentIdStr = id, commentType = 11, ridStr = id),
            idStr = id,
            modules = DynamicItem.Modules(
                moduleAuthor = author(mid = id.takeLast(6).toLong()),
                moduleDynamic = ModuleDynamic(
                    desc = content("forward-desc-$id"),
                ),
            ),
            orig = orig,
        )
    }

    private fun articleOpusDynamic(id: String, rid: String, covers: List<String>): DynamicItem {
        return DynamicItem(
            typeStr = DynamicType.DYNAMIC_TYPE_ARTICLE.name,
            basic = DynamicItem.DynamicBasic(commentIdStr = rid, commentType = 12, ridStr = rid),
            idStr = id,
            modules = DynamicItem.Modules(
                moduleAuthor = author(mid = id.takeLast(6).toLong()),
                moduleDynamic = ModuleDynamic(
                    major = ModuleDynamic.Major(
                        type = "MAJOR_TYPE_OPUS",
                        opus = ModuleDynamic.Major.Opus(
                            title = "opus-title-$rid",
                            summary = content("opus-summary-$rid"),
                            pics = covers.mapIndexed { index, cover ->
                                ModuleDynamic.Major.Opus.DrawItem(
                                    width = 1200 + index,
                                    height = 800 + index,
                                    src = cover,
                                )
                            },
                        ),
                    ),
                ),
            ),
        )
    }

    private fun author(mid: Long): ModuleAuthor {
        return ModuleAuthor(
            mid = mid,
            name = "author-$mid",
            face = "https://example.com/$mid.png",
        )
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
