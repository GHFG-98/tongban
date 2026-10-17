package com.tripdemo.spike

import android.content.Context
import android.content.SharedPreferences

/**
 * 采集设置：取帧间隔 + 每几帧总结一次。
 * 独立 SharedPreferences，设置页可改，重启 trip 生效。
 */
object CaptureSettings {
    private const val PREFS = "capture"
    private const val KEY_INTERVAL = "intervalMs"
    private const val KEY_FRAMES_PER_SUMMARY = "framesPerSummary"
    private const val KEY_AUDIO_ENABLED = "audioEnabled"
    private const val KEY_IMAGE_SEND_MODE = "imageSendMode"

    /** 取帧间隔可选项（毫秒） */
    val INTERVAL_OPTIONS = longArrayOf(
        1_000, 3_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000
    )

    /** 每几帧总结一次可选项 */
    val FRAMES_PER_SUMMARY_OPTIONS = intArrayOf(3, 6, 9, 12, 15, 20, 30)

    const val DEFAULT_INTERVAL_MS = 3_000L
    const val DEFAULT_FRAMES_PER_SUMMARY = 12

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun intervalMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_INTERVAL, DEFAULT_INTERVAL_MS)

    fun framesPerSummary(ctx: Context): Int =
        prefs(ctx).getInt(KEY_FRAMES_PER_SUMMARY, DEFAULT_FRAMES_PER_SUMMARY)

    // ---- 收音开关（默认关） ----
    fun audioEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUDIO_ENABLED, false)

    fun setAudioEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUDIO_ENABLED, on).apply()
    }

    // ---- 图片发送模式：sheet=拼图（默认）；all=全部原图；video=小视频 ----
    const val IMAGE_MODE_SHEET = "sheet"
    const val IMAGE_MODE_ALL = "all"
    const val IMAGE_MODE_VIDEO = "video"

    fun imageSendMode(ctx: Context): String =
        prefs(ctx).getString(KEY_IMAGE_SEND_MODE, IMAGE_MODE_SHEET) ?: IMAGE_MODE_SHEET

    fun setImageSendMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_IMAGE_SEND_MODE, mode).apply()
    }

    fun save(ctx: Context, intervalMs: Long, framesPerSummary: Int) {
        prefs(ctx).edit()
            .putLong(KEY_INTERVAL, intervalMs)
            .putInt(KEY_FRAMES_PER_SUMMARY, framesPerSummary)
            .apply()
    }

    fun intervalLabel(ms: Long): String =
        if (ms < 60_000) "${ms / 1000}秒" else "${ms / 60_000}分钟"

    fun indexOfInterval(ms: Long): Int {
        val i = INTERVAL_OPTIONS.indexOfFirst { it == ms }
        return if (i >= 0) i else INTERVAL_OPTIONS.indexOf(DEFAULT_INTERVAL_MS)
    }

    fun indexOfFrames(n: Int): Int {
        val i = FRAMES_PER_SUMMARY_OPTIONS.indexOf(n)
        return if (i >= 0) i else FRAMES_PER_SUMMARY_OPTIONS.indexOf(DEFAULT_FRAMES_PER_SUMMARY)
    }
}
