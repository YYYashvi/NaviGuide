package com.example.objectdetectionapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivityHybrid"

        // Cloud Vision throttling / filtering
        private const val CLOUD_MIN_INTERVAL_MS = 1500L
        private const val CLOUD_MIN_SCORE = 0.45f

        // TTS
        private const val SPEECH_COOLDOWN_MS = 2000L

        // Prefs keys (for auto-raise volume consent)
        private const val PREFS = "prefs"
        private const val KEY_AUTO_RAISE_VOLUME = "auto_raise_volume"
    }

    // ===== Cloud Vision config =====
    private val CLOUD_API_KEY: String by lazy { getString(R.string.vision_api_key) }
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private var lastCloudAt = 0L

    // Vision result holder
    private data class VisionBox(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val score: Float, val label: String
    )

    // YOLOv8 (local) fallback
    private var yoloClassifier: YoloV8Classifier? = null

    // Camera / UI
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var overlayView: OverlayView
    private lateinit var btnStartStop: MaterialButton
    private lateinit var switchSpeech: SwitchMaterial
    private lateinit var detectedLabel: TextView

    // TTS
    private var tts: TextToSpeech? = null
    private var lastSpoken: String? = null
    private var lastSpokenTimeMs: Long = 0L
    private var isCameraRunning = true
    private var isSpeechOn = true

    // For a little “persistence” on speech
    private val detectionCounters: MutableMap<String, Int> = HashMap()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) startCamera()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayView = findViewById(R.id.overlayView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        btnStartStop = findViewById(R.id.btnStartStop)
        switchSpeech = findViewById(R.id.switchSpeech)
        detectedLabel = findViewById(R.id.detectedLabel)

        overlayView.visibility = View.VISIBLE
        detectedLabel.visibility = View.VISIBLE

        isSpeechOn = switchSpeech.isChecked
        btnStartStop.text = if (isCameraRunning) "Stop" else "Start"

        // Init YOLO fallback
        yoloClassifier = try {
            YoloV8Classifier(this) // uses yolov8.tflite in assets
        } catch (e: Exception) {
            Log.e(TAG, "YOLO init failed: ${e.message}", e)
            null
        }

        btnStartStop.setOnClickListener {
            isCameraRunning = !isCameraRunning
            btnStartStop.text = if (isCameraRunning) "Stop" else "Start"
            if (!isCameraRunning) {
                overlayView.boxes = emptyList()
                overlayView.labels = emptyList()
                overlayView.invalidate()
            }
        }

        switchSpeech.setOnCheckedChangeListener { _, isChecked ->
            isSpeechOn = isChecked
            if (isChecked) {
                checkAndWarnVolume()
                setAutoRaise(true)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Init TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            } else {
                Log.w(TAG, "TTS initialization failed: $status")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndWarnVolume()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
        try { yoloClassifier?.close() } catch (_: Exception) {}
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<androidx.camera.view.PreviewView>(R.id.previewView).surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isCameraRunning) analyzeImage(imageProxy) else imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed: ${e.message}", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Online/Offline switch core ----------

    private fun analyzeImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap() ?: run { imageProxy.close(); return }
            val scaled = Bitmap.createScaledBitmap(bitmap, 640, 640, true)

            if (isInternetAvailable()) {
                // ---------------- Cloud Vision path ----------------
                val now = System.currentTimeMillis()
                if (now - lastCloudAt >= CLOUD_MIN_INTERVAL_MS) {
                    lastCloudAt = now

                    val b64 = bitmapToBase64Jpeg(scaled, quality = 80)
                    val cloudBoxes = callVisionObjectLocalizationBase64(b64, CLOUD_API_KEY)
                        .filter { it.score >= CLOUD_MIN_SCORE }

                    if (cloudBoxes.isNotEmpty()) {
                        val mapped = cloudBoxes.map {
                            floatArrayOf(it.x1, it.y1, it.x2, it.y2, it.score, -1f)
                        }
                        overlayView.post {
                            overlayView.boxes = mapped
                            overlayView.labels = cloudBoxes.map { it.label }
                            overlayView.invalidate()
                        }

                        val name = cloudBoxes.maxByOrNull { it.score }!!.label
                        runOnUiThread {
                            detectedLabel.visibility = View.VISIBLE
                            detectedLabel.text = "Cloud: $name"
                        }
                        if (isSpeechOn) maybeSpeak(name)
                    } else {
                        overlayView.post {
                            overlayView.boxes = emptyList()
                            overlayView.labels = emptyList()
                            overlayView.invalidate()
                        }
                        runOnUiThread { detectedLabel.text = "Detected: None" }
                    }
                }
            } else {
                // ---------------- YOLO (offline) path ----------------
                val detections = yoloClassifier?.infer(scaled).orEmpty()

                if (detections.isNotEmpty()) {
                    val boxes = ArrayList<FloatArray>(detections.size)
                    val labels = ArrayList<String>(detections.size)
                    for (det in detections) {
                        val box = det.boundingBox
                        val w = scaled.width.toFloat()
                        val h = scaled.height.toFloat()
                        val x1 = (box.left / w).coerceIn(0f, 1f)
                        val y1 = (box.top / h).coerceIn(0f, 1f)
                        val x2 = (box.right / w).coerceIn(0f, 1f)
                        val y2 = (box.bottom / h).coerceIn(0f, 1f)
                        val cat = det.categories.firstOrNull()
                        val label = cat?.label ?: "Object"
                        val score = cat?.score ?: 0f

                        boxes.add(floatArrayOf(x1, y1, x2, y2, score, -1f))
                        labels.add(label)
                    }

                    overlayView.post {
                        overlayView.boxes = boxes
                        overlayView.labels = labels
                        overlayView.invalidate()
                    }

                    val top = labels.getOrNull(0) ?: "Object"
                    runOnUiThread {
                        detectedLabel.visibility = View.VISIBLE
                        detectedLabel.text = "YOLO: $top"
                    }
                    if (isSpeechOn) maybeSpeak(top)
                } else {
                    overlayView.post {
                        overlayView.boxes = emptyList()
                        overlayView.labels = emptyList()
                        overlayView.invalidate()
                    }
                    runOnUiThread { detectedLabel.text = "Detected: None" }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "analyzeImage error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ---------- TTS helpers ----------

    private fun maybeSpeak(text: String) {
        val now = System.currentTimeMillis()
        if (text != lastSpoken || now - lastSpokenTimeMs > SPEECH_COOLDOWN_MS) {
            lastSpoken = text
            lastSpokenTimeMs = now
            runOnUiThread {
                checkAndWarnVolume()
                if (isAutoRaiseEnabled()) ensureVolumeFloor(25, 50, true)
                nudgeIfBluetoothLow()
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.requestAudioFocus({}, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), text)
            }
        }
    }

    private fun checkAndWarnVolume() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (vol == 0) {
            val anchor: View = findViewById(R.id.previewView) ?: findViewById(android.R.id.content)
            Snackbar
                .make(anchor, "Media volume is muted — TTS will be silent.", Snackbar.LENGTH_LONG)
                .setAction("Sound settings") {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS))
                }
                .show()
        }
    }

    private fun setAutoRaise(enabled: Boolean) =
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_RAISE_VOLUME, enabled).apply()

    private fun isAutoRaiseEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_RAISE_VOLUME, true)

    /** If STREAM_MUSIC volume is below a floor, bump it to a target. Returns true if changed. */
    private fun ensureVolumeFloor(
        minThresholdPercent: Int = 25,
        raiseToPercent: Int = 50,
        showSystemUi: Boolean = true
    ): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val minIdx = try {
            val m = AudioManager::class.java.getMethod(
                "getStreamMinVolume",
                Int::class.javaPrimitiveType
            )
            m.invoke(am, AudioManager.STREAM_MUSIC) as Int
        } catch (_: Throwable) { 0 }

        val maxIdx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curIdx = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (maxIdx <= 0) return false

        val curPercent = (curIdx * 100f) / maxIdx
        if (curPercent >= minThresholdPercent.coerceIn(0, 100)) return false

        val targetIdx = (maxIdx * (raiseToPercent.coerceIn(0, 100) / 100f))
            .toInt()
            .coerceIn(minIdx.coerceAtLeast(1), maxIdx)
        if (targetIdx <= curIdx) return false

        val flags = if (showSystemUi) AudioManager.FLAG_SHOW_UI else 0
        am.setStreamVolume(AudioManager.STREAM_MUSIC, targetIdx, flags)
        return true
    }

    /** True if audio is currently routed to a Bluetooth output. */
    private fun isBluetoothOutputActive(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    /** If BT is active, show a small nudge to raise volume on the headset itself. */
    private fun nudgeIfBluetoothLow() {
        if (isBluetoothOutputActive()) {
            val anchor: View = findViewById(R.id.previewView) ?: findViewById(android.R.id.content)
            Snackbar
                .make(
                    anchor,
                    "Bluetooth output active. If speech is quiet, increase volume on the headset.",
                    Snackbar.LENGTH_LONG
                )
                .show()
        }
    }

    // ---------- Vision helpers ----------

    private fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 80): String {
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun callVisionObjectLocalizationBase64(
        base64Jpeg: String,
        apiKey: String
    ): List<VisionBox> {
        if (apiKey.isBlank()) {
            Log.w(TAG, "Vision key missing; skipping cloud call.")
            return emptyList()
        }

        val url = "https://vision.googleapis.com/v1/images:annotate?key=$apiKey"

        val features = JSONArray().apply {
            put(JSONObject().apply { put("type", "OBJECT_LOCALIZATION") })
        }
        val image = JSONObject().apply { put("content", base64Jpeg) }
        val requestObj = JSONObject().apply {
            put("image", image)
            put("features", features)
        }
        val root = JSONObject().apply {
            put("requests", JSONArray().put(requestObj))
        }

        val req = Request.Builder()
            .url(url)
            .post(root.toString().toRequestBody(JSON))
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                val bodyText = resp.body?.string()
                if (!resp.isSuccessful || bodyText.isNullOrEmpty()) {
                    Log.w(TAG, "Vision API failed: ${resp.code} ${resp.message}")
                    emptyList()
                } else {
                    parseVisionResponse(bodyText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vision call error: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseVisionResponse(jsonText: String): List<VisionBox> {
        val out = mutableListOf<VisionBox>()
        try {
            val root = JSONObject(jsonText)
            val responses = root.optJSONArray("responses") ?: return emptyList()
            if (responses.length() == 0) return emptyList()
            val r0 = responses.getJSONObject(0)
            val anns = r0.optJSONArray("localizedObjectAnnotations") ?: return emptyList()

            for (i in 0 until anns.length()) {
                val a = anns.getJSONObject(i)
                val name = a.optString("name", "Unknown")
                val score = a.optDouble("score", 0.0).toFloat()

                val poly = a.getJSONObject("boundingPoly").getJSONArray("normalizedVertices")
                var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
                for (j in 0 until poly.length()) {
                    val v = poly.getJSONObject(j)
                    val x = v.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f)
                    val y = v.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f)
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
                out.add(VisionBox(minX, minY, maxX, maxY, score, name))
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseVisionResponse error: ${e.message}", e)
        }
        return out
    }
}

// ---- ImageProxy -> Bitmap helper ----
fun ImageProxy.toBitmap(): Bitmap? {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = android.graphics.YuvImage(
        nv21,
        android.graphics.ImageFormat.NV21,
        width,
        height,
        null
    )
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}
