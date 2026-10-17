package com.tripdemo.spike

import android.content.Context
import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** StepFun 服务端内容审核拦截（HTTP 451）：重试无意义，应友好跳过 */
class CensoredException(message: String) : Exception(message)

object StepVlConfig {
    private const val PREFS = "stepvl"
    private const val KEY_BASE = "baseUrl"
    private const val KEY_TOKEN = "apiKey"
    private const val KEY_MODEL = "model"
    private const val KEY_GREET_BASE = "greetBaseUrl"
    private const val KEY_GREET_TOKEN = "greetApiKey"
    private const val KEY_GREET_MODEL = "greetModel"
    private const val KEY_GREET_PROMPT = "greetPrompt"
    private const val KEY_SUMMARY_PROMPT = "summaryPrompt"
    private const val KEY_IMG_BASE = "imgBaseUrl"
    private const val KEY_IMG_TOKEN = "imgApiKey"
    private const val KEY_IMG_MODEL = "imgModel"

    // 三个模型的默认值只作为「提示文案」，key 一律由用户在设置里自己填（本仓库不含任何密钥）
    const val DEFAULT_BASE_URL = "https://api.stepfun.com/step_plan/v1"
    const val DEFAULT_MODEL = "step-3.7-flash"

    // 实时问候走标准端点（Step-1o Turbo Vision 只在 /v1 提供，stepplan 端点没有）
    const val DEFAULT_GREET_BASE_URL = "https://api.stepfun.com/v1"
    const val DEFAULT_GREET_MODEL = "step-1o-turbo-vision"

    /**
     * ASR 端点：实测 step_plan 网关不路由音频模型（404 model_invalid），
     * StepAudio ASR 只在标准 /v1 提供。固定走 StepFun，复用「行程总结」的 key。
     */
    const val ASR_BASE_URL = "https://api.stepfun.com/v1"
    const val ASR_MODEL = "stepaudio-3-asr-max"

    /** 卡通集合图生图（OpenAI 兼容 images/generations）默认值 */
    const val DEFAULT_IMG_BASE_URL = "https://mp.z01.normandyfunc.com/v1"
    const val DEFAULT_IMG_MODEL = "gpt-image-2"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 模型一：行程总结 / 问答 / 导出报告 ----------

    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    fun apiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_TOKEN, "").orEmpty()

    fun model(ctx: Context): String =
        prefs(ctx).getString(KEY_MODEL, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    // ---------- 模型二：实时问候（同端点也可用于语音识别 ASR） ----------

    fun greetBaseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_GREET_BASE, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_GREET_BASE_URL

    fun greetApiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_GREET_TOKEN, "").orEmpty()

    fun greetModel(ctx: Context): String =
        prefs(ctx).getString(KEY_GREET_MODEL, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_GREET_MODEL

    // ---------- 模型三：卡通集合图生图 ----------

    fun imgBaseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_IMG_BASE, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_IMG_BASE_URL

    fun imgApiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_IMG_TOKEN, "").orEmpty()

    fun imgModel(ctx: Context): String =
        prefs(ctx).getString(KEY_IMG_MODEL, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_IMG_MODEL

    /** 实时问候 prompt（设置页可改，留空用默认） */
    fun greetPrompt(ctx: Context): String =
        prefs(ctx).getString(KEY_GREET_PROMPT, "")?.takeIf { it.isNotBlank() }
            ?: StepVlClient.DEFAULT_GREET_PROMPT

    /** 行程小计 prompt（设置页可改，留空用默认） */
    fun summaryPrompt(ctx: Context): String =
        prefs(ctx).getString(KEY_SUMMARY_PROMPT, "")?.takeIf { it.isNotBlank() }
            ?: StepVlClient.DEFAULT_SUMMARY_PROMPT

    fun saveGreetPrompt(ctx: Context, s: String) {
        prefs(ctx).edit().putString(KEY_GREET_PROMPT, s).apply()
    }

    fun saveSummaryPrompt(ctx: Context, s: String) {
        prefs(ctx).edit().putString(KEY_SUMMARY_PROMPT, s).apply()
    }

    fun save(ctx: Context, baseUrl: String, apiKey: String, model: String) {
        prefs(ctx).edit()
            .putString(KEY_BASE, baseUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, apiKey.trim())
            .putString(KEY_MODEL, model.trim())
            .apply()
    }

    fun saveGreet(ctx: Context, baseUrl: String, apiKey: String, model: String) {
        prefs(ctx).edit()
            .putString(KEY_GREET_BASE, baseUrl.trim().trimEnd('/'))
            .putString(KEY_GREET_TOKEN, apiKey.trim())
            .putString(KEY_GREET_MODEL, model.trim())
            .apply()
    }

    fun saveImg(ctx: Context, baseUrl: String, apiKey: String, model: String) {
        prefs(ctx).edit()
            .putString(KEY_IMG_BASE, baseUrl.trim().trimEnd('/'))
            .putString(KEY_IMG_TOKEN, apiKey.trim())
            .putString(KEY_IMG_MODEL, model.trim())
            .apply()
    }

    /** 行程总结/问答/导出是否可用（key 由用户填） */
    fun configured(ctx: Context): Boolean = apiKey(ctx).isNotBlank()

    /** 实时问候是否可用 */
    fun greetConfigured(ctx: Context): Boolean = greetApiKey(ctx).isNotBlank()

    /** 卡通集合图是否可用 */
    fun imgConfigured(ctx: Context): Boolean = imgApiKey(ctx).isNotBlank()
}

