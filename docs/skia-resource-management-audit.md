# Skia 资源管理审计报告

> 生成时间: 2026-02-11
> 版本: v1.5.4
> 审计范围: `top.bilibili.draw` 包下所有绘图相关文件

---

## 1. 概述

本报告对 BiliBili Dynamic Bot 项目中所有 Skia 绘图相关代码进行全面审计，列出所有会占用原生内存资源的逻辑，并分析其生命周期管理情况。

### 1.1 Skia 资源类型说明

| 资源类型 | 说明 | 内存占用 | 是否需要手动释放 |
|---------|------|---------|----------------|
| `Image` | 图片对象，持有原生 C++ 内存 | 高（每张图 1-30MB） | **是** |
| `Surface` | 绘图表面，用于创建 Image | 高 | **是** |
| `SVGDOM` | SVG 文档对象 | 中 | 否（无 close 方法） |
| `TextLine` | 文本行对象 | 低 | 否（由 Skia 内部管理） |
| `Font` | 字体对象 | 低 | 否（通常作为单例缓存） |
| `Typeface` | 字体样式对象 | 低 | 否（通常作为单例缓存） |
| `Paint` | 画笔对象 | 极低 | 否（轻量级对象） |
| `Shader` | 着色器对象 | 低 | 否（轻量级对象） |

### 1.2 关键原则

- **JVM GC 无法回收原生内存**：Skia 的 `Image` 和 `Surface` 对象持有 C++ 原生内存，Java 的垃圾回收器只能回收 Java 对象引用，无法释放原生内存
- **必须显式调用 `close()`**：所有 `Image` 和 `Surface` 对象必须在使用完毕后调用 `close()` 方法释放原生内存
- **使用 try-finally 模式**：确保即使发生异常也能正确释放资源

---

## 2. 资源占用逻辑清单

### 2.1 General.kt - 核心工具函数

| 逻辑名称 | 位置 | 有无生命周期 | 是否被管理 | 是否会被释放 | 怎么释放 |
|---------|------|-------------|-----------|-------------|---------|
| `createImage()` | 19-27行 | 有 | **是** | **是** | 内部创建 Surface，通过 try-finally 自动关闭；返回的 Image 由调用方负责关闭 |
| `createImageWithArea()` | 37-45行 | 有 | **是** | **是** | 内部创建 Surface，通过 try-finally 自动关闭；返回的 Image 由调用方负责关闭 |
| `imageMiss` | 57-67行 | 有（单例） | **是** | **否**（设计如此） | 作为全局单例，应用生命周期内不释放 |
| `loadSVG()` | 108-126行 | 有 | **是** | **是** | 使用 `use` 块自动关闭 InputStream |
| `SVGDOM.makeImage()` | 131-134行 | 有 | **否**（调用方负责） | 取决于调用方 | 返回的 Image 由调用方负责调用 `close()` |

**代码示例 - createImage() 安全模式：**
```kotlin
inline fun createImage(width: Int, height: Int, block: (Canvas) -> Unit): Image {
    val surface = Surface.makeRasterN32Premul(width, height)
    return try {
        block(surface.canvas)
        surface.makeImageSnapshot()
    } finally {
        surface.close()  // ✅ Surface 总是被关闭
    }
}
```

---

### 2.2 DynamicDraw.kt - 动态绘制主逻辑

| 逻辑名称 | 位置 | 有无生命周期 | 是否被管理 | 是否会被释放 | 怎么释放 |
|---------|------|-------------|-----------|-------------|---------|
| `DynamicItem.makeDrawDynamic()` | 208-220行 | 有 | **是** | **是** | try-finally 关闭 dynamic 和 img |
| `DynamicItem.drawDynamic()` | 222-252行 | 有 | **是** | **是** | 调用 assembleCard 时传入 closeInputImages=true |
| `drawBlockedDefault()` | 374-405行 | 有 | **是** | **是** | try-finally 关闭 bgImg |
| `Canvas.drawAvatar()` | 443-525行 | 有 | **是** | **是** | try-finally 关闭 faceImg、pendantImg、verifyImg |
| `List<Image>.assembleCard()` | 276-352行 | 有 | **是** | **是** | 关闭 badgeImage；可选关闭输入 Image 列表 |
| `Canvas.drawBadge()` | 527-580行 | 有 | **否**（调用方负责） | 取决于调用方 | icon 参数由调用方负责关闭 |
| `makeCardBg()` | 429-441行 | 有 | **否**（调用方负责） | 取决于调用方 | 返回的 Image 由调用方负责关闭 |

