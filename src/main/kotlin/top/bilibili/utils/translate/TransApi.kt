package top.bilibili.utils.translate

import kotlinx.serialization.decodeFromString
import top.bilibili.BiliConfigManager
import top.bilibili.utils.json
import top.bilibili.utils.logger

/**
 * 百度翻译接口的轻量封装。
 */
class TransApi(private val appid: String, private val securityKey: String) {
    /**
     * 请求百度翻译接口并返回原始响应字符串。
     */
    fun getTransResult(query: String, from: String, to: String): String? {
        val params = buildParams(query, from, to)
        return HttpGet[TRANS_API_HOST, params]
    }

    /**
     * 按百度翻译接口要求构造签名参数。
     */
    private fun buildParams(query: String, from: String, to: String): Map<String?, String?> {
        val params: MutableMap<String?, String?> = HashMap()
        params["q"] = query
        params["from"] = from
        params["to"] = to
        params["appid"] = appid

        // 随机数
        val salt = System.currentTimeMillis().toString()
        params["salt"] = salt

        // 签名
        val src = appid + query + salt + securityKey // 加密前的原文
        params["sign"] = MD5.md5(src)
        return params
    }

    companion object {
        private const val TRANS_API_HOST = "http://api.fanyi.baidu.com/api/trans/vip/translate"
    }
}

var jp =
    "[ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕゖ゚゛゜ゝゞゟ゠ァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴヵヶヷヸヹヺ・ーヽヾヿ㍿]".toRegex()

private val api = TransApi(
    BiliConfigManager.config.translateConfig.baidu.APP_ID,
    BiliConfigManager.config.translateConfig.baidu.SECURITY_KEY
)

//文本翻译
/**
 * 将非中文文本翻译为中文；已是中文或不满足翻译条件时返回 `null`。
 */
fun trans(text: String): String? {
    if (BiliConfigManager.config.enableConfig.translateEnable) {
        if (BiliConfigManager.config.translateConfig.baidu.SECURITY_KEY != "") {
            var msg = text
            while (msg.indexOf('[') != -1) {
                // 先剔除方括号片段，是为了避免消息标记内容干扰语言检测与翻译结果。
                msg = msg.replaceRange(msg.indexOf('['), msg.indexOf(']') + 1, "  ")
            }
            if (msg.contains(jp) || !msg.contains("[\u4e00-\u9fa5]".toRegex())) {
                try {
                    val resMsg = api.getTransResult(msg, "auto", "zh")
                    if (resMsg == null) {
                        logger.error("翻译数据获取失败")
                        return null
                    }
                    val transResult = resMsg.let { json.decodeFromString<TransResult>(it) }
                    if (transResult.errorCode != null) {
                        logger.error("翻译错误 code: ${transResult.errorCode}  msg: ${transResult.errorMsg}")
                        return null
                    }
                    if (transResult.from != "zh") {
                        return buildString {
                            for (item in transResult.transResult!!) {
                                appendLine(item.dst)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Baidu translation failure! 百度翻译失败! $e")
                }
            } else return null
        } else logger.error("Baidu translation API not configured! 未配置百度翻译API")
    }
    return null
}
