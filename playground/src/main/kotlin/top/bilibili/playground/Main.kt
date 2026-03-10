package top.bilibili.playground

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Draw Playground",
    ) {
        MaterialTheme {
            Text("Draw Playground")
        }
    }
}