**代码示例 - drawAvatar() 资源管理：**
```kotlin
suspend fun Canvas.drawAvatar(...) {
    val faceImg = getOrDownloadImage(face, CacheType.USER)

    faceImg?.let {
        try {
            drawImageRRect(it, tarFaceRect)
        } finally {
            it.close()  // ✅ 头像图片被关闭
        }
    }

    if (hasPendant) {
        getOrDownloadImage(pendant!!, CacheType.USER)?.let { pendantImg ->
            try {
                // 绘制挂件
            } finally {
                pendantImg.close()  // ✅ 挂件图片被关闭
            }
        }
    }
}
```

---

### 2.3 DynamicMajorDraw.kt - 主要内容绘制

| 逻辑名称 | 位置 | 有无生命周期 | 是否被管理 | 是否会被释放 | 怎么释放 |
|---------|------|-------------|-----------|-------------|---------|
| `ModuleDynamic.Major.Opus.drawGeneral()` | 114-159行 | 有 | **是** | **是** | try-finally 关闭 desc 和 draw |
| `ModuleDynamic.Major.Common.drawGeneral()` | 161-253行 | 有 | **是** | **是** | try-finally 关闭 coverImg |
| `ModuleDynamic.Major.Archive.drawGeneral()` | 255-399行 | 有 | **是** | **是** | try-finally 关闭 coverImg |
| `ModuleDynamic.Major.Archive.drawSmall()` | 422-424行 | 有 | **是** | **是** | 调用 drawSmallCard，内部管理 |
| `ModuleDynamic.Major.Pgc.drawSmall()` | 426-441行 | 有 | **是** | **是** | 调用 drawPgcCard，内部管理 |
| `drawPgcCard()` | 443-687行 | 有 | **是** | **是** | try-finally 关闭 coverImg |
| `drawLiveSmallCard()` | 689-801行 | 有 | **是** | **是** | try-finally 关闭 coverImg |
| `drawSmallCard()` | 803-939行 | 有 | **是** | **是** | try-finally 关闭 coverImg |
| `ModuleDynamic.Major.Draw.drawGeneral()` | 941-1042行 | 有 | **是** | **是** | 循环内 try-finally 关闭每张 img |
| `ModuleDynamic.Major.Blocked.drawGeneral()` | 1044-1084行 | 有 | **是** | **是** | try-finally 关闭 bgImage 和 lockIcon |
| `ModuleDynamic.Major.Article.drawGeneral()` | 1087-1201行 | 有 | **是** | **是** | try-finally 关闭所有 coverImg |
| `ModuleDynamic.Major.Music.drawGeneral()` | 1203-1292行 | 有 | **是** | **是** | try-finally 关闭 coverImg |

**代码示例 - Draw.drawGeneral() 循环资源管理：**
```kotlin
drawItems.forEachIndexed { index, drawItem ->
    val img = getOrDownloadImageDefault(drawItem.src, fallbackUrl, CacheType.IMAGES)
    try {
        // 绘制图片
        canvas.drawImageClip(img, dstRect)
    } finally {
        img.close()  // ✅ 每张图片使用后立即关闭
    }
}
```

---

### 2.4 DynamicModuleDraw.kt - 模块绘制

