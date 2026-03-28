package top.bilibili.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.math.max
import kotlin.math.roundToInt

internal const val LARGE_IMAGE_WIDTH_THRESHOLD = 4000
internal const val LARGE_IMAGE_HEIGHT_THRESHOLD = 3000
internal const val PREPROCESSED_IMAGE_MAX_LONG_EDGE = 2800

/**
 * 可从文件头快速识别的图片格式。
 */
internal enum class ImageHeaderFormat {
    PNG,
    JPEG,
    GIF,
    WEBP,
}

/**
 * 图片文件头中提取出的关键元信息。
 */
internal data class ImageHeaderInfo(
    val format: ImageHeaderFormat,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
)

private val imagePreprocessMutex = Mutex()

/**
 * 根据文件头快速探测图片格式、尺寸与是否为动图。
 */
internal fun inspectImageHeader(bytes: ByteArray): ImageHeaderInfo? {
    return inspectPngHeader(bytes)
        ?: inspectJpegHeader(bytes)
        ?: inspectGifHeader(bytes)
        ?: inspectWebpHeader(bytes)
}

/**
 * 判断图片是否需要在下载后做静态大图预处理。
 */
internal fun shouldPreprocessLargeStaticImage(
    header: ImageHeaderInfo,
    widthThreshold: Int = LARGE_IMAGE_WIDTH_THRESHOLD,
    heightThreshold: Int = LARGE_IMAGE_HEIGHT_THRESHOLD,
): Boolean {
    return !header.isAnimated &&
        header.width > widthThreshold &&
        header.height > heightThreshold
}

/**
 * 计算在最长边限制下的等比缩放尺寸。
 */
internal fun computeDownscaledSize(
    width: Int,
    height: Int,
    maxLongEdge: Int = PREPROCESSED_IMAGE_MAX_LONG_EDGE,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0) {
        return width to height
    }
    val longEdge = max(width, height)
    if (longEdge <= maxLongEdge) {
        return width to height
    }

    val scale = maxLongEdge.toDouble() / longEdge.toDouble()
    val scaledWidth = max(1, (width * scale).roundToInt())
    val scaledHeight = max(1, (height * scale).roundToInt())
    return scaledWidth to scaledHeight
}

/**
 * 返回原图对应的预处理缩放缓存路径。
 */
internal fun resizedVariantPath(originalPath: Path): Path {
    return originalPath.resolveSibling("resized_${originalPath.name}")
}

/**
 * 对下载后的图片字节做按需预处理，优先复用已存在的缩放缓存。
 */
internal suspend fun prepareDownloadedImageBytes(
    filePath: Path,
    originalBytes: ByteArray = filePath.readBytes(),
): ByteArray {
    val header = inspectImageHeader(originalBytes) ?: return originalBytes
    if (!shouldPreprocessLargeStaticImage(header)) {
        return originalBytes
    }

    val resizedPath = resizedVariantPath(filePath)
    if (resizedPath.exists()) {
        logger.info(
            "正在复用大图预处理缓存: 原文件=${filePath.name}, 缩放缓存=${resizedPath.name}, " +
                "原始尺寸=${header.width}x${header.height}",
        )
        return resizedPath.readBytes()
    }

    return imagePreprocessMutex.withLock {
        if (resizedPath.exists()) {
            logger.info(
                "在锁内复用大图预处理缓存: 原文件=${filePath.name}, 缩放缓存=${resizedPath.name}, " +
                    "原始尺寸=${header.width}x${header.height}",
            )
            return@withLock resizedPath.readBytes()
        }

        runCatching {
            val (targetWidth, targetHeight) = computeDownscaledSize(header.width, header.height)
            logger.warn(
                "正在预处理过大的静态图片: 文件名=${filePath.name}, " +
                    "原始尺寸=${header.width}x${header.height}, 目标尺寸=${targetWidth}x${targetHeight}, 格式=${header.format}",
            )
            // 锁内再次检查并执行缩放，是为了避免并发下载同一张大图时重复生成缓存文件。
            createResizedVariant(originalBytes, resizedPath, header, targetWidth, targetHeight)
            resizedPath.readBytes()
        }.getOrElse { error ->
            logger.warn(
                "大图预处理失败，正在回退原图: 文件名=${filePath.name}, " +
                    "原始尺寸=${header.width}x${header.height}, 原因=${error.message}",
                error,
            )
            originalBytes
        }
    }
}

/**
 * 将原图缩放后编码为新的缓存变体文件。
 */
private fun createResizedVariant(
    originalBytes: ByteArray,
    resizedPath: Path,
    header: ImageHeaderInfo,
    targetWidth: Int,
    targetHeight: Int,
) {
    Image.makeFromEncoded(originalBytes).use { originalImage ->
        val surface = Surface.makeRasterN32Premul(targetWidth, targetHeight)
        try {
            val srcRect = Rect.makeWH(originalImage.width.toFloat(), originalImage.height.toFloat())
            val dstRect = Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat())
            val paint = Paint()
            try {
                surface.canvas.drawImageRect(
                    originalImage,
                    srcRect,
                    dstRect,
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                    paint,
                    false,
                )
            } finally {
                paint.close()
            }

            val resizedImage = surface.makeImageSnapshot()
            try {
                val encoded = resizedImage.encodeToData(encodedFormatFor(header.format))
                    ?: error("Failed to encode resized image")
                try {
                    resizedPath.writeBytes(encoded.bytes)
                } finally {
                    encoded.close()
                }
            } finally {
                resizedImage.close()
            }
        } finally {
            surface.close()
        }
    }
}

