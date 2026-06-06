package com.offline.dpadmessenger.backend.gmessages.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Render [data] as a square QR Bitmap. Pure-Java ZXing QRCodeWriter, no
 * Android-specific zxing dependency. Mirrors the QrCode composable in
 * dpad-messenger-backend/app so the pairing UX matches the Signal flow.
 *
 * The Google Messages pairing URL is long (~150+ chars), so we use
 * error-correction level L (more data capacity) and a tight margin to keep
 * the modules large enough to scan from the Flip's small screen.
 */
@Composable
fun QrCode(
    data: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    val bitmap = remember(data) { encodeAsBitmap(data, pixelSize = 600) }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing QR code",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun encodeAsBitmap(data: String, pixelSize: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, pixelSize, pixelSize, hints)
    val bmp = Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.RGB_565)
    for (x in 0 until pixelSize) {
        for (y in 0 until pixelSize) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}
