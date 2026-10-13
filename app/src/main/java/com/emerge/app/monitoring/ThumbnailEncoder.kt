package com.emerge.app.monitoring

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import java.io.ByteArrayOutputStream

internal data class EncodedThumbnail(val bytes: ByteArray, val width: Int, val height: Int, val kind: String)

/** A separate local image product. These bytes are never a cloud request payload. */
internal object ThumbnailEncoder {
    const val MAX_BYTES = 96 * 1024
    fun encode(source: Bitmap, region: Rect?, kind: String): EncodedThumbnail? {
        val bitmap = MonitorFrameEncoder.crop(source, region, 480)
        try {
            // Skip uniformly black/blank transitions. This is not a learned blur/occlusion classifier.
            val luma = mutableListOf<Int>()
            for(y in 0 until bitmap.height step maxOf(1,bitmap.height/24))
                for(x in 0 until bitmap.width step maxOf(1,bitmap.width/24)) {
                    val pixel=bitmap.getPixel(x,y)
                    luma += (Color.red(pixel)*3+Color.green(pixel)*6+Color.blue(pixel))/10
                }
            val sorted=luma.sorted()
            if(sorted[sorted.size*9/10]-sorted[sorted.size/10] < 18) return null
            if (kind == "video_frame") {
                // A small edge check rejects very soft transition frames without decoding full-size images in UI.
                fun luminance(pixel: Int) = (Color.red(pixel)*3+Color.green(pixel)*6+Color.blue(pixel))/10
                val edges = mutableListOf<Int>()
                for (y in 0 until bitmap.height-1 step maxOf(1,bitmap.height/48))
                    for (x in 0 until bitmap.width-1 step maxOf(1,bitmap.width/48)) {
                        val center=luminance(bitmap.getPixel(x,y))
                        edges += maxOf(kotlin.math.abs(center-luminance(bitmap.getPixel(x+1,y))),
                            kotlin.math.abs(center-luminance(bitmap.getPixel(x,y+1))))
                    }
                if (edges.isEmpty() || edges.sorted()[edges.size*97/100] < 8) return null
            }
            val stream=ByteArrayOutputStream()
            for(quality in intArrayOf(75,60,45)) {
                stream.reset()
                if(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,stream) && stream.size() <= MAX_BYTES)
                    return EncodedThumbnail(stream.toByteArray(),bitmap.width,bitmap.height,kind)
            }
            return null
        } finally { bitmap.recycle() }
    }
}