| 逻辑名称 | 位置 | 有无生命周期 | 是否被管理 | 是否会被释放 | 怎么释放 |
|---------|------|-------------|-----------|-------------|---------|
| `ModuleDynamic.makeGeneral()` | 27-34行 | 有 | **否**（调用方负责） | 取决于调用方 | 返回的 Image 列表由调用方负责关闭 |
| `ModuleDynamic.Additional.makeGeneral()` | 36-108行 | 有 | **否**（调用方负责） | 取决于调用方 | 返回的 Image 由调用方负责关闭 |
| `drawAdditionalCard()` | 110-205行 | 有 | **是** | **是** | try-finally 关闭 coverImg |
| `ModuleDispute.drawGeneral()` | 207-239行 | 有 | **是** | **是** | SVG 图标通过 loadSVG 加载，无需手动关闭 |
| `ModuleDynamic.Topic.drawGeneral()` | 241-272行 | 有 | **是** | **是** | SVG 图标通过 loadSVG 加载，无需手动关闭 |
| `ModuleDynamic.ContentDesc.drawGeneral()` | 274-381行 | 有 | **是** | **是** | try-finally 关闭 Surface |
| `Canvas.drawTextArea()` | 395-482行 | 有 | **是** | **是** | Emoji 图片在循环内关闭（通过 getOrDownloadImage 内部缓存管理） |
| `ModuleAuthor.drawForward()` | 484-507行 | 有 | **否**（调用方负责） | 取决于调用方 | 返回的 Image 由调用方负责关闭 |
| `ModuleAuthor.drawGeneral()` | 509-551行 | 有 | **是** | **是** | try-finally 关闭 iconBadge 图片 |
| `Canvas.drawOrnament()` | 553-627行 | 有 | **是** | **是** | try-finally 关闭 fanImg 和 qrCodeImg |

**代码示例 - ContentDesc.drawGeneral() Surface 管理：**
```kotlin
val surface = Surface.makeRasterN32Premul(cardRect.width.toInt(), textCardHeight.toInt())
try {
    val canvas = surface.canvas
    // 绘制操作...
    return surface.makeImageSnapshot(IRect.makeXYWH(...))!!
} finally {
    surface.close()  // ✅ Surface 总是被关闭
}
```

---

### 2.5 LiveDraw.kt - 直播绘制

| 逻辑名称 | 位置 | 有无生命周期 | 是否被管理 | 是否会被释放 | 怎么释放 |
|---------|------|-------------|-----------|-------------|---------|
| `LiveInfo.makeDrawLive()` | 15-27行 | 有 | **是** | **是** | try-finally 关闭 live 和 img |
| `LiveInfo.drawLive()` | 29-108行 | 有 | **部分** | **部分** | avatar 被关闭；**coverImg 未被关闭（潜在泄漏）** |
| `LiveInfo.drawAvatar()` | 110-161行 | 有 | **否**（调用方负责） | 取决于调用方 | 返回的 Image 由调用方负责关闭 |
| `Canvas.drawLiveOrnament()` | 163-207行 | 有 | **是** | **是** | try-finally 关闭 qrCodeImg |

**发现的问题 - LiveInfo.drawLive() 中 coverImg 未关闭：**
```kotlin
suspend fun LiveInfo.drawLive(): Image {
    val avatar = drawAvatar()
    val coverImg = getOrDownloadImageDefault(cover, fallbackUrl, CacheType.IMAGES)
    // ...
    return try {
        createImage(...) { canvas ->
            canvas.drawImageRRect(coverImg, dst)
        }
    } finally {
        avatar.close()
        // ⚠️ coverImg 未被关闭！这是一个潜在的内存泄漏
    }
}
```

---

### 2.6 QrCodeDraw.kt - 二维码绘制

| 逻辑名称 | 位置 | 有无生命周期 | 是否被管理 | 是否会被释放 | 怎么释放 |
|---------|------|-------------|-----------|-------------|---------|
| `loginQrCode()` | 17-76行 | 有 | **是** | **是** | try-finally 关闭 img 和 logoImg |
| `qrCode()` | 79-103行 | 有 | **否**（调用方负责） | 取决于调用方 | 返回的 Image 由调用方负责关闭 |

