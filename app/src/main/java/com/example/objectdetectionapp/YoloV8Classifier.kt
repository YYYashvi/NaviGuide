package com.example.objectdetectionapp
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector


class YoloV8Classifier(
        context: Context,
        private val modelPath: String = "yolov8.tflite" // ensure this file is in app/src/main/assets/
) {
    private val TAG = "YoloV8Classifier"
    private val detector: ObjectDetector

    init {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(6)        // tune as needed
                .setScoreThreshold(0.25f) // tune as needed
                .build()

        detector = try {
            ObjectDetector.createFromFileAndOptions(context, modelPath, options)
        } catch (e: Exception) {
            throw RuntimeException("Failed to load model '$modelPath': ${e.message}", e)
        }
    }

    /**
     * Run inference on a Bitmap and return the list of Task Vision Detection objects.
     * MainActivity expects List<Detection> so we return the raw detections here.
     */
    fun infer(bitmap: Bitmap): List<Detection> {
        return try {
            // Convert Bitmap -> TensorImage for the Task API
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val results: List<Detection> = detector.detect(tensorImage)
            // optional: Log basic info
            Log.d(TAG, "Inference returned ${results.size} detections")
            results
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            emptyList()
        }
    }

    fun close() {
        try {
            detector.close()
        } catch (_: Exception) { /* ignore */ }
    }
}