/**
 * 将识别到的文件头格式映射为可输出的编码格式。
 */
private fun encodedFormatFor(format: ImageHeaderFormat): EncodedImageFormat {
    return when (format) {
        ImageHeaderFormat.PNG -> EncodedImageFormat.PNG
        ImageHeaderFormat.JPEG -> EncodedImageFormat.JPEG
        ImageHeaderFormat.WEBP -> EncodedImageFormat.WEBP
        // GIF 缩放后统一编码为 PNG，是为了避免静态化过程中引入额外的 GIF 编码复杂度。
        ImageHeaderFormat.GIF -> EncodedImageFormat.PNG
    }
}

/**
 * 解析 PNG 文件头。
 */
private fun inspectPngHeader(bytes: ByteArray): ImageHeaderInfo? {
    if (bytes.size < 24) return null
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (!bytes.copyOfRange(0, 8).contentEquals(signature)) return null

    val width = readInt32BE(bytes, 16)
    val height = readInt32BE(bytes, 20)
    val animated = bytes.containsAscii("acTL")
    return ImageHeaderInfo(ImageHeaderFormat.PNG, width, height, animated)
}

/**
 * 解析 GIF 文件头。
 */
private fun inspectGifHeader(bytes: ByteArray): ImageHeaderInfo? {
    if (bytes.size < 10) return null
    val header = String(bytes.copyOfRange(0, 6))
    if (header != "GIF87a" && header != "GIF89a") return null

    val width = readUInt16LE(bytes, 6)
    val height = readUInt16LE(bytes, 8)
    val imageDescriptorCount = bytes.count { it == 0x2C.toByte() }
    val animated = imageDescriptorCount > 1 || bytes.containsAscii("NETSCAPE2.0")
    return ImageHeaderInfo(ImageHeaderFormat.GIF, width, height, animated)
}

/**
 * 解析 JPEG 文件头中的尺寸信息。
 */
private fun inspectJpegHeader(bytes: ByteArray): ImageHeaderInfo? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null

    var offset = 2
    while (offset + 1 < bytes.size) {
        while (offset < bytes.size && bytes[offset] != 0xFF.toByte()) {
            offset++
        }
        if (offset >= bytes.size) return null

        while (offset < bytes.size && bytes[offset] == 0xFF.toByte()) {
            offset++
        }
        if (offset >= bytes.size) return null

        val marker = bytes[offset].toInt() and 0xFF
        offset++

        when {
            marker == 0xD9 || marker == 0xDA -> return null
            marker == 0x01 || marker in 0xD0..0xD7 -> continue
        }

        if (offset + 1 >= bytes.size) return null
        val segmentLength = readUInt16BE(bytes, offset)
        if (segmentLength < 2 || offset + segmentLength > bytes.size) return null

        if (marker in JPEG_SOF_MARKERS) {
            if (offset + 6 >= bytes.size) return null
            val height = readUInt16BE(bytes, offset + 3)
            val width = readUInt16BE(bytes, offset + 5)
            return ImageHeaderInfo(ImageHeaderFormat.JPEG, width, height, false)
        }

        offset += segmentLength
    }

    return null
}

/**
 * 解析 WebP 扩展头中的尺寸与动画标记。
 */
private fun inspectWebpHeader(bytes: ByteArray): ImageHeaderInfo? {
    if (bytes.size < 30) return null
    if (!bytes.containsAsciiAt(0, "RIFF")) return null
    if (!bytes.containsAsciiAt(8, "WEBP")) return null
    if (!bytes.containsAsciiAt(12, "VP8X")) return null

    val flags = bytes[20].toInt() and 0xFF
    val animated = flags and 0x02 != 0
    val width = 1 + readUInt24LE(bytes, 24)
    val height = 1 + readUInt24LE(bytes, 27)
    return ImageHeaderInfo(ImageHeaderFormat.WEBP, width, height, animated)
}

/**
 * 按大端序读取 32 位整数。
 */
private fun readInt32BE(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}

/**
 * 按大端序读取 16 位无符号整数。
 */
private fun readUInt16BE(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 8) or
        (bytes[offset + 1].toInt() and 0xFF)
}

/**
 * 按小端序读取 16 位无符号整数。
 */
private fun readUInt16LE(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

/**
 * 按小端序读取 24 位无符号整数。
 */
private fun readUInt24LE(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}

/**
 * 判断字节数组中是否包含指定 ASCII 文本片段。
 */
private fun ByteArray.containsAscii(value: String): Boolean {
    val needle = value.encodeToByteArray()
    if (needle.isEmpty() || needle.size > size) return false

    for (index in 0..size - needle.size) {
        var matched = true
        for (needleIndex in needle.indices) {
            if (this[index + needleIndex] != needle[needleIndex]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}

/**
 * 判断字节数组在指定偏移处是否匹配指定 ASCII 文本。
 */
private fun ByteArray.containsAsciiAt(offset: Int, value: String): Boolean {
    val needle = value.encodeToByteArray()
    if (offset < 0 || offset + needle.size > size) return false
    return copyOfRange(offset, offset + needle.size).contentEquals(needle)
}

private val JPEG_SOF_MARKERS = setOf(
    0xC0, 0xC1, 0xC2, 0xC3,
    0xC5, 0xC6, 0xC7,
    0xC9, 0xCA, 0xCB,
    0xCD, 0xCE, 0xCF,
)