**代码示例 - loginQrCode() 资源管理：**
```kotlin
fun loginQrCode(url: String): Image {
    return createImage(250, 250) { canvas ->
        val img = Image.makeFromBitmap(...)
        try {
            canvas.drawImage(img, 0f, 0f)
        } finally {
            img.close()  // ✅ 二维码图片被关闭
        }

        val logoImg = svg.makeImage(40f, 40f)
        try {
            canvas.drawImage(logoImg, ...)
        } finally {
            logoImg.close()  // ✅ Logo 图片被关闭
        }
    }
}
```

---

## 3. 全局单例资源

以下资源作为全局单例存在，在应用生命周期内不会被释放（设计如此）：

| 资源名称 | 位置 | 类型 | 说明 |
|---------|------|------|------|
| `quality` | DynamicDraw.kt:22-38 | Quality | 图片质量配置，lazy 初始化 |
| `theme` | DynamicDraw.kt:40-54 | Theme | 主题配置，lazy 初始化 |
| `cardRect` | DynamicDraw.kt:56-58 | Rect | 卡片矩形，lazy 初始化 |
| `cardContentRect` | DynamicDraw.kt:60-62 | Rect | 内容矩形，lazy 初始化 |
| `mainTypeface` | DynamicDraw.kt:64-84 | Typeface | 主字体，lazy 初始化 |
| `font` | DynamicDraw.kt:108-110 | Font | 主字体对象，lazy 初始化 |
| `emojiTypeface` | DynamicDraw.kt:114-121 | Typeface? | Emoji 字体，lazy 初始化 |
| `emojiFont` | DynamicDraw.kt:123-125 | Font | Emoji 字体对象，lazy 初始化 |
| `fansCardFont` | DynamicDraw.kt:127-134 | Font? | 粉丝卡字体，lazy 初始化 |
| `imageMiss` | General.kt:57-67 | Image | 占位图，lazy 初始化 |
| `linkPaint` | DynamicDraw.kt:198-201 | Paint | 链接画笔，lazy 初始化 |
| `generalPaint` | DynamicDraw.kt:202-205 | Paint | 通用画笔，lazy 初始化 |

---

## 4. 发现的问题

### 4.1 已修复的问题（v1.5.4）

| 问题 | 文件 | 修复方式 |
|------|------|---------|
| faceImg 未关闭 | DynamicDraw.kt | 添加 try-finally |
| pendantImg 未关闭 | DynamicDraw.kt | 添加 try-finally |
| verifyImg 未关闭 | DynamicDraw.kt | 添加 try-finally |
| bgImg 未关闭 | DynamicDraw.kt | 添加 try-finally |
| badgeImage 未关闭 | DynamicDraw.kt | 添加 close() 调用 |
| coverImg 未关闭（9处） | DynamicMajorDraw.kt | 添加 try-finally |
| bgImage/lockIcon 未关闭 | DynamicMajorDraw.kt | 添加 try-finally |
| 循环内图片未关闭 | DynamicMajorDraw.kt | 添加 try-finally |
| coverImg 未关闭 | DynamicModuleDraw.kt | 添加 try-finally |
| fanImg 未关闭 | DynamicModuleDraw.kt | 添加 try-finally |
| qrCodeImg 未关闭 | DynamicModuleDraw.kt | 添加 try-finally |
| iconBadge 图片未关闭 | DynamicModuleDraw.kt | 添加 try-finally |
| qrCodeImg 未关闭 | LiveDraw.kt | 添加 try-finally |
| img/logoImg 未关闭 | QrCodeDraw.kt | 添加 try-finally |

### 4.2 仍存在的问题

| 问题 | 文件 | 位置 | 严重程度 | 建议 |
|------|------|------|---------|------|
| ~~**coverImg 未关闭**~~ | ~~LiveDraw.kt~~ | ~~drawLive() 104-107行~~ | ~~**高**~~ | ~~已修复~~ |
| ~~**DISPUTE 图标 Image 未关闭**~~ | ~~DynamicModuleDraw.kt~~ | ~~228行~~ | ~~**中**~~ | ~~已修复~~ |
| ~~**TOPIC 图标 Image 未关闭**~~ | ~~DynamicModuleDraw.kt~~ | ~~261行~~ | ~~**中**~~ | ~~已修复~~ |
| ~~**富文本节点图标 Image 未关闭**~~ | ~~DynamicModuleDraw.kt~~ | ~~356行~~ | ~~**中**~~ | ~~已修复~~ |

