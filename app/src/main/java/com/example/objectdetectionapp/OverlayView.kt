package com.example.objectdetectionapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // boxes: [x1_norm, y1_norm, x2_norm, y2_norm, confidence, classIndex]
    var boxes: List<FloatArray> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 36f
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()

        for (box in boxes) {
            if (box.size < 6) continue
            val x1 = box[0] * vw
            val y1 = box[1] * vh
            val x2 = box[2] * vw
            val y2 = box[3] * vh
            val conf = box[4]
            val cls = box[5].toInt()
            val name = if (cls in MainActivity.COCO_CLASSES.indices) MainActivity.COCO_CLASSES[cls] else "Unknown"

            // Draw rectangle and label
            canvas.drawRect(x1, y1, x2, y2, boxPaint)
            val label = "$name ${"%.2f".format(conf)}"
            // draw text above box if possible
            val textY = (y1 - 8f).coerceAtLeast(36f)
            canvas.drawText(label, x1, textY, textPaint)
        }
    }
}
