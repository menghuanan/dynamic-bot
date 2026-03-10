package top.bilibili.playground

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import top.bilibili.playground.ui.PlaygroundApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Draw Playground",
    ) {
        PlaygroundApp()
    }
}
