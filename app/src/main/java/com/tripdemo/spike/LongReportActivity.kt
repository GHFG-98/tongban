package com.tripdemo.spike

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长尺度分析：把选中的多段小记对应的**多张拼图**一起送给 step-3.7-flash，
 * 生成一段更长尺度的行程回顾，并可导出精美 HTML 报告。
 */
class LongReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAMES = "names"
        const val EXTRA_SMART_PICK = "smartPick"
        private const val TAG = "LongReport"
    }

    private lateinit var statusText: TextView
    private lateinit var reportText: TextView
    private lateinit var btnExport: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private var reportTextValue = ""
    private var reportTitle = ""
    private var videoBytes: ByteArray? = null
    private val reportImages = ArrayList<Pair<String, ByteArray>>()   // 标签 -> JPEG bytes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VideoAssembler.cacheDirContext = applicationContext
        setContentView(R.layout.activity_long_report)

        statusText = findViewById(R.id.statusText)
        reportText = findViewById(R.id.reportText)
        btnExport = findViewById(R.id.btnExport)

        val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        val smartPick = intent.getBooleanExtra(EXTRA_SMART_PICK, false)
        if (names.isEmpty()) {
            statusText.text = "没有选中的小记"
            return
        }
        statusText.text =
            if (smartPick) "智能选集 ${names.size} 段，正在合成视频…"
            else "正在分析 ${names.size} 段小记…"
        btnExport.isEnabled = false
        btnExport.alpha = 0.5f

        scope.launch {
            val records = MediaStoreHelper.loadSummaries(this@LongReportActivity)
                .filter { it.name in names }
                .sortedBy { it.ts }

            // 读拼图原图（降采样到 720 宽，控制 token 与体积）
            val images = ArrayList<ByteArray>()
            val labels = ArrayList<String>()
            for (rec in records) {
                val sheetName = rec.sheetName ?: continue
                val uri = MediaStoreHelper.queryUriByName(
                    this@LongReportActivity, MediaStoreHelper.SHEETS_DIR, sheetName
                ) ?: continue
                val bytes = readScaled(uri) ?: continue
                images.add(bytes)
                labels.add(if (rec.ts > 0) fmt.format(Date(rec.ts)) else rec.name)
                reportImages.add(Pair(labels.last(), bytes))
            }

            if (images.isEmpty()) {
                runOnUiThread {
                    statusText.text = "选中的小记没有可用拼图"
                    btnExport.isEnabled = false
                }
                return@launch
            }

            val priorTexts = records.mapIndexed { i, r ->
                "【${labels.getOrNull(i) ?: ""}】${r.text}"
            }.joinToString("\n")

            val prompt = StepVlClient.LONG_PROMPT + "\n\n各段已有小记：\n" + priorTexts

            // 优先视频理解（省 token、时序好）；合成失败自动回退多图
            var usedVideo = false
            val text = try {
                val mp4 = VideoAssembler.build(images)
                if (mp4 != null) {
                    usedVideo = true
                    videoBytes = mp4
                    runOnUiThread { statusText.text = "视频已合成（${mp4.size / 1024}KB），正在理解…" }
                    StepVlClient().summarizeVideo(
                        baseUrl = StepVlConfig.baseUrl(this@LongReportActivity),
                        apiKey = StepVlConfig.apiKey(this@LongReportActivity),
                        model = StepVlConfig.model(this@LongReportActivity),
                        mp4 = mp4,
                        prompt = prompt,
                    )
                } else {
                    StepVlClient().summarizeMulti(
                        baseUrl = StepVlConfig.baseUrl(this@LongReportActivity),
                        apiKey = StepVlConfig.apiKey(this@LongReportActivity),
                        model = StepVlConfig.model(this@LongReportActivity),
                        jpegs = images,
                        labels = labels,
                        prompt = prompt,
                    )
                }
            } catch (e: CensoredException) {
                "内容审核未通过：选中的拼图含有敏感内容，可少选几段或更换录制内容后重试"
            } catch (e: Exception) {
                "长尺度分析失败：${e.message}"
            }
            reportTextValue = text
            reportTitle = "行程回顾 · ${labels.first()} - ${labels.last()}"
            runOnUiThread {
                statusText.text =
                    if (smartPick) "智能选集 Top${images.size} · 分析完成（${if (usedVideo) "视频理解" else "拼图理解"}）"
                    else "分析完成（${images.size} 段，${if (usedVideo) "视频理解" else "拼图理解"}）"
                reportText.text = text
                btnExport.isEnabled = true
                btnExport.alpha = 1.0f
            }
        }

        btnExport.setOnClickListener { exportReport() }
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun readScaled(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 720) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeStream(
                    contentResolver.openInputStream(uri), null, opts
                ) ?: return null
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 78, baos)
                bmp.recycle()
                baos.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 导出精美 HTML 报告（自包含：图片 base64 内嵌，可分享/浏览器打开/打印PDF） */
    private fun exportReport() {
        if (reportTextValue.isBlank()) return
        scope.launch {
            val html = ReportBuilder.build(
                title = reportTitle,
                narrative = reportTextValue,
                sections = reportImages.map { ReportBuilder.Section(it.first, "", it.second) },
                videoBytes = videoBytes,
            )
            val name = "report_${System.currentTimeMillis()}.html"
            val uri = MediaStoreHelper.saveText(
                this@LongReportActivity, "${MediaStoreHelper.ROOT}/reports", name, html,
                mime = "text/html"
            )
            runOnUiThread {
                if (uri == null) {
                    Toast.makeText(this@LongReportActivity, "导出失败", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    this@LongReportActivity,
                    "已导出：/sdcard/Download/trip_demo/reports/$name",
                    Toast.LENGTH_LONG
                ).show()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/html"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, reportTitle)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "分享行程报告"))
            }
        }
    }
}
