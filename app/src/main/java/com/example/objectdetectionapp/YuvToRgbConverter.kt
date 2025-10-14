package com.example.objectdetectionapp

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import androidx.camera.core.ImageProxy

class YuvToRgbConverter(context: Context) {

    private val rs: RenderScript = RenderScript.create(context)
    private val yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    fun yuvToRgb(image: ImageProxy): Bitmap? {
        val yuvBytes = ByteArray(image.planes.sumOf { it.buffer.remaining() })
        var offset = 0
        for (plane in image.planes) {
            val buffer = plane.buffer
            val remaining = buffer.remaining()
            buffer.get(yuvBytes, offset, remaining)
            offset += remaining
        }

        val outBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val inAllocation = Allocation.createSized(rs, Element.U8(rs), yuvBytes.size)
        val outAllocation = Allocation.createFromBitmap(rs, outBitmap)

        inAllocation.copyFrom(yuvBytes)
        yuvToRgbIntrinsic.setInput(inAllocation)
        yuvToRgbIntrinsic.forEach(outAllocation)
        outAllocation.copyTo(outBitmap)

        return outBitmap
    }
}
