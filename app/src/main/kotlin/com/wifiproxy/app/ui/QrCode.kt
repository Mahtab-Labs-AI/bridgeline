package com.wifiproxy.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders [content] as a real, scannable QR bitmap via zxing's [QRCodeWriter]. CPU-bound
 *  (encoding plus a 512×512 pixel fill): call it off the main thread. */
fun qrCodeBitmap(content: String, sizePx: Int = 512): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx) { i ->
        if (matrix.get(i % sizePx, i / sizePx)) AndroidColor.BLACK else AndroidColor.WHITE
    }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    return bitmap.asImageBitmap()
}

/** Encodes [content] on [Dispatchers.Default] (the emulator saw ANRs encoding on Main) and shows
 *  a placeholder of the same size until the bitmap is ready. */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val imageBitmap by produceState<ImageBitmap?>(initialValue = null, content) {
        value = null
        value = withContext(Dispatchers.Default) { qrCodeBitmap(content) }
    }
    val bitmap = imageBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "QR code",
            modifier = modifier.size(size),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .semantics { contentDescription = "QR code loading" },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}
