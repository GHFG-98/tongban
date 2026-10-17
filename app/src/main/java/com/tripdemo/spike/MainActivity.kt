package com.tripdemo.spike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 录制主界面（纯 UI）：
 * 相机与采集在 CaptureService 里常驻运行，本页只负责
 * ① 取景（轮询 Service 的最新帧）② 录制开关 ③ 状态展示 ④ 跳转。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewTexture: android.view.TextureView
    private lateinit var statusText: TextView
    private lateinit var summaryStatusText: TextView
    private lateinit var recordButton: android.widget.FrameLayout
    private lateinit var innerCircle: View
    private lateinit var innerSquare: View
    private lateinit var configText: TextView
    private lateinit var savePathText: TextView
    private lateinit var idleHint: TextView
    private lateinit var greetPill: TextView
    private lateinit var btnAudio: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val pillHandler = Handler(Looper.getMainLooper())
    private val hidePill = Runnable { greetPill.visibility = View.GONE }

    private var recording = false
    private var previewBufferW = 0
    private var previewBufferH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewTexture = findViewById(R.id.previewTexture)
        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)
        summaryStatusText = findViewById(R.id.summaryStatusText)
        innerCircle = findViewById(R.id.innerCircle)
        innerSquare = findViewById(R.id.innerSquare)
        idleHint = findViewById(R.id.idleHint)
        configText = findViewById(R.id.configText)
        savePathText = findViewById(R.id.savePathText)
        greetPill = findViewById(R.id.greetPill)
        btnAudio = findViewById(R.id.btnAudio)
        refreshAudioButton()

        // 屏幕常亮（车载/演示场景）
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 预览表面交给 Service 的 Preview 流（GPU 直出）
        previewTexture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                CaptureService.attachPreviewSurface(android.view.Surface(st))
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                // w/h 是 CameraX setDefaultBufferSize 的流尺寸（纹理真实尺寸）
                previewBufferW = w
                previewBufferH = h
                applyPreviewTransform()
            }
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                CaptureService.attachPreviewSurface(null)
                return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }

        recordButton.setOnClickListener { toggleRecording() }
        findViewById<TextView>(R.id.btnSummary).setOnClickListener {
            startActivity(Intent(this, SummaryActivity::class.java))
        }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnAudio.setOnClickListener { toggleAudio() }

        // 启动常驻采集服务（取景 + 主动问候立即开始）
        ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java))
    }

    override fun onResume() {
        super.onResume()
        CaptureService.listeners.add(stateListener)
        SummaryEngine.get(applicationContext).onStatus = { msg ->
            runOnUiThread { summaryStatusText.text = "总结：$msg" }
        }
        GreetingEngine.get(applicationContext).onGreeting = { text -> showGreetPill(text) }
        CaptureService.state?.let { renderState(it) }
        uiHandler.postDelayed({ applyPreviewTransform() }, 300)
        uiHandler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        CaptureService.listeners.remove(stateListener)
        uiHandler.removeCallbacks(ticker)
    }

    private val stateListener: (CaptureService.State) -> Unit = { renderState(it) }

    private fun renderState(s: CaptureService.State) {
        recording = s.recording
        statusText.text = buildString {
            append(if (s.recording) "● 录制中" else "未开始")
            append("  ${s.seconds}s · ${s.frames} 帧 · ${s.bytes / 1024}KB")
        }
        configText.text = s.config
        savePathText.text = s.saveRoot
        innerCircle.visibility = if (s.recording) View.GONE else View.VISIBLE
        innerSquare.visibility = if (s.recording) View.VISIBLE else View.GONE
        idleHint.visibility = if (s.recording) View.GONE else View.VISIBLE
    }

    /** 状态秒表刷新 */
    private val ticker = object : Runnable {
        override fun run() {
            CaptureService.state?.let { s ->
                if (s.recording) {
                    val secs = s.seconds + 1
                    statusText.text = "● 录制中  ${secs}s · ${s.frames} 帧 · ${s.bytes / 1024}KB"
                }
            }
            uiHandler.postDelayed(this, 1000)
        }
    }

    private fun toggleRecording() {
        val action = if (recording) CaptureService.ACTION_STOP_RECORD else CaptureService.ACTION_START_RECORD
        ContextCompat.startForegroundService(
            this, Intent(this, CaptureService::class.java).setAction(action)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /**
     * TextureView  FIT 变换：只缩放不平移旋转。
     * CameraX 对提供的 Surface 已按设备方向渲染（方向它负责）；
     * TextureView 默认把纹理拉伸填满 view（纵向拉长=失真），这里按纹理真实比例 FIT 居中。
     */
    private fun applyPreviewTransform() {
        val tw = previewBufferW.toFloat()
        val th = previewBufferH.toFloat()
        val vw = previewTexture.width.toFloat()
        val vh = previewTexture.height.toFloat()
        if (tw <= 0f || th <= 0f || vw <= 0f || vh <= 0f) return
        val scale = minOf(vw / tw, vh / th)
        val m = android.graphics.Matrix()
        m.postScale(scale, scale)
        m.postTranslate((vw - tw * scale) / 2f, (vh - th * scale) / 2f)
        previewTexture.setTransform(m)
        android.util.Log.w("CamDiag", "FIT buf=${tw}x$th view=${vw}x$vh scale=$scale")
    }

    /** 收音开关（录制按钮上方） */
    private fun toggleAudio() {
        val on = !CaptureSettings.audioEnabled(this)
        if (on &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        CaptureSettings.setAudioEnabled(this, on)
        refreshAudioButton()
        Toast.makeText(
            this,
            if (on) "已开启录音（下次开始记录生效）" else "已关闭录音",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshAudioButton() {
        val on = CaptureSettings.audioEnabled(this)
        btnAudio.text = if (on) "收音：开" else "收音：关"
        btnAudio.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt())
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            CaptureSettings.setAudioEnabled(this, true)
            refreshAudioButton()
            Toast.makeText(this, "已开启录音（下次开始记录生效）", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要录音权限才能开启", Toast.LENGTH_SHORT).show()
        }
    }

    /** 预览界面上飘一句实时问候（Step-1o Turbo Vision，≤15字），6秒后自动消失） */
    private fun showGreetPill(text: String) {
        runOnUiThread {
            greetPill.text = "\"$text\""
            greetPill.visibility = View.VISIBLE
            pillHandler.removeCallbacks(hidePill)
            pillHandler.postDelayed(hidePill, 6000)
        }
    }
}
