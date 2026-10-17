package com.tripdemo.spike

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/**
 * MediaStore 帮助类：JPEG / 文本落盘到 /sdcard/Download/trip_demo/ 下，
 * 以及按文件名反查 URI（相册/MTP/文件管理器均可见，免权限）。
 */
object MediaStoreHelper {

    const val ROOT = "Download/trip_demo"
    const val SHEETS_DIR = "$ROOT/sheets"
    const val SUMMARIES_DIR = "$ROOT/summaries"

    /** 存任意二进制文件（导出素材的 WAV 等用）；同名覆盖 */
    fun saveFile(
        context: Context,
        relPath: String,
        name: String,
        bytes: ByteArray,
        mime: String,
    ): Uri? {
        val existing = queryUriByName(context, relPath, name)
        val uri = existing ?: run {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            }
            context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } ?: return null
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        return uri
    }

    /** 列出某目录下全部文件（显示名 + URI），用于导出素材 */
    fun listFiles(context: Context, relPath: String): List<Pair<String, Uri>> {
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=?"
        val args = arrayOf("$relPath/")
        val out = ArrayList<Pair<String, Uri>>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                out.add(name to Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()))
            }
        }
        return out
    }

    fun saveJpeg(context: Context, relPath: String, name: String, jpeg: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
        }
        val uri = context.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
        return uri
    }

    fun saveText(
        context: Context,
        relPath: String,
        name: String,
        text: String,
        mime: String = "application/json",
    ): Uri? {
        // 同名覆盖：先查已有的，直接写它的 URI（否则 MediaStore 会自动改名成 meta (1).json）
        val existing = queryUriByName(context, relPath, name)
        val uri = existing ?: run {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            }
            context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } ?: return null
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        return uri
    }

    /** 删除 Download 下的文件（按显示名）。不存在视为已删；返回是否删干净 */
    fun deleteByName(context: Context, relPath: String, name: String): Boolean {
        val uri = queryUriByName(context, relPath, name) ?: return true
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            android.util.Log.w("MediaStoreHelper", "delete denied: $relPath/$name", e)
            false
        }
    }

    /** 按显示名反查 Download 下的 URI（用于列表里显示拼图缩略图） */
    fun queryUriByName(context: Context, relPath: String, name: String): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val args = arrayOf(name, "$relPath/")
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }

    data class SummaryFile(
        val name: String,
        val ts: Long,
        val text: String,
        val sheetName: String?,
        val ok: Boolean,
        val loc: String? = null,
    )

    /** 读取全部总结记录（新的在前） */
    fun loadSummaries(context: Context): List<SummaryFile> {
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_ADDED)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=?"
        val args = arrayOf("$SUMMARIES_DIR/")
        val out = ArrayList<SummaryFile>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args,
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: continue
                out.add(parse(name, text))
            }
        }
        return out
    }

    private fun parse(name: String, raw: String): SummaryFile {
        return try {
            val j = JSONObject(raw)
            SummaryFile(
                name = name,
                ts = j.optLong("ts"),
                text = j.optString("text"),
                sheetName = j.optString("sheet").ifBlank { null },
                ok = j.optBoolean("ok"),
                loc = j.optString("loc").ifBlank { null },
            )
        } catch (e: Exception) {
            SummaryFile(name, 0L, "（记录解析失败）${raw.take(200)}", null, false)
        }
    }
}
