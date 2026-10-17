# 瞳伴（Tongban）

一个**行程回看** Android 应用：按间隔抓拍、自动拼图、调用多模态大模型生成「行程小计」，
支持实时主动问候、途中语音识别（ASR）、行程问答，以及一键导出精美 HTML 报告
（含 3D 卡通手帐风格的「旅程集合图」）。

> 单人/小团队自用向的开源实现，端侧抽帧 + 云端多模态理解。
> 代码不含任何 API Key，所有模型凭证由使用者在设置页自行填写（只存本机）。

---

## 功能

| 模块 | 说明 |
| --- | --- |
| 间隔抓帧 | 取帧间隔可调（1s ~ 2min），帧自动旋转转正、写入相册可见目录 |
| 行程小计 | 每 N 帧自动调多模态模型生成一段中文小计；**语音、时间戳、GPS 一起送模型** |
| 实时问候 | 每 6 秒一帧调视觉模型，生成 ≤15 字的主动搭话，悬浮在预览上 |
| 语音识别 | 录音按批次切段送 ASR，结果**实时增量**显示在「行程语音」卡，并参与总结 |
| 行程问答 | 对选中/全部小计提问，可读小计 + 位置 + 语音 + 拼图 |
| 导出报告 | 素材再送模型生成叙述 + 3D 卡通手帐集合图 + 双列拼图网格，自包含 HTML |
| 导出素材 | 一键导出全部拼图 / 语音文本 / WAV 录音到 `Download/trip_demo/materials/` |
| 选择模式 | 长按照片多选，支持删除 / 导出所选 / 长尺度分析 |

## 模型配置（三套，设置页自行填写）

| 用途 | 默认模型 | 说明 |
| --- | --- | --- |
| 模型一 · 行程总结与问答 | `step-3.7-flash` | 小计生成、问答、导出报告叙述 |
| 模型二 · 实时问候与语音识别 | `step-1o-turbo-vision` | 实时问候；同一 baseUrl/key 也用于 ASR（ASR 模型名固定 `stepaudio-3-asr-max`） |
| 模型三 · 卡通集合图生图 | `gpt-image-2` | OpenAI 兼容 `images/generations` |

- 三套都是 **OpenAI 兼容协议**（baseUrl + apiKey + model），可指向任何兼容网关。
- 凭证只写入应用私有 SharedPreferences，**不联网上传、不含在仓库里**。
- 模型一必填；模型二/三不填则对应功能自动降级（问候/生图跳过，其余正常）。

## 构建

```bash
# 需要 JDK 17+ 与 Android SDK（compileSdk 35）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

~/gradle-8.9/bin/gradle -p . assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

最低支持 Android 10（API 29）。

## 使用

1. 启动后进入设置页，填写至少「模型一」的 baseUrl / apiKey / model。
2. 主界面点录制按钮开始行程；需要语音就打开「收音」开关。
3. 底部「行程小计」进入小计页：看语音/小计、提问、长按多选、导出。
4. 产物位置：
   - 小计 / 拼图：`/sdcard/Download/trip_demo/{summaries,sheets}`
   - 报告：`/sdcard/Download/trip_demo/reports`
   - 素材包：`/sdcard/Download/trip_demo/materials`

## 代码结构

```
app/src/main/java/com/tripdemo/spike/
├── CaptureService.kt        # 常驻采集服务（相机双流、收音、状态广播）
├── IntervalCapturer.kt      # ImageAnalysis：按间隔抽帧 / 问候帧 / 预览兜底
├── SummaryEngine.kt         # 小计生成（批次边界、语音/GPS/时间戳拼 prompt）
├── GreetingEngine.kt        # 实时问候
├── AudioRecorder.kt         # 16k 单声道 PCM 录制（按字节段切分）
├── AsrClient.kt             # ASR（SSE 流式）
├── ImageGenClient.kt        # 卡通集合图生图
├── StepVlClient.kt          # OpenAI 兼容 chat/completions + 三套模型配置
├── ContactSheetBuilder.kt   # 拼图合成（Canvas，格子随帧宽高比自适应）
├── ReportBuilder.kt         # 自包含 HTML 报告
├── VideoAssembler.kt        # 多段拼图 → MP4（长尺度分析用）
├── MediaStoreHelper.kt      # MediaStore 落盘 / 反查 / 导出素材
├── TripMetaWriter.kt        # 帧元数据（时间 + GPS）账本
├── MainActivity.kt          # 取景器 + 录制控制
├── SummaryActivity.kt       # 行程小计页（语音卡 / 问答 / 多选 / 导出）
├── LongReportActivity.kt    # 长尺度分析（多图/视频理解）
├── SettingsActivity.kt      # 采集设置 + 提示词 + 三套模型配置
└── PromptPresets.kt         # 问候 / 总结提示词预设
```

## 已知限制

- 实时问候与 ASR 目前按 StepFun 的接口形状实现；换其他网关需自行适配请求体。
- 语音识别按批次增量写入，停止时会补识别末尾不满一批的片段。
- 生图失败不影响报告导出（报告里省去集合图区块）。

## License

[MIT](LICENSE)
