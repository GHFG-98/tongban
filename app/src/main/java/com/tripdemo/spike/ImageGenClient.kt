package com.tripdemo.spike

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 卡通集合图生成（gpt-image-2，OpenAI 兼容 images/generations）。
 * 返回图片字节（PNG/JPEG）；失败抛异常，由调用方决定是否降级。
 */
class ImageGenClient {

    /**
     * @param baseUrl 网关根地址（如 https://i-ai-gw.stepx.com），自动拼 /v1/images/generations
     * @param size    图片尺寸，如 1024x1024
     */
    fun generate(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        size: String = "1024x1024",
    ): ByteArray {
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
        }.toString()
        // 兼容两种配置写法：baseUrl 带不带 /v1 都能正确拼出 /images/generations
        val base = baseUrl.trimEnd('/')
        val url = if (base.endsWith("/v1", ignoreCase = true)) {
            "$base/images/generations"
        } else {
            "$base/v1/images/generations"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}: ${text.take(200)}")
            }
            val item = JSONObject(text).optJSONArray("data")?.optJSONObject(0)
                ?: throw java.io.IOException("响应无 data")
            val b64 = item.optString("b64_json").replace("\n", "").replace("\r", "")
            if (b64.isNotBlank()) return Base64.decode(b64, Base64.DEFAULT)
            val url = item.optString("url")
            if (url.isNotBlank()) return download(url)
            throw java.io.IOException("响应无图片（既无 b64_json 也无 url）")
        }
    }

    private fun download(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("下载图片失败 HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw java.io.IOException("空图片")
        }
    }

    companion object {
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)   // 生图普遍较慢
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
