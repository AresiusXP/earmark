package xyz.aresius.audiobooks.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.aresius.audiobooks.testutil.TestCodes

class QrCodeTest {
    /** Renders like the app does (black on white, 4-module quiet zone) and decodes with ZXing's reader. */
    private fun decode(qr: QrCode, scale: Int = 5): String {
        val quiet = 4
        val dim = (qr.size + 2 * quiet) * scale
        val pixels = IntArray(dim * dim) { 0xFFFFFF }
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (!qr.isDark(x, y)) continue
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + quiet) * scale + dy) * dim + (x + quiet) * scale + dx] = 0x000000
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(dim, dim, pixels)))
        // ZXing's locator occasionally misses a large, pixel-perfect synthetic symbol (its own encoder fails the same way about 1 time in
        // 100 at versions 8-10 in this harness); the pure-barcode reader reads the module grid exactly, so it is the fallback.
        return runCatching { QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text }
            .getOrElse { QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text }
    }

    @Test fun aLinkCodeFitsVersionTwoAndDecodes() {
        val qr = QrCode.encode(TestCodes.LINK)
        assertEquals(2, qr.version)
        assertEquals(25, qr.size)
        assertEquals(TestCodes.LINK, decode(qr))
    }

    @Test fun everyLengthFromOneByteToTheMaximumDecodesAndCoversVersionsOneToTen() {
        val random = Random(42)
        val alphabet = "ABCDEFGHJKMNPQRSTVWXYZ0123456789 -_./:abcxyz"
        val versions = HashSet<Int>()
        val failures = ArrayList<String>()
        for (length in 1..QrCode.MAX_BYTES) {
            val text = String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
            val qr = QrCode.encode(text)
            versions.add(qr.version)
            if (runCatching { decode(qr) }.getOrNull() != text) failures.add("length $length (version ${qr.version})")
        }
        assertEquals(emptyList(), failures)
        assertEquals((1..10).toSet(), versions)
    }

    @Test fun utf8TextDecodes() {
        val text = "Audiobooks – luisterboek: Zoë 📚"
        assertEquals(text, decode(QrCode.encode(text)))
    }

    @Test fun thePlainCodeAsTheDialogEncodesItDecodesToTheCode() {
        // what CodeDialog puts in the QR: the 26 normalised characters
        listOf(TestCodes.INVITE, TestCodes.LINK, TestCodes.OTHER, "0".repeat(26), "Z".repeat(26)).forEach {
            assertEquals(it, decode(QrCode.encode(it)))
        }
    }

    @Test fun finderPatternsAndTimingAreWhereTheStandardPutsThem() {
        val qr = QrCode.encode("A")
        assertEquals(21, qr.size)
        for (i in 0 until 7) {
            assertTrue(qr.isDark(i, 0) && qr.isDark(0, i) && qr.isDark(qr.size - 1 - i, 0) && qr.isDark(0, qr.size - 1 - i))
        }
        for (i in 8 until qr.size - 8) assertEquals(i % 2 == 0, qr.isDark(i, 6))
        assertEquals(false, qr.isDark(-1, 0))
        assertEquals(false, qr.isDark(qr.size, 0))
    }

    @Test fun textThatIsTooLongIsRefused() {
        assertFailsWith<IllegalArgumentException> { QrCode.encode("x".repeat(QrCode.MAX_BYTES + 1)) }
        QrCode.encode("x".repeat(QrCode.MAX_BYTES))
    }
}
