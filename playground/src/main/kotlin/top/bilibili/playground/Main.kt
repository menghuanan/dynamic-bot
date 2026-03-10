package top.bilibili.playground

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import top.bilibili.playground.ui.PlaygroundApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Draw Playground",
        state = rememberWindowState(width = 1480.dp, height = 920.dp),
    ) {
        PlaygroundApp()
    }
}