class StepVlClient {

    /** 对当前画面生成一句主动问候（≤15字），返回纯文本。失败抛异常。 */
    fun greet(baseUrl: String, apiKey: String, model: String, jpeg: ByteArray, prompt: String): String {
        val raw = summarize(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            jpeg = jpeg,
            prompt = prompt,
        )
        return cap15(raw)
    }

    /** 兜底截断到15字：优先在15字内的最后一个标点处断 */
    private fun cap15(text: String): String {
        val t = text.trim().trim('"', '"', '「', '」', '\'', ' ')
        if (t.length <= 15) return t
        val head = t.substring(0, 15)
        val lastPunct = head.indexOfLast { it in "。！？；，、,.;!? " }
        return if (lastPunct >= 5) head.substring(0, lastPunct) else head
    }

    /** 对一张拼图 JPEG 生成中文总结，返回纯文本。失败抛异常。 */
    fun summarize(baseUrl: String, apiKey: String, model: String, jpeg: ByteArray, prompt: String): String {
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$b64")
                })
            })
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                })
            })
            put("temperature", 0.2)
            // step-3.7-flash 是推理模型：low 档思维链最短；4096 给思维链留足额度
            // （踩坑：2048 会被长思维链吃光导致 content 为空）
            put("reasoning_effort", "low")
            put("max_tokens", 4096)
        }
        return post(baseUrl, apiKey, body)
    }

    /** 多张拼图一起分析（长尺度回顾），返回纯文本。失败抛异常。 */
    fun summarizeMulti(
        baseUrl: String,
        apiKey: String,
        model: String,
        jpegs: List<ByteArray>,
        labels: List<String>,
        prompt: String,
    ): String {
        val content = JSONArray()
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })
        jpegs.forEachIndexed { i, jpeg ->
            val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
            val label = labels.getOrNull(i)?.let { "【$it】" } ?: ""
            content.put(JSONObject().apply {
                put("type", "text")
                put("text", "$label 第${i + 1}张拼图：")
            })
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$b64")
                })
            })
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                })
            })
            put("temperature", 0.3)
            put("reasoning_effort", "low")
            put("max_tokens", 4096)
        }
        return post(baseUrl, apiKey, body)
    }

    /** 视频理解：MP4（base64 内联，video_url 字段）生成中文回顾。失败抛异常。 */
    fun summarizeVideo(
        baseUrl: String,
        apiKey: String,
        model: String,
        mp4: ByteArray,
        prompt: String,
    ): String {
        val b64 = android.util.Base64.encodeToString(mp4, android.util.Base64.NO_WRAP)
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "video_url")
                put("video_url", JSONObject().apply {
                    put("url", "data:video/mp4;base64,$b64")
                })
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                })
            })
            put("temperature", 0.3)
            put("reasoning_effort", "low")
            put("max_tokens", 4096)
        }
        return post(baseUrl, apiKey, body)
    }
    /**
     * 行程问答：问题 + 各段小记 + 位置 + 语音 + 参考拼图，返回回答。失败抛异常。
     * @param voiceText 途中的语音（带相对时间），有则一并送模型
     * @param gpsText 各段位置信息（起点→终点），有则一并送模型
     */
    fun ask(
        baseUrl: String,
        apiKey: String,
        model: String,
        question: String,
        summaryTexts: List<String>,
        sheets: List<ByteArray>,
        voiceText: String = "",
        gpsText: String = "",
    ): String {
        val content = JSONArray()
        val sb = StringBuilder()
        sb.append(ASK_PROMPT).append("\n\n【各段小记】\n")
        summaryTexts.forEachIndexed { i, t -> sb.append("第${i + 1}段：$t\n") }
        if (gpsText.isNotBlank()) {
            sb.append("\n【位置信息（各段起点→终点）】\n").append(gpsText).append("\n")
        }
        if (voiceText.isNotBlank()) {
            sb.append("\n【途中的语音（用户随行说的话，带相对时间）】\n").append(voiceText).append("\n")
        }
        sb.append("\n【用户问题】$question")
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", sb.toString())
        })
        sheets.forEach { jpeg ->
            val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$b64") })
            })
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                })
            })
            put("temperature", 0.3)
            put("reasoning_effort", "low")
            put("max_tokens", 4096)
        }
        return post(baseUrl, apiKey, body)
    }

    /** 统一发送；HTTP 451 → CensoredException；其他错误脱敏（不泄漏原始响应） */
    private fun post(baseUrl: String, apiKey: String, body: JSONObject): String {
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 451) throw CensoredException("内容审核未通过")
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val choices = JSONObject(text).optJSONArray("choices")
                ?: throw java.io.IOException("响应无 choices")
            val out = choices.getJSONObject(0).optJSONObject("message")
                ?.optString("content", "").orEmpty()
            if (out.isBlank()) throw java.io.IOException("模型未返回正文（思考过程可能占满了额度），请重试")
            return out
        }
    }

    companion object {
        /** 预览实时问候模型（Step-1o Turbo Vision：图+文输入、纯文本输出、速度快、32K 上下文） */
        const val GREET_MODEL = "step-1o-turbo-vision"

        const val DEFAULT_GREET_PROMPT =
            "你是相机取景画面里一位一直在场的朋友，随口跟用户聊一句。\n" +
                "要求：\n" +
                "1. 不超过15个字；\n" +
                "2. 说你注意到的画面细节、当下的感受或联想（如「这线绕得跟迷宫似的」「绿鼠标垫看着挺安静」）；\n" +
                "3. 只陈述和分享，不说教、不提醒、不建议——不要出现「记得」「应该」「注意」「别」「当心」这类字眼；\n" +
                "4. 画面里如果有人，只说动作和场景，不描述外貌、衣着、身体；\n" +
                "5. 不用问句，不审问；\n" +
                "6. 只输出这句话本身：不要解释、不要引号、不要前缀、不要多个标点。"

        /** 行程问答 */
        const val ASK_PROMPT =
            "下面是同一次行程的各段小记（按时间顺序），可能附有对应拼图。用户针对这段经历提问。\n" +
                "请依据这些小记和画面回答：\n" +
                "1. 只根据给定内容回答，不确定或没提到的就说「小记里没提到」，不要编造；\n" +
                "2. 回答简短自然，一两句话，像朋友聊天；\n" +
                "3. 问题涉及时间或先后时，可按小记的时间顺序推断；\n" +
                "4. 直接给答案，不要复述小记。"

        /** 长尺度回顾（多张拼图一起分析） */
        const val LONG_PROMPT =
            "下面是同一次行程中按时间顺序选出的多段拼图，每段左上角标注了该段相对行程开始的时间（mm:ss），" +
                "每张图前面附带了它的时间标签。另附各段已生成的短小记供参考。\n" +
                "请写一份更长尺度的行程回顾：\n" +
                "1. 以发生的事情为主线，把这几段连成一段完整经历：从开始到结束，场景如何演变、人做了什么；\n" +
                "2. 挑出这段时间里值得记住的瞬间，具体一点；\n" +
                "3. 参考各段小记但不要简单拼接，不要逐段复述；\n" +
                "4. 画面里如果有人，只说动作和场景，不描述外貌、衣着、身体；\n" +
                "5. 语气像随手记下的碎碎念，温和、生活化，不要公文腔也不要太文艺；\n" +
                "6. 结尾加一句轻轻的慰问或提醒，一句就够；\n" +
                "7. 字数150-300字，直接给正文，不要解释拼图本身。"
        /** 导出报告：把选中的多段素材（拼图+小记）再送一次模型，生成报告叙述 */
        const val REPORT_PROMPT =
            "下面是同一次行程中按时间顺序选出的若干段，每段附一张当时画面的拼图（左上角标注了该段的时间），" +
                "以及各段已生成的短小记。请基于这些素材写一份完整的行程报告：\n" +
                "1. 严格按各段的时间顺序（从早到晚）组织，不要混淆不同时段（如上午/下午/深夜），跨时段时用明确的时间过渡；\n" +
                "2. 用连贯段落把这几段串成一段完整经历，突出场景变化和值得注意的细节；\n" +
                "3. 相邻各段内容可能重复（每段小记都带前文），只保留一次，挑最新的细节，不要复述同样的事；\n" +
                "4. 可以合理发挥细节与感受，但不要编造不存在的事件；\n" +
                "5. 语气温和、生活化，像一篇短游记，不要公文腔；\n" +
                "6. 结尾加一句轻轻的问候或总结；\n" +
                "7. 字数200-400字，直接给正文，不要解释拼图本身。"
        const val DEFAULT_SUMMARY_PROMPT =
            "下面是同一次行程「从开始到现在」的记录：可能包含之前各段已经写过的小记，以及最新一段抓拍画面拼成的拼图。\n" +
                "请据此写一份从开始到现在的整体回顾：\n" +
                "1. 大概讲讲从开始到现在发生了什么、场景怎么变的、有什么值得注意的事，按发生的事情自然串起来；\n" +
                "2. 之前的小记是已经发生的部分，最新拼图是刚发生的一部分，两者要连成一段完整经历，不要逐段复述；\n" +
                "3. 在事实基础上可以适度发挥，加一点画面里的细节和感受，但不要编造不存在的事件；\n" +
                "4. 画面里如果有人，只说动作和场景，不描述外貌、衣着、身体；\n" +
                "5. 语气像随手记下的碎碎念，温和、生活化，不要公文腔，也不要太文艺；\n" +
                "6. 不用纠结精确时间，大致带过就行；\n" +
                "7. 结尾加一句轻轻的慰问或提醒，一句就够；\n" +
                "8. 总共4-6句话，直接给正文，不要解释拼图本身。"

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
