package com.aspirelending.jobcards.core

import android.graphics.*

object Quality {
    /** Variance of the Laplacian on a downscaled grayscale copy; low = blurry. */
    fun sharpness(path: String): Float {
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        val b = BitmapFactory.decodeFile(path, o) ?: return 999f
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h); b.recycle()
        val g = IntArray(w * h) { (Color.red(px[it]) * 3 + Color.green(px[it]) * 6 + Color.blue(px[it])) / 10 }
        var n = 0; var s = 0.0; var s2 = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x; val l = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
            s += l; s2 += l * l; n++
        }
        return if (n == 0) 999f else (s2 / n - (s / n) * (s / n)).toFloat()
    }
    const val BLUR_THRESHOLD = 60f

    /** Contrast-stretched grayscale copy: a second opinion for OCR (the saved JPEG is untouched). */
    fun recognitionVariant(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
        var ss = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / ss > 2400) ss *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = ss }) ?: return null
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = 1.7f; val t = (-0.5f * c + 0.5f) * 255f
        val cm = ColorMatrix().apply { setSaturation(0f); postConcat(ColorMatrix(floatArrayOf(c,0f,0f,0f,t, 0f,c,0f,0f,t, 0f,0f,c,0f,t, 0f,0f,0f,1f,0f))) }
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        src.recycle(); return out
    }
}
