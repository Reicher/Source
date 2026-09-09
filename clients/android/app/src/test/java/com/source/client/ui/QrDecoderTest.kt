package com.source.client.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    @Test
    fun `decodes Source pairing payload from luminance data`() {
        val payload = "source://pair?v=1&node_id=srcnode_test"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 320, 320)
        val luminance = ByteArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0 else 0xff.toByte()
        }

        assertEquals(payload, QrDecoder.decodeLuminance(luminance, matrix.width, matrix.height))
    }

    @Test
    fun `ignores frames without a QR code`() {
        assertNull(QrDecoder.decodeLuminance(ByteArray(64 * 64) { 0xff.toByte() }, 64, 64))
    }
}
