package com.tripdemo.spike

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
/**
 * 行程小计页（v2 交互）：
 *  - 顶部 Ask 常驻区：输入 + 发送，问答历史保留在页面内（不再是一次性弹窗）
 *  - 小记卡片流：左时间轴 + 上图下文；**最新总结在最上面**；点卡片看大图；
 *    **长按照片进入选择模式并选中该段**；卡片右上 ⋮ 出菜单（看原图/复制/编辑/多选/删除）
 *  - 底部操作栏：常态「生成小计/导出素材/导出所有小计」；选择模式切换为
 *    「取消/导出(N)/删除(N)/分析(N)」（长按照片进入选择模式）
 */
class SummaryActivity : AppCompatActivity() {

    private lateinit var engine: SummaryEngine
    private lateinit var listContainer: LinearLayout
    private lateinit var askHistory: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private var selectMode = false
    private val selected = LinkedHashSet<String>()
    /** 当前列表缓存（倒序），删除时按名字取记录，避免重复查库 */
    private var currentItems: List<MediaStoreHelper.SummaryFile> = emptyList()

    /** 问答历史（最新在下） */
    private data class Qa(val q: String, val a: String, val ok: Boolean)
    private val qaList = ArrayList<Qa>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)

        listContainer = findViewById(R.id.listContainer)
        askHistory = findViewById(R.id.askHistory)
        scrollView = findViewById(R.id.scrollView)
        titleText = findViewById(R.id.titleText)
        statusText = findViewById(R.id.statusText)

        engine = SummaryEngine.get(applicationContext)
        engine.onStatus = { msg -> runOnUiThread { statusText.text = msg } }
        engine.onSummary = { refresh() }
        // 一段语音识别完成 → 只刷语音卡（按批次 incremental，语音会一段段长出来）
        CaptureService.onAsrSegment = { runOnUiThread { refreshAsrCard() } }

        val etQuestion = findViewById<EditText>(R.id.etQuestion)
        etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                ask(); true
            } else false
        }
        findViewById<TextView>(R.id.btnAsk).setOnClickListener { ask() }

        findViewById<TextView>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 底部操作栏——常态
        findViewById<TextView>(R.id.btnGenerate).setOnClickListener { generateNow() }
        findViewById<TextView>(R.id.btnSmartPick).setOnClickListener { exportMaterials() }
        findViewById<TextView>(R.id.btnExportAll).setOnClickListener { exportSelected() }

        // 底部操作栏——选择模式
        findViewById<TextView>(R.id.btnSelectCancel).setOnClickListener { exitSelectMode() }
        findViewById<TextView>(R.id.btnExportSelected).setOnClickListener { exportSelected() }
        findViewById<TextView>(R.id.btnDeleteSelected).setOnClickListener { deleteSelected() }
        findViewById<TextView>(R.id.btnLongAnalysis).setOnClickListener { startLongAnalysis() }

        refresh()
    }

    // ---------- 底部栏 / 选择模式 ----------

    private fun updateChrome() {
        val normal = !selectMode
        findViewById<TextView>(R.id.btnGenerate).visibility = if (normal) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnSmartPick).visibility = if (normal) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnExportAll).visibility = if (normal) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnSelectCancel).visibility = if (selectMode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnExportSelected).visibility = if (selectMode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnDeleteSelected).visibility = if (selectMode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnLongAnalysis).visibility = if (selectMode) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.btnExportSelected).text = "导出(${selected.size})"
        findViewById<TextView>(R.id.btnDeleteSelected).text = "删除(${selected.size})"
        findViewById<TextView>(R.id.btnLongAnalysis).text = "分析(${selected.size})"
        findViewById<TextView>(R.id.btnLongAnalysis).isEnabled = selected.isNotEmpty()
        findViewById<TextView>(R.id.btnLongAnalysis).alpha = if (selected.isEmpty()) 0.5f else 1.0f
        findViewById<TextView>(R.id.btnDeleteSelected).isEnabled = selected.isNotEmpty()
        findViewById<TextView>(R.id.btnDeleteSelected).alpha = if (selected.isEmpty()) 0.5f else 1.0f

        titleText.text = if (selectMode) "已选 ${selected.size} 段" else "行程小计"
    }

    private fun enterSelectMode() {
        selectMode = true
        updateChrome()
        refresh()
    }

    private fun exitSelectMode() {
        selectMode = false
        selected.clear()
        updateChrome()
        refresh()
    }

    // ---------- 列表 ----------

    private fun refresh() {
        runOnUiThread {
            // 最新的总结排在最上面（时间倒序）
            val items = MediaStoreHelper.loadSummaries(this).sortedByDescending { it.ts }
            currentItems = items
            listContainer.removeAllViews()
            if (items.isEmpty()) {
                listContainer.addView(emptyView())
            } else {
                items.forEach { listContainer.addView(buildItem(it)) }
            }
            updateChrome()
            refreshAsrCard()
        }
    }

    /** 回到页面时刷新（ASR 是停止录制后异步生成的，回来才能看到） */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 选择模式：删除勾选的若干段 */
    private fun deleteSelected() {
        if (selected.isEmpty()) return
        val recs = currentItems.filter { it.name in selected }
        confirmDelete(recs)
    }

    // ---------- 行程语音（收音识别结果） ----------

    /**
     * 展示收音/ASR 结果：找最新一条带 asr.json 的 trip，列出「mm:ss 『文字』」。
     * 让用户在本页就能确认收音到底成没成功。
     */
    private fun refreshAsrCard() {
        val title = findViewById<TextView>(R.id.asrTitle)
        val status = findViewById<TextView>(R.id.asrStatus)
        val list = findViewById<LinearLayout>(R.id.asrList)
        list.removeAllViews()

        val root = File(getExternalFilesDir(null), "trip_demo")
        val trips = root.listFiles { f -> f.isDirectory && f.name.startsWith("trip_") }
            ?.sortedBy { it.name }   // trip_spike_<ts>，字典序即时间序

        when {
            !CaptureSettings.audioEnabled(this) -> {
                title.text = "行程语音"
                status.setTextColor(0xFF8E8E93.toInt())
                status.text = "未开启行程录音（设置里可打开）"
            }
            trips.isNullOrEmpty() -> {
                title.text = "行程语音"
                status.setTextColor(0xFF8E8E93.toInt())
                status.text = "还没有行程记录"
            }
            else -> {
                val withAsr = trips.lastOrNull { File(it, "asr.json").exists() }
                if (withAsr != null) {
                    title.text = "行程语音 · ${tripTimeLabel(withAsr.name)}"
                    val asrFile = File(withAsr, "asr.json")
                    val err = readAsrError(asrFile)
                    if (err != null) {
                        // 识别失败：显示真实原因（红色），别和"还在识别"混淆
                        status.setTextColor(0xFFB3261E.toInt())
                        status.text = err
                    } else {
                        status.setTextColor(0xFF8E8E93.toInt())
                        val segs = readAsrSegs(asrFile)
                        if (segs.isEmpty()) {
                            val failed = readAsrFailed(asrFile)
                            status.text = if (failed > 0) "语音识别失败（$failed 段），稍后回来看看"
                            else "识别结果为空（可能全程无人说话）"
                        } else {
                            val failed = readAsrFailed(asrFile)
                            status.text = "已识别 ${segs.size} 段语音" +
                                (failed.takeIf { it > 0 }?.let { "（$it 段识别失败）" } ?: "")
                            segs.take(30).forEach { (t, txt) ->
                                list.addView(TextView(this).apply {
                                    text = String.format("%02d:%02d", t / 60, t % 60) + "  「$txt」"
                                    textSize = 13f
                                    setTextColor(0xFF1C1C1E.toInt())
                                    setPadding(0, 6, 0, 0)
                                    setTextIsSelectable(true)
                                })
                            }
                            if (segs.size > 30) {
                                list.addView(TextView(this).apply {
                                    text = "……共 ${segs.size} 段"
                                    textSize = 12f
                                    setTextColor(0xFF8E8E93.toInt())
                                    setPadding(0, 6, 0, 0)
                                })
                            }
                        }
                    }
                } else {
                    title.text = "行程语音 · ${tripTimeLabel(trips.last().name)}"
                    status.setTextColor(0xFF8E8E93.toInt())
                    status.text = "这段行程还没有识别结果（停止录制后自动识别，稍后回来看看）"
                }
            }
        }
    }

    /** trip_spike_1792134288394 → 「10-16 15:04」 */
    private fun tripTimeLabel(dirName: String): String {
        val ts = dirName.removePrefix("trip_spike_").toLongOrNull() ?: return ""
        return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))
    }

    /** 读 asr.json 的 error 字段（识别失败时落盘的原因），无则 null */
    private fun readAsrError(f: File): String? =
        try {
            org.json.JSONObject(f.readText()).optString("error").ifBlank { null }
        } catch (_: Exception) {
            null
        }

    /** 读 asr.json 的 failed 计数（按批次识别时失败的段数），无则 0 */
    private fun readAsrFailed(f: File): Int =
        try {
            org.json.JSONObject(f.readText()).optInt("failed")
        } catch (_: Exception) {
            0
        }

    /** 最新一条有识别的行程语音 → 「mm:ss 『文字』」多行文本（问答用），无则空串 */
    private fun latestAsrVoiceText(): String {
        val root = File(getExternalFilesDir(null), "trip_demo")
        val trips = root.listFiles { f -> f.isDirectory && f.name.startsWith("trip_") }
            ?.sortedBy { it.name } ?: return ""
        val withAsr = trips.lastOrNull { File(it, "asr.json").exists() } ?: return ""
        val segs = readAsrSegs(File(withAsr, "asr.json"))
        if (segs.isEmpty()) return ""
        return segs.joinToString("\n") { (t, txt) ->
            String.format("%02d:%02d", t / 60, t % 60) + " 「$txt」"
        }
    }

    /** 读 asr.json 的 segs → [(行程相对秒, 文字)] */
    private fun readAsrSegs(f: File): List<Pair<Long, String>> {
        return try {
            val j = org.json.JSONObject(f.readText())
            val arr = j.optJSONArray("segs") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                s.optLong("t") to s.optString("text")
            }.filter { it.second.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun emptyView(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 70, 0, 70)
        }
        box.addView(ImageView(this).apply { setImageResource(R.drawable.ic_empty_photo) })
        box.addView(TextView(this).apply {
            text = "还没有行程小计"
            textSize = 17f
            setTextColor(0xFF1C1C1E.toInt())
            setPadding(0, 16, 0, 0)
        })
        box.addView(TextView(this).apply {
            text = "回到录制页开始记录，或点下方「生成小计」"
            textSize = 13f
            setTextColor(0xFF8E8E93.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(40, 6, 40, 0)
        })
        return box
    }

    private fun buildItem(rec: MediaStoreHelper.SummaryFile): View {
        val row = layoutInflater.inflate(R.layout.item_summary, listContainer, false)
        val thumb = row.findViewById<ImageView>(R.id.thumb)
        val check = row.findViewById<ImageView>(R.id.check)
        val btnMore = row.findViewById<TextView>(R.id.btnMore)
        val dot = row.findViewById<View>(R.id.dot)

        row.findViewById<TextView>(R.id.time).text =
            if (rec.ts > 0) fmt.format(Date(rec.ts)) else rec.name

        val skipped = rec.text.startsWith("⏭")
        val body = row.findViewById<TextView>(R.id.body)
        body.text = when {
            skipped -> rec.text
            rec.ok -> rec.text
            else -> "⚠ ${rec.text}"
        }
        body.setTextColor(if (skipped || rec.ok) 0xFF1C1C1E.toInt() else 0xFFB3261E.toInt())

        val isNewest = listContainer.childCount == 0
        dot.setBackgroundResource(if (isNewest) R.drawable.bg_dot else R.drawable.bg_dot_gray)

        val isSelected = selected.contains(rec.name)
        check.visibility = if (selectMode) View.VISIBLE else View.GONE
        btnMore.visibility = if (selectMode) View.GONE else View.VISIBLE
        check.setImageResource(
            if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_check_empty
        )

        val sheetUri = rec.sheetName?.let {
            MediaStoreHelper.queryUriByName(this, MediaStoreHelper.SHEETS_DIR, it)
        }
        sheetUri?.let { loadThumb(it, thumb) }

        row.setOnClickListener {
            when {
                selectMode -> {
                    if (!selected.add(rec.name)) selected.remove(rec.name)
                    check.setImageResource(
                        if (selected.contains(rec.name)) R.drawable.ic_check_circle
                        else R.drawable.ic_check_empty
                    )
                    updateChrome()
                }
                sheetUri != null -> openImage(sheetUri)
            }
        }
        // 长按照片（整张卡片）→ 进入选择模式并选中该段；选择模式下长按=切换勾选
        row.setOnLongClickListener {
            if (!selectMode) {
                selected.add(rec.name)
                enterSelectMode()
            } else {
                if (!selected.add(rec.name)) selected.remove(rec.name)
                check.setImageResource(
                    if (selected.contains(rec.name)) R.drawable.ic_check_circle
                    else R.drawable.ic_check_empty
                )
                updateChrome()
            }
            row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            true
        }
        btnMore.setOnClickListener { showCardMenu(rec, sheetUri) }
        return row
    }

    /** 卡片 ⋮ 菜单：看原图 / 复制 / 编辑 / 多选 / 删除 */
    private fun showCardMenu(rec: MediaStoreHelper.SummaryFile, sheetUri: Uri?) {
        val options = mutableListOf<String>()
        if (sheetUri != null) options.add("看原图")
        options.add("复制文字")
        options.add("编辑")
        options.add("多选")
        options.add("删除")
        AlertDialog.Builder(this)
            .setTitle("这段小计")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "看原图" -> sheetUri?.let { openImage(it) }
                    "复制文字" -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("行程小计", rec.text))
                        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    "编辑" -> showEditDialog(rec)
                    "多选" -> {
                        selected.add(rec.name)
                        enterSelectMode()
                    }
                    "删除" -> confirmDelete(listOf(rec))
                }
            }
            .show()
    }

    /** 删除确认（小计 JSON + 对应拼图，不可恢复） */
    private fun confirmDelete(recs: List<MediaStoreHelper.SummaryFile>) {
        if (recs.isEmpty()) return
        val withSheet = recs.count { it.sheetName != null }
        val sheetHint = if (withSheet > 0) "及 $withSheet 张拼图" else ""
        AlertDialog.Builder(this)
            .setTitle("删除 ${recs.size} 段小计？")
            .setMessage("小计${sheetHint}会被一并删除，且不可恢复。")
            .setPositiveButton("删除") { _, _ -> deleteRecords(recs) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 执行删除：IO 线程删文件 → 回主线程刷新 */
    private fun deleteRecords(recs: List<MediaStoreHelper.SummaryFile>) {
        statusText.text = "正在删除…"
        CoroutineScope(Dispatchers.IO).launch {
            val deleted = ArrayList<String>()
            for (rec in recs) {
                val jsonOk = MediaStoreHelper.deleteByName(
                    this@SummaryActivity, MediaStoreHelper.SUMMARIES_DIR, rec.name
                )
                val sheetOk = rec.sheetName?.let {
                    MediaStoreHelper.deleteByName(this@SummaryActivity, MediaStoreHelper.SHEETS_DIR, it)
                } ?: true
                if (jsonOk && sheetOk) deleted.add(rec.name)
                else android.util.Log.w("TripSummary", "delete failed: ${rec.name}")
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                selected.removeAll(deleted.toSet())
                statusText.text = when {
                    deleted.isEmpty() -> "删除失败"
                    deleted.size == recs.size -> "已删除 ${deleted.size} 段"
                    else -> "已删除 ${deleted.size}/${recs.size} 段"
                }
                refresh()
            }
        }
    }

    private fun showEditDialog(rec: MediaStoreHelper.SummaryFile) {
        val input = EditText(this).apply {
            setText(rec.text)
            setTextColor(0xFF1C1C1E.toInt())
            textSize = 15f
            setPadding(40, 30, 40, 30)
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        val scroll = ScrollView(this).apply { addView(input) }
        AlertDialog.Builder(this)
            .setTitle("编辑小记")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isBlank()) {
                    Toast.makeText(this, "不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val json = org.json.JSONObject().apply {
                        put("ts", rec.ts)
                        put("frames", 0)
                        put("sheet", rec.sheetName)
                        put("ok", true)
                        put("edited", true)
                        put("text", newText)
                    }.toString()
                    MediaStoreHelper.saveText(
                        this@SummaryActivity, MediaStoreHelper.SUMMARIES_DIR, rec.name, json
                    )
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(this@SummaryActivity, "已保存", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 主动作 ----------

    /** 生成小计：手动触发一次总结（替代旧的“立即生成一次”） */
    private fun generateNow() {
        if (!StepVlConfig.configured(this)) {
            Toast.makeText(this, "请先在 API 设置里填 Key", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "正在生成…"
        engine.summarizeNow()
    }

    /**
     * 导出素材：把所有行程的原始素材打包到一个目录——
     * 拼图（sheets）→ PNG/JPG、语音识别（asr.json）→ 可读文本、录音（PCM）→ 可播放的 WAV。
     * 全部落在 /sdcard/Download/trip_demo/materials/ 下，文件管理器直接可见。
     */
    private fun exportMaterials() {
        CoroutineScope(Dispatchers.IO).launch {
            val progress = kotlinx.coroutines.withContext(Dispatchers.Main) {
                val view = layoutInflater.inflate(R.layout.dialog_export_progress, null)
                view.findViewById<TextView>(R.id.exportTitle).text = "正在导出素材"
                view.findViewById<TextView>(R.id.exportMsg).text = "正在收集拼图与语音…"
                val dlg = androidx.appcompat.app.AlertDialog.Builder(this@SummaryActivity)
                    .setView(view)
                    .setCancelable(false)
                    .create()
                dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dlg.show()
                dlg
            }
            fun step(msg: String) {
                // 必须回主线程更新 View（IO 线程直接 setText 会崩 App）
                runOnUiThread { runCatching { progress.findViewById<TextView>(R.id.exportMsg)?.text = msg } }
            }
            try {
                val root = File(getExternalFilesDir(null), "trip_demo")
                val sheets = MediaStoreHelper.listFiles(this@SummaryActivity, MediaStoreHelper.SHEETS_DIR)
                var copied = 0
                var failed = 0
                for ((name, uri) in sheets) {
                    val ok = runCatching {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@runCatching false
                        MediaStoreHelper.saveFile(
                            this@SummaryActivity,
                            "${MediaStoreHelper.ROOT}/materials/sheets", name, bytes, "image/jpeg"
                        ) != null
                    }.getOrDefault(false)
                    if (ok) copied++ else failed++
                }

                val trips = root.listFiles { f -> f.isDirectory && f.name.startsWith("trip_") }
                    ?.sortedBy { it.name }.orEmpty()
                var voiceFiles = 0
                var audioFiles = 0
                var audioBytes = 0L
                for (dir in trips) {
                    runCatching {
                        // 语音识别文本 → 可读 .txt
                        val asr = File(dir, "asr.json")
                        if (asr.exists()) {
                            val txt = runCatching {
                                val j = org.json.JSONObject(asr.readText())
                                val sb = StringBuilder()
                                sb.append("行程 ${dir.name.removePrefix("trip_")}\n\n")
                                j.optJSONArray("segs")?.let { arr ->
                                    for (i in 0 until arr.length()) {
                                        val s = arr.getJSONObject(i)
                                        val t = s.optLong("t")
                                        sb.append(String.format("%02d:%02d", t / 60, t % 60))
                                        sb.append("  ").append(s.optString("text")).append("\n")
                                    }
                                }
                                sb.toString()
                            }.getOrNull()
                            if (txt != null && MediaStoreHelper.saveFile(
                                    this@SummaryActivity,
                                    "${MediaStoreHelper.ROOT}/materials/voice",
                                    "${dir.name.removePrefix("trip_")}.txt",
                                    txt.toByteArray(), "text/plain"
                                ) != null
                            ) voiceFiles++
                        }
                        // 录音 PCM → 加 WAV 头，变成可播放音频
                        val pcm = File(dir, "audio/rec.pcm")
                        if (pcm.exists() && pcm.length() > 44) {
                            step("正在转换录音（${dir.name.removePrefix("trip_")}）…")
                            val wav = pcmToWav(pcm)
                            if (wav != null && MediaStoreHelper.saveFile(
                                    this@SummaryActivity,
                                    "${MediaStoreHelper.ROOT}/materials/audio",
                                    "${dir.name.removePrefix("trip_")}.wav",
                                    wav, "audio/wav"
                                ) != null
                            ) {
                                audioFiles++
                                audioBytes += wav.size
                            }
                        }
                    }.onFailure {
                        android.util.Log.w("TripSummary", "export trip ${dir.name} failed", it)
                        failed++
                    }
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    statusText.text = "素材已导出"
                    Toast.makeText(
                        this@SummaryActivity,
                        "已导出到 /sdcard/Download/trip_demo/materials/\n" +
                            "拼图 $copied 张 · 语音 $voiceFiles 份 · 录音 $audioFiles 段" +
                            if (audioBytes > 0) "（${audioBytes / 1024 / 1024}MB）" else "" +
                            if (failed > 0) " · $failed 个失败" else "",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    runCatching { progress.dismiss() }
                }
            }
        }
    }

    /** PCM(S16LE 16k 单声道) → WAV（加 44 字节头），失败返回 null */
    private fun pcmToWav(pcm: File): ByteArray? = try {
        val pcmBytes = pcm.readBytes()
        val sampleRate = AudioRecorder.SAMPLE_RATE.toLong()
        val channels = 1
        val byteRate = sampleRate * channels * 2
        val totalDataLen = pcmBytes.size + 36L
        java.io.ByteArrayOutputStream(pcmBytes.size + 44).use { out ->
            fun w(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun le32(v: Long) {
                out.write((v and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt())
                out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 24) and 0xFF).toInt())
            }
            fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            w("RIFF"); le32(totalDataLen); w("WAVE")
            w("fmt "); le32(16L); le16(1); le16(channels)
            le32(sampleRate); le32(byteRate); le16(channels * 2); le16(16)
            w("data"); le32(pcmBytes.size.toLong())
            out.write(pcmBytes)
            out.toByteArray()
        }
    } catch (e: Exception) {
        android.util.Log.e("TripSummary", "pcmToWav failed", e)
        null
    }

    private fun startLongAnalysis() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "先勾选至少一段小记", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, LongReportActivity::class.java)
                .putExtra(LongReportActivity.EXTRA_NAMES, selected.toTypedArray())
        )
    }

    /** 导出报告（有勾选导出勾选的，否则导出全部） */
    private fun exportSelected() {
        CoroutineScope(Dispatchers.IO).launch {
            val all = MediaStoreHelper.loadSummaries(this@SummaryActivity).sortedBy { it.ts }
            val scope = if (selected.isNotEmpty()) all.filter { it.name in selected } else all
            if (scope.isEmpty()) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Toast.makeText(this@SummaryActivity, "没有可导出的小记", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val sections = ArrayList<ReportBuilder.Section>()
            for (rec in scope) {
                val sheetName = rec.sheetName ?: continue
                val uri = MediaStoreHelper.queryUriByName(
                    this@SummaryActivity, MediaStoreHelper.SHEETS_DIR, sheetName
                )
                val img = uri?.let { u ->
                    try {
                        contentResolver.openInputStream(u)?.use { it.readBytes() }
                    } catch (_: Exception) { null }
                }
                if (img != null) sections.add(ReportBuilder.Section(timeLabel(rec.ts), rec.text, img))
            }
            if (sections.isEmpty()) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Toast.makeText(this@SummaryActivity, "选中的小记没有可用拼图", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            // 导出进度弹窗（自定义卡片样式，主线程创建；不可取消、步骤显眼）
            val progress = kotlinx.coroutines.withContext(Dispatchers.Main) {
                val view = layoutInflater.inflate(R.layout.dialog_export_progress, null)
                val dlg = androidx.appcompat.app.AlertDialog.Builder(this@SummaryActivity)
                    .setView(view)
                    .setCancelable(false)
                    .create()
                // 去掉系统默认对话框边框，让自定义卡片的圆角/阴影完整呈现
                dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dlg.show()
                dlg
            }
            fun step(msg: String) {
                progress.findViewById<TextView>(R.id.exportMsg)?.text = msg
            }
            // ①-③ 全流程包在 try 里：任何意外都确保关掉弹窗（finally），不再崩 App
            try {
                // ① 素材再次送给 step-3.7flash：所选拼图 + 各段小记 → 报告叙述
                val narrative = try {
                    val texts = scope.map { it.text }
                    val voice = latestAsrVoiceText()
                    // 模型输入上限 12 张（段数多时均匀抽），避免超限；HTML 仍保留全部段
                    val withImg = sections.filter { it.imageJpeg != null }
                    val picked = if (withImg.size <= 12) withImg
                    else (0 until 12).map { withImg[it * (withImg.size - 1) / 11] }
                    android.util.Log.i(
                        "TripSummary",
                        "导出报告素材: ${texts.size}段小记(送图${picked.size}张) + 语音${voice.length}字"
                    )
                    StepVlClient().summarizeMulti(
                        baseUrl = StepVlConfig.baseUrl(this@SummaryActivity),
                        apiKey = StepVlConfig.apiKey(this@SummaryActivity),
                        model = StepVlConfig.model(this@SummaryActivity),
                        jpegs = picked.mapNotNull { it.imageJpeg },
                        labels = picked.map { it.label },
                        prompt = StepVlClient.REPORT_PROMPT +
                            "\n\n【各段小记】\n" +
                            texts.mapIndexed { i, t -> "第${i + 1}段（${sections[i].label}）：$t" }
                                .joinToString("\n") +
                            (if (voice.isNotBlank())
                                "\n\n【途中的语音（用户随行说的话，带相对时间）】\n$voice" else ""),
                    )
                } catch (e: Exception) {
                    // 模型失败不阻塞导出：回退为纯文本拼接
                    android.util.Log.w("TripSummary", "report narrative failed", e)
                    sections.joinToString("\n") { "· ${it.text}" }
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    step("正在生成卡通集合图…")
                }
                // ② 卡通集合图：未填生图 key 直接跳过；失败不阻塞导出
                var cartoon: ByteArray? = null
                var cartoonNote: String? = null
                if (!StepVlConfig.imgConfigured(this@SummaryActivity)) {
                    cartoonNote = "未在设置里填写「模型三 · 卡通集合图生图」的 apiKey"
                } else try {
                    cartoon = ImageGenClient().generate(
                        baseUrl = StepVlConfig.imgBaseUrl(this@SummaryActivity),
                        apiKey = StepVlConfig.imgApiKey(this@SummaryActivity),
                        model = StepVlConfig.imgModel(this@SummaryActivity),
                        prompt = cartoonPrompt(sections),
                    )
                } catch (e: Exception) {
                    android.util.Log.w("TripSummary", "cartoon gen failed", e)
                    cartoonNote = "卡通集合图生成失败：${e.message}"
                }
                // ③ 组装 HTML（模型叙述 + 卡通集合图 + 拼图网格）
                val html = ReportBuilder.build(
                    title = "行程小计 · ${sections.size} 段",
                    narrative = narrative,
                    sections = sections,
                    videoBytes = null,
                    cartoonImage = cartoon,
                    cartoonNote = cartoonNote,
                )
                val name = "report_${System.currentTimeMillis()}.html"
                val uri = MediaStoreHelper.saveText(
                    this@SummaryActivity, "${MediaStoreHelper.ROOT}/reports", name, html,
                    mime = "text/html"
                )
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (uri == null) {
                        Toast.makeText(this@SummaryActivity, "导出失败", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    // 只提示保存位置，不弹分享界面（需要分享可去 reports 目录手动分享）
                    Toast.makeText(
                        this@SummaryActivity,
                        "已导出：/sdcard/Download/trip_demo/reports/$name",
                        Toast.LENGTH_LONG
                    ).show()
                    statusText.text = "已导出报告（$name）"
                }
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    runCatching { progress.dismiss() }
                }
            }
        }
    }

    private fun timeLabel(ts: Long): String = if (ts > 0) fmt.format(Date(ts)) else ""

    /** 卡通集合图提示词：把所选各段串成左右连贯的分镜描述（图内不出现文字） */
    private fun cartoonPrompt(sections: List<ReportBuilder.Section>): String {
        val beats = sections.take(6).mapIndexed { i, s ->
            val t = s.text.replace("\n", " ").trim().take(50)
            "第${i + 1}格（${s.label}）$t"
        }.joinToString("；")
        return "写实3D卡通风格手帐插画：把这段旅程画成一页3D手帐——" +
            "用4到6个圆角小格子像立体贴纸一样错落贴在浅色纸纹背景上，格子有轻微厚度和投影，" +
            "像真实拍立得照片贴在笔记本上，格子之间用细线箭头依次串联；" +
            "每个格子里画当时场景的3D卡通再现：$beats。" +
            "整体3D渲染质感、柔和自然光照、干净的写实质材（木纹、布料、金属、屏幕微光都真实可信），" +
            "色彩明快干净但不甜腻，像一本3D动画电影的分镜手帐；" +
            "画面里不要出现任何文字、字母和数字。"
    }
    // ---------- Ask 问答（历史保留在页面） ----------

    private fun ask() {
        val et = findViewById<EditText>(R.id.etQuestion)
        val q = et.text.toString().trim()
        if (q.isBlank()) {
            Toast.makeText(this, "先输入问题", Toast.LENGTH_SHORT).show()
            return
        }
        if (!StepVlConfig.configured(this)) {
            Toast.makeText(this, "请先在 API 设置里填 Key", Toast.LENGTH_SHORT).show()
            return
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(et.windowToken, 0)
        et.text.clear()

        // 先插入占位问答
        val pending = Qa(q, "思考中…", true)
        qaList.add(pending)
        renderQa()

        CoroutineScope(Dispatchers.IO).launch {
            val all = MediaStoreHelper.loadSummaries(this@SummaryActivity).sortedBy { it.ts }
            val scope = if (selected.isNotEmpty()) all.filter { it.name in selected } else all
            val sheets = ArrayList<ByteArray>()
            for (rec in scope.sortedByDescending { it.ts }) {
                if (sheets.size >= 4) break
                val uri = rec.sheetName?.let {
                    MediaStoreHelper.queryUriByName(this@SummaryActivity, MediaStoreHelper.SHEETS_DIR, it)
                } ?: continue
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size < 1_500_000) sheets.add(bytes)
                    }
                } catch (_: Exception) {
                }
            }
            val answer = try {
                StepVlClient().ask(
                    baseUrl = StepVlConfig.baseUrl(this@SummaryActivity),
                    apiKey = StepVlConfig.apiKey(this@SummaryActivity),
                    model = StepVlConfig.model(this@SummaryActivity),
                    question = q,
                    summaryTexts = scope.map { it.text },
                    sheets = sheets,
                    // 位置：各段起点→终点（单独成段，模型好按路线回答）
                    gpsText = scope.mapIndexedNotNull { i, rec ->
                        rec.loc?.let { "第${i + 1}段：$it" }
                    }.joinToString("\n"),
                    // 语音：最新一条有识别的行程，mm:ss 「文字」
                    voiceText = latestAsrVoiceText(),
                )
            } catch (e: CensoredException) {
                "内容审核未通过，换个问法或稍后再试"
            } catch (e: Exception) {
                "提问失败：${e.message}"
            }
            val ok = !answer.startsWith("提问失败") && !answer.startsWith("内容审核")
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val idx = qaList.indexOf(pending)
                if (idx >= 0) qaList[idx] = Qa(q, answer, ok)
                renderQa()
            }
        }
    }

    private fun renderQa() {
        runOnUiThread {
            askHistory.removeAllViews()
            if (qaList.isEmpty()) {
                askHistory.visibility = View.GONE
                return@runOnUiThread
            }
            askHistory.visibility = View.VISIBLE
            for (qa in qaList) {
                askHistory.addView(TextView(this).apply {
                    text = "问：${qa.q}"
                    textSize = 13f
                    setTextColor(0xFF8E8E93.toInt())
                    setPadding(0, 12, 0, 2)
                })
                askHistory.addView(TextView(this).apply {
                    text = qa.a
                    textSize = 15f
                    setTextColor(if (qa.ok) 0xFF1C1C1E.toInt() else 0xFFB3261E.toInt())
                    setPadding(0, 2, 0, 4)
                    setTextIsSelectable(true)
                })
            }
            // 问答区在页面顶部：回答就位后滚到顶部展示，而不是滑到最底部的小计
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_UP) }
        }
    }

    // ---------- 工具 ----------

    private fun loadThumb(uri: Uri, iv: ImageView) {
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bmp = BitmapFactory.decodeStream(input, null, opts)
                    runOnUiThread { iv.setImageBitmap(bmp) }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun openImage(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "没有可查看图片的应用", Toast.LENGTH_SHORT).show()
        }
    }
}
