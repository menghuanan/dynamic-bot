package top.bilibili.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BiliCommandProcessorTest {
    private class Recorder : BiliCommandExecutor {
        var called: String = ""
        var unknownCommand: String = ""

        override suspend fun add() { called = "add" }
        override suspend fun remove() { called = "remove" }
        override suspend fun list() { called = "list" }
        override suspend fun color() { called = "color" }
        override suspend fun groups() { called = "groups" }
        override suspend fun group() { called = "group" }
        override suspend fun filter() { called = "filter" }
        override suspend fun template() { called = "template" }
        override suspend fun atall() { called = "atall" }
        override suspend fun config() { called = "config" }
        override suspend fun admin() { called = "admin" }
        override suspend fun blacklist() { called = "blacklist" }
        override suspend fun help() { called = "help" }
        override suspend fun unknown(command: String) {
            called = "unknown"
            unknownCommand = command
        }
    }

    @Test
    fun `dispatches all primary commands and aliases`() = kotlinx.coroutines.runBlocking {
        val cases = listOf(
            "/bili add 1" to "add",
            "/bili remove 1" to "remove",
            "/bili rm 1" to "remove",
            "/bili list" to "list",
            "/bili ls" to "list",
            "/bili color 1 #d3edfa" to "color",
            "/bili groups" to "groups",
            "/bili group list" to "group",
            "/bili filter list 1" to "filter",
            "/bili template list d" to "template",
            "/bili tpl list d" to "template",
            "/bili atall set all" to "atall",
            "/bili aa set all" to "atall",
            "/bili config show" to "config",
            "/bili cfg show" to "config",
            "/bili admin list" to "admin",
            "/bili blacklist list" to "blacklist",
            "/bili bl list" to "blacklist",
            "/bili help" to "help",
        )

        for ((input, expected) in cases) {
            val recorder = Recorder()
            BiliCommandProcessor.process(input, recorder)
            assertEquals(expected, recorder.called, "input=$input")
        }
    }

    @Test
    fun `dispatches help when command body is blank`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder()
        BiliCommandProcessor.process("/bili   ", recorder)
        assertEquals("help", recorder.called)
    }

    @Test
    fun `dispatches unknown for unsupported command`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder()
        BiliCommandProcessor.process("/bili 颜色 1 #d3edfa", recorder)
        assertEquals("unknown", recorder.called)
        assertEquals("颜色", recorder.unknownCommand)
    }
}
