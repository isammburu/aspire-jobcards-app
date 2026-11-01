package com.aspirelending.jobcards.core

import android.graphics.*
import android.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

/**
 * Our own crop, perspective correction, rotation and clean-up for photos that did not come through Google's scanner
 * (shared from WhatsApp / Gallery). Works offline, in plain Android graphics, and keeps colour and thin pen strokes:
 * the paper is levelled to a natural off-white rather than thresholded to black and white.
 * Every step is guarded: if anything fails the caller keeps the unmodified photo.
 */
object DocumentCleaner {
    class Output(val cropped: Boolean, val rotation: Int)

    private const val MAX_SIDE = 2600f
    private const val DECODE_SIDE = 3000
    private const val PAPER_LEVEL = 244f     // paper ends up light grey-white, not blown out
    private const val JPEG_QUALITY = 92

    /** Where the untouched photo lives for a saved/processed card (".jpeg" -> ".orig.jpg"). Absent for scanner captures. */
    fun originalFor(processedPath: String) = File(processedPath.removeSuffix(".jpeg") + ".orig.jpg")

    suspend fun clean(src: File, dst: File): Output? {
        var bm = decode(src) ?: return null
        try {
            var cropped = false
            val quad = withContext(Dispatchers.Default) { detectQuad(bm) }
            if (quad != null) {
                val warped = withContext(Dispatchers.Default) { warp(bm, quad) }
                if (warped != null) { bm.recycle(); bm = warped; cropped = true }
            }
            val rot = orientation(bm)
            if (rot != 0) { val r = rotate(bm, rot); if (r !== bm) bm.recycle(); bm = r }
            val norm = withContext(Dispatchers.Default) { normalize(bm) }
            bm.recycle(); bm = norm
            val ok = withContext(Dispatchers.IO) { dst.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } }
            return if (ok && dst.length() > 0) Output(cropped, rot) else { dst.delete(); null }
        } catch (e: CancellationException) { dst.delete(); throw e
        } catch (e: Throwable) { dst.delete(); return null
        } finally { bm.recycle() }
    }

    /** Fallback / "use original": the photo with its camera rotation applied, as a JPEG, otherwise unchanged. */
    suspend fun exportOriginal(src: File, dst: File): Boolean = withContext(Dispatchers.IO) {
        val bm = decode(src) ?: return@withContext false
        try { dst.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } } finally { bm.recycle() }
    }

    /** For on-screen viewing: downscaled, with the camera's EXIF rotation applied (BitmapFactory ignores it). */
    fun loadForDisplay(path: String, maxSide: Int): Bitmap? = runCatching { decode(File(path), maxSide) }.getOrNull()

    private fun decode(f: File, maxSide: Int = DECODE_SIDE): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        var ss = 1; while (max(b.outWidth, b.outHeight) / ss > maxSide) ss *= 2
        val bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss }) ?: return null
        val m = exifMatrix(f) ?: return bm
        val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        if (r !== bm) bm.recycle(); return r
    }

    private fun exifMatrix(f: File): Matrix? {
        val o = runCatching { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return null
        }
        return m
    }

    private class Grid(val w: Int, val h: Int, val v: FloatArray)

    /** Mean brightness of every cell x cell block (every second pixel sampled; fast and alias-free enough for this). */
    private fun blockMeans(bm: Bitmap, cell: Int): Grid {
        val w = bm.width; val h = bm.height
        val gw = (w + cell - 1) / cell; val gh = (h + cell - 1) / cell
        val sum = FloatArray(gw * gh); val cnt = IntArray(gw * gh)
        val rows = 64; val buf = IntArray(w * rows)
        var y0 = 0
        while (y0 < h) {
            val n = min(rows, h - y0); bm.getPixels(buf, 0, w, 0, y0, w, n)
            for (r in 0 until n step 2) {
                val gy = (y0 + r) / cell
                for (x in 0 until w step 2) {
                    val p = buf[r * w + x]
                    val l = ((((p shr 16) and 255) * 3) + (((p shr 8) and 255) * 6) + (p and 255)) / 10f
                    val gi = gy * gw + x / cell; sum[gi] += l; cnt[gi]++
                }
            }
            y0 += n
        }
        return Grid(gw, gh, FloatArray(sum.size) { if (cnt[it] > 0) sum[it] / cnt[it] else 128f })
    }

    /** Finds the card as the largest bright blob; null when it can't be trusted (then the whole photo is kept). */
    private fun detectQuad(bm: Bitmap): List<Pt>? {
        val cell = max(2, max(bm.width, bm.height) / 320)
        val g = blockMeans(bm, cell)
        val blur = Geometry.boxBlur(g.v, g.w, g.h, 1)   // blends pen strokes into the paper so they don't punch holes
        val thr = Geometry.otsu(blur)
        var mask = BooleanArray(blur.size) { blur[it] > thr }
        val frac = mask.count { it }.toFloat() / mask.size
        if (frac < 0.15f || frac > 0.97f) return null
        mask = Geometry.dilate(Geometry.erode(mask, g.w, g.h), g.w, g.h)   // opening: cuts thin bright leaks into the background
        val comp = Geometry.largestComponent(mask, g.w, g.h) ?: return null
        val q = Geometry.quadFromBlob(comp, g.w, g.h) ?: return null
        val pts = q.map { Pt((it.x * cell).coerceIn(0f, bm.width - 1f), (it.y * cell).coerceIn(0f, bm.height - 1f)) }
        val cx = pts.map { it.x }.average().toFloat(); val cy = pts.map { it.y }.average().toFloat()
        return pts.map { Pt(it.x + (cx - it.x) * 0.01f, it.y + (cy - it.y) * 0.01f) }   // 1% inset: no dark table edge in the result
    }

    private fun warp(src: Bitmap, q: List<Pt>): Bitmap? {
        fun d(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)
        var ow = max(d(q[0], q[1]), d(q[3], q[2])); var oh = max(d(q[0], q[3]), d(q[1], q[2]))
        if (ow < 200f || oh < 200f) return null
        val s = min(1f, MAX_SIDE / max(ow, oh)); ow *= s; oh *= s
        val w = ow.roundToInt(); val h = oh.roundToInt()
        val m = Matrix()
        val from = floatArrayOf(q[0].x, q[0].y, q[1].x, q[1].y, q[2].x, q[2].y, q[3].x, q[3].y)
        val to = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        if (!m.setPolyToPoly(from, 0, to, 0, 4)) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun rotate(bm: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return bm
        return Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    /** Reads the page at 0/90/180/270 and keeps the angle where the most text is recognised; stays at 0 unless clearly better. */
    private suspend fun orientation(bm: Bitmap): Int {
        val s = 1400f / max(bm.width, bm.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(bm, (bm.width * s).roundToInt().coerceAtLeast(1), (bm.height * s).roundToInt().coerceAtLeast(1), true) else bm
        val scores = FloatArray(4)
        try {
            for (k in 0..3) {
                val r = if (k == 0) small else rotate(small, k * 90)
                scores[k] = try { Ocr.readBitmap(r).sumOf { l -> (l.text.count { it.isLetterOrDigit() } * l.confidence).toDouble() }.toFloat() }
                    catch (e: CancellationException) { throw e } catch (e: Throwable) { 0f }
                if (r !== small) r.recycle()
            }
        } finally { if (small !== bm) small.recycle() }
        val best = scores.indices.maxByOrNull { scores[it] } ?: 0
        return if (best != 0 && scores[best] >= 20f && scores[best] > 1.25f * scores[0]) best * 90 else 0
    }

    /**
     * Levels uneven light and removes soft shadows: the paper brightness is estimated per block (text removed by a max
     * filter), then every pixel is scaled so that paper lands at a natural off-white. Colour and ink strokes are kept;
     * nothing is thresholded and no content is invented.
     */
    private fun normalize(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height; val cell = 16
        val g = blockMeans(src, cell)
        val bg = Geometry.boxBlur(Geometry.maxFilter(g.v, g.w, g.h, 2), g.w, g.h, 1)
        val x0 = IntArray(w); val x1 = IntArray(w); val fx = FloatArray(w)
        for (x in 0 until w) {
            val gx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (g.w - 1).toFloat()); val a = gx.toInt()
            x0[x] = a; x1[x] = min(a + 1, g.w - 1); fx[x] = gx - a
        }
        val lut = IntArray(256) { (255.0 * (it / 255.0).pow(1.12)).roundToInt().coerceIn(0, 255) }   // slightly firmer ink
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = 64; val buf = IntArray(w * rows)
        var y0 = 0
        while (y0 < h) {
            val n = min(rows, h - y0); src.getPixels(buf, 0, w, 0, y0, w, n)
            for (r in 0 until n) {
                val gy = ((y0 + r + 0.5f) / cell - 0.5f).coerceIn(0f, (g.h - 1).toFloat()); val ya = gy.toInt(); val yb = min(ya + 1, g.h - 1); val fy = gy - ya
                val ra = ya * g.w; val rb = yb * g.w
                for (x in 0 until w) {
                    val top = bg[ra + x0[x]] * (1 - fx[x]) + bg[ra + x1[x]] * fx[x]
                    val bot = bg[rb + x0[x]] * (1 - fx[x]) + bg[rb + x1[x]] * fx[x]
                    val b = top * (1 - fy) + bot * fy
                    val scale = (PAPER_LEVEL / max(b, 40f)).coerceAtMost(3f)
                    val p = buf[r * w + x]
                    val rr = lut[min(255, (((p shr 16) and 255) * scale).toInt())]
                    val gg = lut[min(255, (((p shr 8) and 255) * scale).toInt())]
                    val bb = lut[min(255, ((p and 255) * scale).toInt())]
                    buf[r * w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            out.setPixels(buf, 0, w, 0, y0, w, n); y0 += n
        }
        return out
    }
}
