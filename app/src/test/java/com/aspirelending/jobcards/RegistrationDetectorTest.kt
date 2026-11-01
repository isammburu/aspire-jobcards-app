package com.aspirelending.jobcards

import android.graphics.Rect
import com.aspirelending.jobcards.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationDetectorTest {
    private fun top(text: String, conf: Float = 0.9f) =
        RegistrationDetector.detect(listOf(OcrLine(text, Rect(), conf))).firstOrNull()?.value

    /** Only the common civilian Kenyan plates are supported. */
    private val plates = listOf(
        "KBW 890M",
        "KDA 123A",
        "KEA 123A",
        "KMDA 123A",
        "KTWA 123A",
        "KHMA 123A",
        "Z 12345",
        "KAB 123",
        "ZF 1234"
    )

    @Test fun plainPlatesAreRecognised() = plates.forEach { assertEquals(it, top(it)) }
    @Test fun labelledPlatesAreRecognised() = plates.forEach { assertEquals(it, top("Registration: $it")) }
    @Test fun labelOnlyFormats() { assertEquals("ZF 1234", top("Registration: ZF 1234")); assertEquals("KAB 123", top("Reg No: KAB 123")) }

    @Test fun ocrConfusionIsFixedByPosition() {
        assertEquals("KBW 890M", top("K8W 890M")); assertEquals("KBW 890M", top("KBW 89OM"))
        assertEquals("KDA 123A", top("KDA I23A"))
    }

    @Test fun longerPlateBeatsContainedPlate() { assertEquals("KMDA 123A", top("KMDA 123A")); assertEquals("Z 12345", top("Z 12345")) }
    @Test fun plainWordsAreNotPlates() { assertEquals(null, top("Customer name John Kamau")); assertEquals(null, top("Date 12/10/2026")) }
    @Test fun unknownSeriesNextToLabelIsOnlyLowConfidence() {
        val c = RegistrationDetector.detect(listOf(OcrLine("Registration: XYZ 12", Rect(), 0.9f))).first()
        assertEquals(Tier.LOW, RegistrationDetector.tier(c.score))
    }
    @Test fun agreementBetweenPassesRaisesScore() {
        val a = RegistrationDetector.detect(listOf(OcrLine("Registration: KBW 890M", Rect(), 0.6f)))
        val m = RegistrationDetector.merge(listOf(a, a))
        assertTrue(m.first().score > a.first().score)
    }

    @Test fun tiers() {
        assertEquals(Tier.HIGH, RegistrationDetector.tier(0.9f)); assertEquals(Tier.MEDIUM, RegistrationDetector.tier(0.7f)); assertEquals(Tier.LOW, RegistrationDetector.tier(0.4f))
    }

    @Test fun filenames() {
        assertEquals("KBW 890M.jpeg", RegistrationDetector.sanitizeFilename("  kbw   890m "))
        assertEquals("KBW 890M.jpeg", RegistrationDetector.sanitizeFilename("KBW/890M".replace("/", " ")))
        assertEquals("Z 12345.jpeg", RegistrationDetector.sanitizeFilename("Z 12345"))
    }
}
