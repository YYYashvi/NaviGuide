package com.example.objectdetectionapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
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
import com.google.android.material.switchmaterial.SwitchMaterial
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivityYOLO"
        private const val MODEL_INPUT_SIZE = 640f
        private const val CONFIDENCE_THRESHOLD = 0.3f
        private const val SPEECH_PERSISTENCE_FRAMES = 3
        private const val SPEECH_COOLDOWN_MS = 2000L

        val COCO_CLASSES = arrayOf(
            "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
            "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
            "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
            "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
            "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
            "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
            "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair",
            "couch","potted plant","bed","dining table","toilet","tv","laptop","mouse","remote",
            "keyboard","cell phone","microwave","oven","toaster","sink","refrigerator","book",
            "clock","vase","scissors","teddy bear","hair drier","toothbrush"
        )
    }

    private lateinit var cameraExecutor: ExecutorService
    private var tfliteInterpreter: Interpreter? = null
    private lateinit var overlayView: OverlayView

    // UI Elements (Material types)
    private lateinit var btnStartStop: MaterialButton
    private lateinit var switchSpeech: SwitchMaterial
    private lateinit var detectedLabel: TextView

    // TTS
    private var tts: TextToSpeech? = null
    private var lastSpoken: String? = null
    private var lastSpokenTimeMs: Long = 0L
    private var detectionCounters: MutableMap<String, Int> = HashMap()

    private var isCameraRunning = true
    private var isSpeechOn = true

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

        // UI wiring (must match XML widget types)
        btnStartStop = findViewById(R.id.btnStartStop)
        switchSpeech = findViewById(R.id.switchSpeech)
        detectedLabel = findViewById(R.id.detectedLabel)

        // initial states
        isSpeechOn = switchSpeech.isChecked
        btnStartStop.text = if (isCameraRunning) "Stop" else "Start"

        btnStartStop.setOnClickListener {
            isCameraRunning = !isCameraRunning
            btnStartStop.text = if (isCameraRunning) "Stop" else "Start"

            if (!isCameraRunning) {
                // Clear overlay when detection stops
                overlayView.boxes = emptyList()
                overlayView.invalidate()
            }
        }

        switchSpeech.setOnCheckedChangeListener { _, isChecked ->
            isSpeechOn = isChecked
        }

        // Camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Load model
        try {
            tfliteInterpreter = Interpreter(loadModelFile("yolov8.tflite"))
            Log.d(TAG, "YOLO model loaded successfully")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load YOLO model: ${e.message}", e)
        }

        // Init TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                Log.d(TAG, "TTS initialized")
            } else {
                Log.w(TAG, "TTS initialization failed: $status")
            }
        }
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val channel: FileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

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

    private fun analyzeImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()?.let {
                Bitmap.createScaledBitmap(it, MODEL_INPUT_SIZE.toInt(), MODEL_INPUT_SIZE.toInt(), true)
            }
            if (bitmap == null) {
                imageProxy.close()
                return
            }

            val inputBuffer = ByteBuffer.allocateDirect(
                1 * MODEL_INPUT_SIZE.toInt() * MODEL_INPUT_SIZE.toInt() * 3 * 4
            ).apply { order(ByteOrder.nativeOrder()) }
            bitmapToFloatBuffer(bitmap, inputBuffer)
            inputBuffer.rewind()

            val outputBuffer = Array(1) { Array(84) { FloatArray(8400) } }
            tfliteInterpreter?.run(inputBuffer, outputBuffer)

            val detectedBoxes = parseYoloOutput(outputBuffer) // normalized coords (0..1)
            overlayView.post { overlayView.boxes = detectedBoxes }

            // Update text label
            val topLabel = detectedBoxes.maxByOrNull { it[4] }?.let {
                val idx = it[5].toInt()
                if (idx in COCO_CLASSES.indices) COCO_CLASSES[idx] else "Unknown"
            } ?: "None"
            runOnUiThread { detectedLabel.text = "Detected object: $topLabel" }

            // TTS if enabled
            if (isSpeechOn) handleTtsPersistence(detectedBoxes)

        } catch (e: Exception) {
            Log.e(TAG, "analyzeImage error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun handleTtsPersistence(detectedBoxes: List<FloatArray>) {
        val presentLabels = detectedBoxes.mapNotNull {
            val idx = it[5].toInt()
            if (idx in COCO_CLASSES.indices) COCO_CLASSES[idx] else null
        }.toSet()

        for (label in presentLabels) detectionCounters[label] = (detectionCounters[label] ?: 0) + 1
        val labelsToDecay = detectionCounters.keys.toList()
        for (label in labelsToDecay) if (!presentLabels.contains(label)) {
            val count = (detectionCounters[label] ?: 0) - 1
            if (count <= 0) detectionCounters.remove(label) else detectionCounters[label] = count
        }

        val stableCandidates = detectionCounters.filter { it.value >= SPEECH_PERSISTENCE_FRAMES }.keys
        if (stableCandidates.isNotEmpty()) {
            val top = detectedBoxes
                .filter { it[5].toInt() in COCO_CLASSES.indices }
                .filter { COCO_CLASSES[it[5].toInt()] in stableCandidates }
                .maxByOrNull { it[4] }

            top?.let { box ->
                val className = COCO_CLASSES.getOrElse(box[5].toInt()) { "Unknown" }
                val now = System.currentTimeMillis()
                if (className != lastSpoken || now - lastSpokenTimeMs > SPEECH_COOLDOWN_MS) {
                    lastSpoken = className
                    lastSpokenTimeMs = now
                    runOnUiThread { tts?.speak(className, TextToSpeech.QUEUE_FLUSH, null, className) }
                }
            }
        }
    }

    private fun parseYoloOutput(output: Array<Array<FloatArray>>): List<FloatArray> {
        val boxes = mutableListOf<FloatArray>()
        val preds = output[0]
        val numClasses = 80
        val numCandidates = 8400
        val confidenceThreshold = CONFIDENCE_THRESHOLD
        val nmsThreshold = 0.45f

        for (i in 0 until numCandidates) {
            val x = preds[0][i]; val y = preds[1][i]; val w = preds[2][i]; val h = preds[3][i]

            var maxClass = -1; var maxScore = 0f
            for (c in 0 until numClasses) {
                val score = preds[4 + c][i]
                if (score > maxScore) { maxScore = score; maxClass = c }
            }
            if (maxScore <= confidenceThreshold) continue

            val needsNormalize = (x > 1.5f || y > 1.5f || w > 1.5f || h > 1.5f)
            var cx = x; var cy = y; var ww = w; var hh = h
            if (needsNormalize) { cx /= MODEL_INPUT_SIZE; cy /= MODEL_INPUT_SIZE; ww /= MODEL_INPUT_SIZE; hh /= MODEL_INPUT_SIZE }
            val x1 = (cx - ww / 2f).coerceIn(0f, 1f)
            val y1 = (cy - hh / 2f).coerceIn(0f, 1f)
            val x2 = (cx + ww / 2f).coerceIn(0f, 1f)
            val y2 = (cy + hh / 2f).coerceIn(0f, 1f)
            boxes.add(floatArrayOf(x1, y1, x2, y2, maxScore, maxClass.toFloat()))
        }
        return nonMaxSuppression(boxes, nmsThreshold)
    }

    private fun nonMaxSuppression(boxes: List<FloatArray>, nmsThreshold: Float): List<FloatArray> {
        val finalBoxes = mutableListOf<FloatArray>()
        val sortedBoxes = boxes.sortedByDescending { it[4] }
        val picked = BooleanArray(sortedBoxes.size)
        for (i in sortedBoxes.indices) {
            if (picked[i]) continue
            val A = sortedBoxes[i]; finalBoxes.add(A)
            for (j in i + 1 until sortedBoxes.size) {
                if (picked[j]) continue
                val B = sortedBoxes[j]
                if (iou(A, B) > nmsThreshold) picked[j] = true
            }
        }
        return finalBoxes
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val x1 = maxOf(a[0], b[0]); val y1 = maxOf(a[1], b[1])
        val x2 = minOf(a[2], b[2]); val y2 = minOf(a[3], b[3])
        val interW = maxOf(0f, x2 - x1); val interH = maxOf(0f, y2 - y1)
        val inter = interW * interH
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        return inter / (areaA + areaB - inter + 1e-6f)
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, byteBuffer: ByteBuffer) {
        val size = MODEL_INPUT_SIZE.toInt()
        val intValues = IntArray(size * size)
        bitmap.getPixels(intValues, 0, size, 0, 0, size, size)
        var pixel = 0
        for (i in 0 until size) for (j in 0 until size) {
            val value = intValues[pixel++]
            byteBuffer.putFloat(((value shr 16 and 0xFF) / 255.0f))
            byteBuffer.putFloat(((value shr 8 and 0xFF) / 255.0f))
            byteBuffer.putFloat(((value and 0xFF) / 255.0f))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tfliteInterpreter?.close()
        tts?.stop()
        tts?.shutdown()
    }
}

// Extension to convert ImageProxy to Bitmap
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
    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}
