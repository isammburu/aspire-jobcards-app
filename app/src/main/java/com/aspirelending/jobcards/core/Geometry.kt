package com.aspirelending.jobcards.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Float, val y: Float)

/** Plain-Kotlin helpers (no Android types) used by [DocumentCleaner], so they can be unit tested on the JVM. */
object Geometry {
    /** Otsu threshold for values in 0..255: splits "dark" (table, ink) from "bright" (paper). */
    fun otsu(values: FloatArray): Float {
        val hist = IntArray(256); values.forEach { hist[it.toInt().coerceIn(0, 255)]++ }
        val total = values.size.toDouble(); var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var wB = 0.0; var sB = 0.0; var best = -1.0; var thr = 127
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0.0) continue
            val wF = total - wB; if (wF <= 0.0) break
            sB += t * hist[t].toDouble()
            val mB = sB / wB; val mF = (sum - sB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        return thr + 0.5f
    }

    fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return a.copyOf()
        val tmp = FloatArray(a.size); val out = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (k in max(0, x - r)..min(w - 1, x + r)) { s += a[y * w + k]; n++ }
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (k in max(0, y - r)..min(h - 1, y + r)) { s += tmp[k * w + x]; n++ }
            out[y * w + x] = s / n
        }
        return out
    }

    /** Max filter with the given radius: wipes out text-sized dark specks so what is left is the paper brightness. */
    fun maxFilter(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(a.size); val out = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = Float.NEGATIVE_INFINITY
            for (k in max(0, x - r)..min(w - 1, x + r)) m = max(m, a[y * w + k])
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = Float.NEGATIVE_INFINITY
            for (k in max(0, y - r)..min(h - 1, y + r)) m = max(m, tmp[k * w + x])
            out[y * w + x] = m
        }
        return out
    }

    /** 3x3 erosion; cells outside the grid count as set, so paper touching the photo edge isn't eaten. */
    fun erode(m: BooleanArray, w: Int, h: Int) = morph(m, w, h, true)
    /** 3x3 dilation; cells outside the grid count as empty. */
    fun dilate(m: BooleanArray, w: Int, h: Int) = morph(m, w, h, false)
    private fun morph(m: BooleanArray, w: Int, h: Int, erode: Boolean): BooleanArray {
        val o = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var all = true; var any = false
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                val v = if (xx in 0 until w && yy in 0 until h) m[yy * w + xx] else erode
                if (v) any = true else all = false
            }
            o[y * w + x] = if (erode) all else any
        }
        return o
    }

    /** Largest 4-connected group of set cells, or null if the mask is empty. */
    fun largestComponent(mask: BooleanArray, w: Int, h: Int): BooleanArray? {
        val seen = BooleanArray(mask.size); val queue = IntArray(mask.size)
        var bestStart = -1; var bestSize = 0
        for (s in mask.indices) {
            if (!mask[s] || seen[s]) continue
            var head = 0; var tail = 0; queue[tail++] = s; seen[s] = true
            while (head < tail) {
                val i = queue[head++]; val x = i % w; val y = i / w
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; queue[tail++] = i - w }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; queue[tail++] = i + w }
            }
            if (tail > bestSize) { bestSize = tail; bestStart = s }
        }
        if (bestStart < 0) return null
        val out = BooleanArray(mask.size); val q = IntArray(mask.size)
        var head = 0; var tail = 0; q[tail++] = bestStart; out[bestStart] = true
        while (head < tail) {
            val i = q[head++]; val x = i % w; val y = i / w
            if (x > 0 && mask[i - 1] && !out[i - 1]) { out[i - 1] = true; q[tail++] = i - 1 }
            if (x < w - 1 && mask[i + 1] && !out[i + 1]) { out[i + 1] = true; q[tail++] = i + 1 }
            if (y > 0 && mask[i - w] && !out[i - w]) { out[i - w] = true; q[tail++] = i - w }
            if (y < h - 1 && mask[i + w] && !out[i + w]) { out[i + w] = true; q[tail++] = i + w }
        }
        return out
    }

    fun area(p: List<Pt>): Float {
        var s = 0f
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2f
    }

    fun isConvex(p: List<Pt>): Boolean {
        var sign = 0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]; val c = p[(i + 2) % p.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) { if (sign == 0) sign = s else if (sign != s) return false }
        }
        return true
    }

    /**
     * Four corners (top-left, top-right, bottom-right, bottom-left, in grid units) of the paper blob, or null when the blob
     * isn't a believable card: too small, so big that it is just the whole photo (already cropped), not rectangular enough,
     * or not convex.
     */
    fun quadFromBlob(comp: BooleanArray, w: Int, h: Int): List<Pt>? {
        var tl = -1; var br = -1; var tr = -1; var bl = -1; var count = 0
        var minS = Int.MAX_VALUE; var maxS = Int.MIN_VALUE; var minD = Int.MAX_VALUE; var maxD = Int.MIN_VALUE
        for (i in comp.indices) {
            if (!comp[i]) continue
            count++; val x = i % w; val y = i / w
            if (x + y < minS) { minS = x + y; tl = i }
            if (x + y > maxS) { maxS = x + y; br = i }
            if (x - y > maxD) { maxD = x - y; tr = i }
            if (x - y < minD) { minD = x - y; bl = i }
        }
        if (count == 0) return null
        val q = listOf(
            Pt((tl % w).toFloat(), (tl / w).toFloat()), Pt((tr % w + 1).toFloat(), (tr / w).toFloat()),
            Pt((br % w + 1).toFloat(), (br / w + 1).toFloat()), Pt((bl % w).toFloat(), (bl / w + 1).toFloat()))
        val a = area(q); val whole = (w * h).toFloat()
        if (a < 0.15f * whole || a > 0.96f * whole) return null
        if (count / a < 0.9f) return null
        if (!isConvex(q)) return null
        return q
    }
}
