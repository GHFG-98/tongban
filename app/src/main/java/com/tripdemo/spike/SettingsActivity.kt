package com.tripdemo.spike

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页：采集设置（点行弹单选窗）+ 提示词（可编辑 + 预设点选）+ API 设置。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val tvInterval = findViewById<TextView>(R.id.tvIntervalValue)
        val tvFrames = findViewById<TextView>(R.id.tvFramesValue)
        val etBase = findViewById<EditText>(R.id.etBaseUrl)
        val etKey = findViewById<EditText>(R.id.etApiKey)
        val etModel = findViewById<EditText>(R.id.etModel)
        val etGreetBase = findViewById<EditText>(R.id.etGreetBaseUrl)
        val etGreetKey = findViewById<EditText>(R.id.etGreetApiKey)
        val etGreetModel = findViewById<EditText>(R.id.etGreetModel)
        val etImgBase = findViewById<EditText>(R.id.etImgBaseUrl)
        val etImgKey = findViewById<EditText>(R.id.etImgApiKey)
        val etImgModel = findViewById<EditText>(R.id.etImgModel)
        val etGreet = findViewById<EditText>(R.id.etGreetPrompt)
        val etSummary = findViewById<EditText>(R.id.etSummaryPrompt)

        fun refreshValues() {
            tvInterval.text = CaptureSettings.intervalLabel(CaptureSettings.intervalMs(this))
            tvFrames.text = "${CaptureSettings.framesPerSummary(this)} 帧"
        }
        refreshValues()

        // 取帧间隔单选
        findViewById<LinearLayout>(R.id.rowInterval).setOnClickListener {
            val labels = CaptureSettings.INTERVAL_OPTIONS.map { CaptureSettings.intervalLabel(it) }
                .toTypedArray()
            val current = CaptureSettings.indexOfInterval(CaptureSettings.intervalMs(this))
            AlertDialog.Builder(this)
                .setTitle("取帧间隔")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    CaptureSettings.save(
                        this,
                        CaptureSettings.INTERVAL_OPTIONS[which],
                        CaptureSettings.framesPerSummary(this)
                    )
                    refreshValues()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 每几帧总结一次单选
        findViewById<LinearLayout>(R.id.rowFrames).setOnClickListener {
            val labels = CaptureSettings.FRAMES_PER_SUMMARY_OPTIONS.map { "$it 帧" }.toTypedArray()
            val current = CaptureSettings.indexOfFrames(CaptureSettings.framesPerSummary(this))
            AlertDialog.Builder(this)
                .setTitle("每几帧总结一次")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    CaptureSettings.save(
                        this,
                        CaptureSettings.intervalMs(this),
                        CaptureSettings.FRAMES_PER_SUMMARY_OPTIONS[which]
                    )
                    refreshValues()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 行程录音开关
        val switchAudio = findViewById<Switch>(R.id.switchAudio)
        switchAudio.isChecked = CaptureSettings.audioEnabled(this)
        findViewById<LinearLayout>(R.id.rowAudio).setOnClickListener {
            switchAudio.isChecked = !switchAudio.isChecked
        }
        switchAudio.setOnCheckedChangeListener { _, isChecked ->
            CaptureSettings.setAudioEnabled(this, isChecked)
        }

        // 图片发送模式单选
        val tvImageMode = findViewById<TextView>(R.id.tvImageMode)
        fun refreshImageMode() {
            tvImageMode.text = when (CaptureSettings.imageSendMode(this)) {
                CaptureSettings.IMAGE_MODE_ALL -> "全部原图"
                CaptureSettings.IMAGE_MODE_VIDEO -> "小视频"
                else -> "拼图"
            }
        }
        refreshImageMode()
        findViewById<LinearLayout>(R.id.rowImageMode).setOnClickListener {
            val options = arrayOf(
                "拼图（多帧合成一张，省 token）",
                "全部原图（不合成，最多12帧）",
                "小视频（帧合成MP4，最省且时序更好）",
            )
            val current = when (CaptureSettings.imageSendMode(this)) {
                CaptureSettings.IMAGE_MODE_ALL -> 1
                CaptureSettings.IMAGE_MODE_VIDEO -> 2
                else -> 0
            }
            AlertDialog.Builder(this)
                .setTitle("发给大模型的图片")
                .setSingleChoiceItems(options, current) { dialog, which ->
                    CaptureSettings.setImageSendMode(
                        this,
                        when (which) {
                            1 -> CaptureSettings.IMAGE_MODE_ALL
                            2 -> CaptureSettings.IMAGE_MODE_VIDEO
                            else -> CaptureSettings.IMAGE_MODE_SHEET
                        }
                    )
                    refreshImageMode()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 提示词：当前值 + 预设（点行弹单选）
        etGreet.setText(StepVlConfig.greetPrompt(this))
        etSummary.setText(StepVlConfig.summaryPrompt(this))
        val tvGreetPreset = findViewById<TextView>(R.id.tvGreetPreset)
        val tvSummaryPreset = findViewById<TextView>(R.id.tvSummaryPreset)
        // 预设行显示按当前 prompt 匹配预设名（匹配不上=自定义；空=默认方案）
        fun greetLabel(text: String) =
            PromptPresets.greetPresets.find { it.prompt == text }?.name
                ?: if (text.isBlank()) PromptPresets.greetPresets.first().name else "自定义"
        fun summaryLabel(text: String) =
            PromptPresets.summaryPresets.find { it.prompt == text }?.name
                ?: if (text.isBlank()) PromptPresets.summaryPresets.first().name else "自定义"
        tvGreetPreset.text = greetLabel(etGreet.text.toString())
        tvSummaryPreset.text = summaryLabel(etSummary.text.toString())
        // 提示词框只作预览：盒内上下拖动看全文，点按弹完整编辑窗（解决长 prompt 显示不完整）
        setupPromptBox(etGreet, tvGreetPreset, PromptPresets.greetPresets, "实时问候 prompt")
        setupPromptBox(etSummary, tvSummaryPreset, PromptPresets.summaryPresets, "行程小计 prompt")
        setupPresetRow(
            R.id.rowGreetPreset, tvGreetPreset, PromptPresets.greetPresets
        ) { preset ->
            etGreet.setText(preset.prompt)
            tvGreetPreset.text = preset.name
        }
        setupPresetRow(
            R.id.rowSummaryPreset, tvSummaryPreset, PromptPresets.summaryPresets
        ) { preset ->
            etSummary.setText(preset.prompt)
            tvSummaryPreset.text = preset.name
        }

        etBase.setText(StepVlConfig.baseUrl(this))
        etKey.setText(StepVlConfig.apiKey(this))
        etModel.setText(StepVlConfig.model(this))
        etGreetBase.setText(StepVlConfig.greetBaseUrl(this))
        etGreetKey.setText(StepVlConfig.greetApiKey(this))
        etGreetModel.setText(StepVlConfig.greetModel(this))
        etImgBase.setText(StepVlConfig.imgBaseUrl(this))
        etImgKey.setText(StepVlConfig.imgApiKey(this))
        etImgModel.setText(StepVlConfig.imgModel(this))

        findViewById<TextView>(R.id.btnSaveSettings).setOnClickListener {
            val base = etBase.text.toString().trim()
            val key = etKey.text.toString().trim()
            val model = etModel.text.toString().trim()
            if (base.isBlank() || key.isBlank()) {
                Toast.makeText(this, "模型一的 baseUrl 和 apiKey 必填", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 提示词留空 = 用默认
            StepVlConfig.saveGreetPrompt(this, etGreet.text.toString().trim())
            StepVlConfig.saveSummaryPrompt(this, etSummary.text.toString().trim())
            StepVlConfig.save(this, base, key, model)
            // 模型二/三可留空（留空 = 用默认地址/模型名，key 不填则对应功能不可用）
            StepVlConfig.saveGreet(
                this,
                etGreetBase.text.toString().trim().ifBlank { StepVlConfig.DEFAULT_GREET_BASE_URL },
                etGreetKey.text.toString().trim(),
                etGreetModel.text.toString().trim().ifBlank { StepVlConfig.DEFAULT_GREET_MODEL },
            )
            StepVlConfig.saveImg(
                this,
                etImgBase.text.toString().trim().ifBlank { StepVlConfig.DEFAULT_IMG_BASE_URL },
                etImgKey.text.toString().trim(),
                etImgModel.text.toString().trim().ifBlank { StepVlConfig.DEFAULT_IMG_MODEL },
            )
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 提示词框：盒内拖动可看全文（无需聚焦），点按弹大编辑窗完整编辑。
     * 保存后同步刷新预设名标签；「清空」= 用默认方案。
     */
    private fun setupPromptBox(
        box: EditText,
        label: TextView,
        presets: List<PromptPresets.Preset>,
        title: String,
    ) {
        box.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        box.setOnClickListener {
            val input = EditText(this).apply {
                setText(box.text.toString())
                setTextColor(0xFF1C1C1E.toInt())
                textSize = 14f
                setPadding(40, 30, 40, 30)
                minLines = 12
                gravity = android.view.Gravity.TOP
                setBackgroundColor(0xFFF7F7FA.toInt())
            }
            val scroll = ScrollView(this).apply { addView(input) }
            AlertDialog.Builder(this)
                .setTitle("$title（${box.text.toString().length} 字）")
                .setView(scroll)
                .setPositiveButton("保存") { _, _ ->
                    val t = input.text.toString().trim()
                    box.setText(t)
                    label.text = presets.find { it.prompt == t }?.name
                        ?: if (t.isBlank()) presets.first().name else "自定义"
                }
                .setNeutralButton("清空用默认") { _, _ ->
                    box.setText("")
                    label.text = presets.first().name
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 预设方案：点行弹出单选列表，选中后填入对应输入框 */
    private fun setupPresetRow(
        rowId: Int,
        valueView: TextView,
        presets: List<PromptPresets.Preset>,
        onPick: (PromptPresets.Preset) -> Unit,
    ) {
        findViewById<LinearLayout>(rowId).setOnClickListener {
            val names = presets.map { it.name }.toTypedArray()
            val current = names.indexOf(valueView.text.toString()).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("预设方案")
                .setSingleChoiceItems(names, current) { dialog, which ->
                    onPick(presets[which])
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }


}
