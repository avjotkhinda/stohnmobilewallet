package org.stohncoin.wallet.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The address QR, drawn through the KTX path `createBitmap(size, size)` and `bitmap[x, y] = colour`.
 *
 * That conversion was only ever compile-checked: nothing drew a bitmap, so a wrong default config or
 * a misplaced write would have reached a phone unnoticed. This test draws the code at the size the
 * Receive page asks for (720), checks its pixels, and decodes the result back to the payload with
 * zxing's own reader — a round trip fails on a transposed write, a bad row stride or inverted
 * modules, none of which a size assertion or "some pixel is black" would catch.
 *
 * The size matters to the check: at 61 px this same code draws correctly but has roughly one pixel
 * per module, which is below what the detector can find, so the round trip is only meaningful at a
 * size a caller actually uses.
 */
@RunWith(RobolectricTestRunner::class)
class QrTest {

    @Test
    fun drawsARequestedSizeCodeWhoseModulesDecodeBackToThePayload() {
        val size = 720
        val payload = "stohncoin:StTestAddressForQr?amount=0.125"

        val bitmap = qr(payload, size)

        assertNotNull("qr() must return a bitmap for a plausible payload", bitmap)
        requireNotNull(bitmap)
        assertEquals("the requested width must be honoured", size, bitmap.width)
        assertEquals("the requested height must be honoured", size, bitmap.height)
        assertEquals(
            "createBitmap must keep the ARGB_8888 config the old call passed explicitly",
            Bitmap.Config.ARGB_8888,
            bitmap.config
        )

        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        assertEquals(
            "the top-left corner must stay in the encoder's white quiet zone",
            Color.WHITE,
            pixels[0]
        )
        val black = pixels.count { it == Color.BLACK }
        assertTrue("modules must be drawn in black: found $black of ${pixels.size}", black > pixels.size / 10)
        assertTrue("the code must not be a solid block: found $black of ${pixels.size}", black < pixels.size * 7 / 10)

        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        )
        assertEquals("the drawn modules must decode back to the payload", payload, decoded.text)
    }
}
