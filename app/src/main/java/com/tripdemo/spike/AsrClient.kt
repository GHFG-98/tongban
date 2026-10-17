package com.tripdemo.spike

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** ASR 识别出的一段文字（带音频内时间，毫秒） */
data class AsrSeg(val text: String, val startMs: Long, val endMs: Long)

/**
 * StepAudio 3 ASR（SSE）：一次性提交 PCM，服务端 SSE 推送增量+最终文本。
 * 文档：POST {baseUrl}/audio/asr/sse —— 必须用标准端点（/v1），
 * step_plan 网关不路由音频模型（404 model_invalid）。
 */
class AsrClient {

    /**
     * @param pcm PCM_S16LE 原始字节
     * @return 完整识别文本 + 分段（enable_timestamp=true 时）
     */
    fun transcribe(baseUrl: String, apiKey: String, pcm: ByteArray): AsrResult {
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("audio", JSONObject().apply {
                put("data", b64)
                put("input", JSONObject().apply {
                    put("transcription", JSONObject().apply {
                        put("language", "zh")
                        put("model", "stepaudio-3-asr-max")
                        put("enable_itn", true)
                        put("enable_timestamp", true)
                    })
                    put("format", JSONObject().apply {
                        put("type", "pcm")
                        put("codec", "pcm_s16le")
                        put("rate", SAMPLE_RATE)
                        put("bits", 16)
                        put("channel", 1)
                    })
                })
            })
        }
        val request = Request.Builder()
            .url("$baseUrl/audio/asr/sse")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val segs = ArrayList<AsrSeg>()
        val sb = StringBuilder()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 451) throw CensoredException("内容审核未通过")
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val source = resp.body?.source() ?: throw java.io.IOException("空响应")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                try {
                    val j = JSONObject(payload)
                    when (j.optString("type")) {
                        "transcript.text.delta" -> {
                            val d = j.optString("delta")
                            if (d.isNotEmpty()) {
                                sb.append(d)
                                segs.add(AsrSeg(d, j.optLong("start_time"), j.optLong("end_time")))
                            }
                        }
                        "transcript.text.done" -> {
                            val t = j.optString("text")
                            if (t.isNotEmpty()) sb.clear().append(t)
                        }
                        "error" -> throw java.io.IOException(j.optJSONObject("meta")?.optString("message") ?: "ASR error")
                    }
                } catch (e: org.json.JSONException) {
                    // 忽略无法解析的心跳/注释行
                }
            }
        }
        return AsrResult(sb.toString(), segs)
    }

    data class AsrResult(val text: String, val segs: List<AsrSeg>)

    companion object {
        const val SAMPLE_RATE = 16000

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)   // ASR 可能较慢
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
