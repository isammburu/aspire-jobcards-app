package com.aspirelending.jobcards

import com.aspirelending.jobcards.core.Geometry
import com.aspirelending.jobcards.core.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeometryTest {
    private val w = 80; private val h = 60

    /** Brightness grid with a bright (paper) polygon on a dark table, polygon given by a point-in-quad test. */
    private fun grid(inside: (Int, Int) -> Boolean) = FloatArray(w * h) { if (inside(it % w, it / w)) 210f else 45f }

    private fun detect(v: FloatArray): List<Pt>? {
        val blur = Geometry.boxBlur(v, w, h, 1); val thr = Geometry.otsu(blur)
        var mask = BooleanArray(blur.size) { blur[it] > thr }
        mask = Geometry.dilate(Geometry.erode(mask, w, h), w, h)
        return Geometry.largestComponent(mask, w, h)?.let { Geometry.quadFromBlob(it, w, h) }
    }
    private fun near(p: Pt, x: Float, y: Float, tol: Float = 3f) = abs(p.x - x) <= tol && abs(p.y - y) <= tol

    @Test fun otsuSeparatesTableFromPaper() {
        val t = Geometry.otsu(FloatArray(100) { if (it < 50) 40f else 200f }); assertTrue(t in 40f..200f)
    }

    @Test fun findsAFlatCardOnADarkTable() {
        val q = detect(grid { x, y -> x in 15..65 && y in 8..52 })!!
        assertTrue(near(q[0], 15f, 8f)); assertTrue(near(q[1], 66f, 8f)); assertTrue(near(q[2], 66f, 53f)); assertTrue(near(q[3], 15f, 53f))
    }

    @Test fun findsATiltedCardAndKeepsCornerOrder() {
        // card turned about 20 degrees: corners roughly (22,6) (68,18) (56,54) (10,42)
        fun side(ax: Float, ay: Float, bx: Float, by: Float, x: Int, y: Int) = (bx - ax) * (y - ay) - (by - ay) * (x - ax) >= 0
        val q = detect(grid { x, y -> side(22f, 6f, 68f, 18f, x, y) && side(68f, 18f, 56f, 54f, x, y) && side(56f, 54f, 10f, 42f, x, y) && side(10f, 42f, 22f, 6f, x, y) })!!
        assertEquals(4, q.size); assertTrue(Geometry.isConvex(q))
        assertTrue(near(q[0], 22f, 6f, 4f)); assertTrue(near(q[1], 68f, 18f, 4f)); assertTrue(near(q[2], 56f, 54f, 4f)); assertTrue(near(q[3], 10f, 42f, 4f))
    }

    @Test fun handwritingInsideThePaperDoesNotChangeTheCorners() {
        val v = grid { x, y -> x in 15..65 && y in 8..52 }
        for (x in 25..55 step 3) for (y in 15..45 step 4) v[y * w + x] = 40f   // ink specks
        val q = detect(v)!!; assertTrue(near(q[0], 15f, 8f)); assertTrue(near(q[2], 66f, 53f))
    }

    @Test fun anAlreadyCroppedPhotoIsLeftAlone() { assertNull(detect(grid { _, _ -> true }.also { it[0] = 44f })) ; assertNull(detect(grid { x, y -> x in 0..79 && y in 0..58 })) }
    @Test fun aTinyBrightPatchIsNotACard() = assertNull(detect(grid { x, y -> x in 30..40 && y in 20..28 }))
    @Test fun anLShapedBlobIsNotACard() = assertNull(detect(grid { x, y -> (x in 10..30 && y in 5..55) || (x in 10..70 && y in 40..55) }))
    @Test fun emptyMaskGivesNothing() = assertNull(Geometry.largestComponent(BooleanArray(w * h), w, h))

    @Test fun areaAndConvexity() {
        val sq = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f)); assertEquals(100f, Geometry.area(sq), 0.01f); assertTrue(Geometry.isConvex(sq))
        assertTrue(!Geometry.isConvex(listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(2f, 2f), Pt(0f, 10f))))
    }

    @Test fun maxFilterRemovesDarkSpecksButKeepsLevel() {
        val v = FloatArray(w * h) { 200f }; v[30 * w + 40] = 20f
        assertEquals(200f, Geometry.maxFilter(v, w, h, 2)[30 * w + 40], 0.01f)
    }
}