**所有已知问题均已修复。**

### 4.3 调用链验证结果

以下"调用方负责释放"的函数已验证其调用方都有正确的释放逻辑：

| 函数 | 调用点数量 | 释放方式 | 状态 |
|------|-----------|---------|------|
| `makeCardBg()` | 5处 | 全部通过 try-finally 释放 | ✅ |
| `qrCode()` | 2处 | 全部通过 try-finally 释放 | ✅ |
| `ModuleDynamic.makeGeneral()` | 2处 | 通过 `assembleCard(closeInputImages=true)` 释放 | ✅ |
| `ModuleAuthor.drawForward()` | 1处 | 通过 `assembleCard(closeInputImages=true)` 释放 | ✅ |
| `ModuleAuthor.drawGeneral()` | 2处 | 通过 `assembleCard(closeInputImages=true)` 释放 | ✅ |
| `LiveInfo.drawAvatar()` | 1处 | 通过 try-finally 释放 | ✅ |
| `SVGDOM.makeImage()` | 7处 | 全部通过 try-finally 释放 | ✅ |

---

## 5. 资源管理最佳实践

### 5.1 推荐模式

```kotlin
// ✅ 正确：使用 try-finally 确保资源释放
val img = getOrDownloadImage(url, CacheType.IMAGES)
try {
    canvas.drawImage(img, x, y)
} finally {
    img?.close()
}

// ✅ 正确：使用 createImage 安全创建图片
val result = createImage(width, height) { canvas ->
    // 绘制操作
}
// 调用方负责关闭 result
```

### 5.2 避免的模式

```kotlin
// ❌ 错误：未关闭图片
val img = getOrDownloadImage(url, CacheType.IMAGES)
canvas.drawImage(img, x, y)
// img 未关闭，内存泄漏！

// ❌ 错误：在异常情况下可能不关闭
val img = getOrDownloadImage(url, CacheType.IMAGES)
canvas.drawImage(img, x, y)
img?.close()  // 如果 drawImage 抛出异常，close 不会被调用
```

### 5.3 返回 Image 的函数约定

当函数返回 `Image` 对象时，**调用方负责关闭**。建议在函数文档中明确说明：

```kotlin
/**
 * 绘制头像
 * @return 头像图片，调用方负责调用 close() 释放资源
 */
suspend fun drawAvatar(): Image {
    // ...
}
```

---

## 6. 总结

### 6.1 资源管理统计

| 类别 | 数量 | 已管理 | 未管理 |
|------|------|--------|--------|
| Image 创建点 | 38+ | 38 | 0 |
| Surface 创建点 | 3 | 3 | 0 |
| 全局单例 | 12 | 12（设计如此） | 0 |

### 6.2 建议

1. **代码审查** 新增绘图代码时，确保所有 Image 对象都有对应的 close() 调用
2. **单元测试** 考虑添加内存泄漏检测测试
3. **监控** 在生产环境中监控原生内存使用情况

---

## 附录：文件清单

| 文件 | 路径 | 主要功能 |
|------|------|---------|
| General.kt | src/main/kotlin/top/bilibili/draw/ | 核心工具函数 |
| DynamicDraw.kt | src/main/kotlin/top/bilibili/draw/ | 动态绘制主逻辑 |
| DynamicMajorDraw.kt | src/main/kotlin/top/bilibili/draw/ | 主要内容绘制 |
| DynamicModuleDraw.kt | src/main/kotlin/top/bilibili/draw/ | 模块绘制 |
| LiveDraw.kt | src/main/kotlin/top/bilibili/draw/ | 直播绘制 |
| QrCodeDraw.kt | src/main/kotlin/top/bilibili/draw/ | 二维码绘制 |

---

*报告生成完毕*
