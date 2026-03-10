package top.bilibili.playground.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import java.io.File

@Composable
fun PreviewPane(
    currentImagePath: String?,
    baselineImagePath: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PreviewImage(label = "Current", path = currentImagePath, modifier = Modifier.fillMaxSize())
        PreviewImage(label = "Baseline", path = baselineImagePath, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PreviewImage(label: String, path: String?, modifier: Modifier = Modifier) {
    val bitmap = loadBitmap(path)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label)
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = label)
        } else {
            Text(path ?: "No image loaded")
        }
    }
}

private fun loadBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    val file = File(path)
    if (!file.exists()) return null
    return Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
}