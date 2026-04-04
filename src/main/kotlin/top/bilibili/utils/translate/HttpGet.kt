package top.bilibili.utils.translate

import org.slf4j.LoggerFactory
import java.io.*
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 百度提供
 */
internal object HttpGet {
    internal const val SOCKET_TIMEOUT = 10000 // 10S
    internal const val GET = "GET"
    private val logger = LoggerFactory.getLogger(HttpGet::class.java)

    /**
     * 向指定地址发起 GET 请求，并在可恢复错误时执行有限重试。
     */
    operator fun get(host: String, params: Map<String?, String?>?): String? {
        val maxRetries = 1
        var retryCount = 0
        
        while (retryCount <= maxRetries) {
            var conn: HttpURLConnection? = null
            var `is`: InputStream? = null
            var br: BufferedReader? = null
            try {
                // 设置SSLContext
                val sslcontext = SSLContext.getInstance("TLS")
                // 这里沿用宽松证书策略，是为了兼容历史翻译接口环境中的证书问题。
                sslcontext.init(null, arrayOf(myX509TrustManager), null)
                val sendUrl = getUrlWithQueryString(host, params)

                // System.out.println("URL:" + sendUrl);
                val uri = URL(sendUrl) // 创建URL对象
                conn = uri.openConnection() as HttpURLConnection
                if (conn is HttpsURLConnection) {
                    conn.sslSocketFactory = sslcontext.socketFactory
                }
                conn.connectTimeout = SOCKET_TIMEOUT // 设置相应超时
                conn.requestMethod = GET
                val statusCode = conn.responseCode
                if (statusCode != HttpURLConnection.HTTP_OK) {
                    logger.warn("Http错误码：$statusCode")
                }

                // 读取服务器的数据
                `is` = conn.inputStream
                br = BufferedReader(InputStreamReader(`is`, StandardCharsets.UTF_8))
                val builder = StringBuilder()
                while (true) {
                    val line = br.readLine() ?: break
                    builder.append(line)
                }
                val text = builder.toString()
                return text
            } catch (e: MalformedURLException) {
                logger.warn("Http 请求地址无效: ${e.message}", e)
                // 地址无效不需要重试
                break
            } catch (e: IOException) {
                logger.warn("Http 请求失败 (尝试 ${retryCount + 1}/${maxRetries + 1}): ${e.message}")
            } catch (e: KeyManagementException) {
                logger.warn("Http SSL 初始化失败: ${e.message}", e)
                // SSL 初始化失败重试可能无用，但也可以试一次
            } catch (e: NoSuchAlgorithmException) {
                logger.warn("Http 加密算法不可用: ${e.message}", e)
                // 算法不可用不需要重试
                break
            } finally {
                // 异常路径也必须统一释放网络连接与数据流，避免 7x24 场景下句柄持续累积。
                close(br)
                close(`is`)
                conn?.disconnect()
            }
            
            if (retryCount < maxRetries) {
                try {
                    Thread.sleep(3000)
                } catch (e: InterruptedException) {
                    break
                }
                retryCount++
            } else {
                break
            }
        }
        logger.error("Http 请求彻底失败: $host")
        return null
    }

    /**
     * 将查询参数拼接到 URL 上，并对参数值进行编码。
     */
    fun getUrlWithQueryString(url: String, params: Map<String?, String?>?): String {
        if (params == null) {
            return url
        }
        val builder = StringBuilder(url)
        if (url.contains("?")) {
            builder.append("&")
        } else {
            builder.append("?")
        }
        var i = 0
        for (key in params.keys) {
            val value = params[key]
                ?: // 过滤空的key
                continue
            if (i != 0) {
                builder.append('&')
            }
            builder.append(key)
            builder.append('=')
            builder.append(encode(value))
            i++
        }
        return builder.toString()
    }

    /**
     * 安全关闭可关闭资源，避免清理阶段的异常影响主流程。
     */
    internal fun close(closeable: Closeable?) {
        if (closeable != null) {
            try {
                closeable.close()
            } catch (e: IOException) {
                logger.warn("Http 关闭资源失败: ${e.message}", e)
            }
        }
    }

    /**
     * 对输入的字符串进行URL编码, 即转换为%20这种形式
     *
     * @param input 原文
     * @return URL编码. 如果编码失败, 则返回原文
     */
    fun encode(input: String?): String {
        if (input == null) {
            return ""
        }
        try {
            return URLEncoder.encode(input, "utf-8")
        } catch (e: UnsupportedEncodingException) {
            logger.warn("Http URL 编码失败: ${e.message}", e)
        }
        return input
    }

    private val myX509TrustManager: TrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate>? {
            return null
        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        @Throws(CertificateException::class)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }
    }
}
