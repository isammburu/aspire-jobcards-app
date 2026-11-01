package com.aspirelending.jobcards

import android.graphics.Rect
import com.aspirelending.jobcards.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationDetectorTest {
    private fun top(text: String, conf: Float = 0.9f) =
        RegistrationDetector.detect(listOf(OcrLine(text, Rect(), conf))).firstOrNull()?.value

    /** Plates from the 2026 guide; each must be found alone on a line and after a "Registration:" label. */
    private val plates = listOf(
        "KBW 890M", "KDA 123A", "KEA 123A", "KMDA 123A", "KMCA 123A", "KTWA 123A", "KHMA 123A",
        "KTC 12345", "KHM 12345", "KTW 12345", "GK A123A", "GK 123A", "Z 12345",
        "KG 1234", "KD 1234", "KT 1234", "KC 1234",
        "47 CG", "GVN 047", "SNA 1", "SSN 001", "CJ 1", "00 KCG 00",
        "5 CD 18 K", "029 CD 123 AK", "029 UN 123 AK", "UN 001", "KX 05 A", "KX 05 B")

    @Test fun plainPlatesAreRecognised() = plates.forEach { assertEquals(it, top(it)) }
    @Test fun labelledPlatesAreRecognised() = plates.forEach { assertEquals(it, top("Registration: $it")) }
    @Test fun labelOnlyFormats() { assertEquals("ZF 1234", top("Registration: ZF 1234")); assertEquals("KAB 123", top("Reg No: KAB 123")) }

    @Test fun ocrConfusionIsFixedByPosition() {
        assertEquals("KBW 890M", top("K8W 890M")); assertEquals("KBW 890M", top("KBW 89OM"))
        assertEquals("KDA 123A", top("KDA I23A"))
    }
    @Test fun dealerAndOrdinaryKdAreDifferent() { assertEquals("KD 1234", top("KD 1234")); assertEquals("KDA 123A", top("KDA 123A")) }
    @Test fun longerPlateBeatsContainedPlate() { assertEquals("KMDA 123A", top("KMDA 123A")); assertEquals("KTW 12345", top("KTW 12345")) }
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
        assertEquals("5 CD 18 K.jpeg", RegistrationDetector.sanitizeFilename("5 CD 18 K"))
    }
}
