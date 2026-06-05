package com.offline.dpadmessenger.app

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
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Render [data] as a square QR code Bitmap and display it. Uses ZXing's
 * pure-Java QRCodeWriter — no Android dependencies beyond Bitmap.
 *
 * Sized at 240dp by default so it's scannable from the Flip 2's 2.4"
 * screen at arm's length (typical for the user pointing their primary
 * Signal phone at it).
 */
@Composable
fun QrCode(
    data: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    val bitmap = remember(data) { encodeAsBitmap(data, pixelSize = 480) }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun encodeAsBitmap(data: String, pixelSize: Int): Bitmap {
    val writer = QRCodeWriter()
    val matrix = writer.encode(data, BarcodeFormat.QR_CODE, pixelSize, pixelSize)
    val bmp = Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.RGB_565)
    for (x in 0 until pixelSize) {
        for (y in 0 until pixelSize) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}
